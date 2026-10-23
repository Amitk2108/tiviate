package app.tiviate.data

import androidx.room.withTransaction
import app.tiviate.core.match.NameEditor
import app.tiviate.core.xtream.XtreamAccount
import app.tiviate.core.xtream.XtreamCredentials
import app.tiviate.core.xtream.XtreamJson
import app.tiviate.core.xtream.XtreamUrls
import app.tiviate.data.db.EpgOrigin
import app.tiviate.data.db.EpgSourceEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import app.tiviate.data.db.TiviateDatabase
import app.tiviate.data.sync.EpgSyncer
import app.tiviate.data.sync.PlaylistSyncer
import app.tiviate.data.sync.SyncScheduler
import app.tiviate.data.sync.getOrThrow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

sealed interface SyncProgress {
    data class Channels(val count: Int) : SyncProgress
    data class Guide(val programmes: Int) : SyncProgress
    data class Failed(val message: String) : SyncProgress
    data object Done : SyncProgress
}

class PlaylistRepository(
    private val db: TiviateDatabase,
    private val http: OkHttpClient,
    private val playlistSyncer: PlaylistSyncer,
    private val epgSyncer: EpgSyncer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val playlists: Flow<List<PlaylistEntity>> = db.playlists().observeAll()

    /**
     * One writer at a time: overlapping syncs would delete each other's freshly written channels
     * (stale-stamp cleanup) and write into the same next guide generation.
     */
    private val syncLock = Mutex()

    suspend fun get(id: Long): PlaylistEntity? = db.playlists().get(id)

    suspend fun addM3uUrl(name: String, url: String, epgUrl: String?, userAgent: String?): Long =
        db.playlists().insert(
            PlaylistEntity(name = name, type = PlaylistType.M3U_URL, url = url.trim(), epgUrl = epgUrl.blankToNull(), userAgent = userAgent.blankToNull()),
        )

    suspend fun addM3uFile(name: String, uri: String, epgUrl: String?): Long =
        db.playlists().insert(PlaylistEntity(name = name, type = PlaylistType.M3U_FILE, url = uri, epgUrl = epgUrl.blankToNull()))

    suspend fun addXtream(name: String, creds: XtreamCredentials, preferHls: Boolean, userAgent: String? = null): Long =
        db.playlists().insert(
            PlaylistEntity(
                name = name,
                type = PlaylistType.XTREAM,
                xtreamServer = XtreamUrls.normalizeServer(creds.server),
                xtreamUser = creds.username.trim(),
                xtreamPass = creds.password,
                preferHls = preferHls,
                userAgent = userAgent.blankToNull(),
            ),
        )

    /** Saves edited details; the next sync uses them (favorites and history follow the channels' stable keys). */
    suspend fun updateM3uUrl(id: Long, name: String, url: String, epgUrl: String?, userAgent: String?) = edit(id) {
        it.copy(name = name.trim().ifEmpty { it.name }, url = url.trim(), epgUrl = epgUrl.blankToNull(), userAgent = userAgent.blankToNull())
    }

    /** Names editor: saves the lists and renames the playlist's channels right away. */
    suspend fun setNameEditor(id: Long, prefixes: String?, suffixes: String?) {
        edit(id) { it.copy(namePrefixes = prefixes.blankToNull(), nameSuffixes = suffixes.blankToNull()) }
        val p = db.playlists().get(id) ?: return
        val pre = NameEditor.parse(p.namePrefixes)
        val suf = NameEditor.parse(p.nameSuffixes)
        db.withTransaction {
            for (row in db.channels().nameRows(id)) {
                if (row.renamed) continue
                val cleaned = NameEditor.clean(row.originalName ?: row.name, pre, suf)
                if (cleaned != row.name) db.channels().setName(row.id, cleaned, renamed = false)
            }
        }
    }

    suspend fun updateM3uFile(id: Long, name: String, uri: String, epgUrl: String?) = edit(id) {
        it.copy(name = name.trim().ifEmpty { it.name }, url = uri, epgUrl = epgUrl.blankToNull())
    }

    /** [preferHls] / [userAgent] null = keep as is; a blank User-Agent clears it. */
    suspend fun updateXtream(id: Long, name: String, creds: XtreamCredentials, preferHls: Boolean? = null, userAgent: String? = null) = edit(id) {
        it.copy(
            name = name.trim().ifEmpty { it.name },
            xtreamServer = XtreamUrls.normalizeServer(creds.server),
            xtreamUser = creds.username.trim(),
            xtreamPass = creds.password,
            preferHls = preferHls ?: it.preferHls,
            userAgent = if (userAgent == null) it.userAgent else userAgent.blankToNull(),
        )
    }

    private suspend fun edit(id: Long, change: (PlaylistEntity) -> PlaylistEntity) {
        val p = db.playlists().get(id) ?: return
        db.playlists().update(change(p).copy(lastError = null))
    }

    /** Playlist first, then its guide. Guide failures are recorded per source and never fail the playlist. */
    fun sync(playlistId: Long): Flow<SyncProgress> = channelFlow {
        send(SyncProgress.Channels(0))
        syncLock.withLock {
            try {
                playlistSyncer.sync(playlistId) { trySend(SyncProgress.Channels(it)) }
                send(SyncProgress.Guide(0))
                epgSyncer.syncForPlaylist(playlistId) { trySend(SyncProgress.Guide(it)) }
                send(SyncProgress.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(SyncProgress.Failed(e.message ?: "Sync failed"))
            }
        }
    }

    suspend fun refreshGuide(playlistId: Long) = syncLock.withLock { epgSyncer.syncForPlaylist(playlistId) }

    val epgSources = db.epg().observeSources()

    suspend fun addEpgSource(url: String): Long {
        val clean = url.trim()
        val name = clean.substringAfter("://").substringBefore('/').ifBlank { "Guide" }
        val priority = (db.epg().observeSources().first().filter { it.origin == EpgOrigin.MANUAL }.maxOfOrNull { it.priority } ?: -1) + 1
        return db.epg().insertSource(EpgSourceEntity(url = clean, name = name, origin = EpgOrigin.MANUAL, priority = priority))
    }

    suspend fun renameEpgSource(id: Long, name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        db.epg().source(id)?.let { db.epg().updateSource(it.copy(name = clean)) }
    }

    suspend fun deleteEpgSource(id: Long) {
        db.withTransaction {
            db.epg().deleteProgrammes(id)
            db.epg().deleteChannels(id)
            db.epg().deleteSource(id)
        }
    }

    suspend fun channelCount(playlistId: Long): Int = db.channels().count(playlistId)

    suspend fun clearHistory(playlistId: Long) = db.history().clear(playlistId)

    suspend fun syncAll() = syncLock.withLock {
        val enabled = db.playlists().all().filter { it.enabled }.map { it.id }
        for (id in enabled) runCatching { playlistSyncer.sync(id) }
        runCatching { epgSyncer.syncPlaylists(enabled) } // shared guide links download once
    }

    /**
     * The scheduled update (background job, and once when Tiviate starts): playlists that are due
     * under [mode], then the guide. At start the guide only updates when [guideOnStart] is on and it
     * is over an hour old, or when a playlist was just reloaded.
     */
    suspend fun scheduledUpdate(mode: PlaylistUpdate, atStart: Boolean, guideOnStart: Boolean) = syncLock.withLock {
        val enabled = db.playlists().all().filter { it.enabled }
        val now = clock()
        val due = enabled.filter { SyncScheduler.playlistDue(mode, it.lastSyncAt, now, atStart) }
        for (p in due) runCatching { playlistSyncer.sync(p.id) }
        if (!atStart || due.isNotEmpty() || SyncScheduler.shouldSyncOnStart(guideOnStart, oldestGuideUpdate(enabled), now)) {
            runCatching { epgSyncer.syncPlaylists(enabled.map { it.id }) } // shared guide links download once
        }
    }

    /** Is there anything for the start-up update to do? */
    suspend fun needsStartSync(mode: PlaylistUpdate, guideOnStart: Boolean): Boolean {
        val enabled = db.playlists().all().filter { it.enabled }
        val now = clock()
        return enabled.any { SyncScheduler.playlistDue(mode, it.lastSyncAt, now, atStart = true) } ||
            (enabled.isNotEmpty() && SyncScheduler.shouldSyncOnStart(guideOnStart, oldestGuideUpdate(enabled), now))
    }

    /** When the least recently updated guide source of these playlists was updated; null if one never was. */
    private suspend fun oldestGuideUpdate(playlists: List<PlaylistEntity>): Long? {
        val sources = playlists.flatMap { db.epg().sourcesFor(it.id) }.filter { it.enabled }
        if (sources.isEmpty()) return playlists.minOfOrNull { it.lastSyncAt ?: return null }
        return sources.minOf { it.lastSyncAt ?: return null }
    }

    suspend fun delete(playlistId: Long) = syncLock.withLock {
        db.withTransaction {
            db.epg().autoSource(playlistId)?.let { src ->
                db.epg().deleteProgrammes(src.id)
                db.epg().deleteChannels(src.id)
                db.epg().deleteSource(src.id)
            }
            db.channels().deleteAll(playlistId)
            db.organise().clearMembersOfPlaylist(playlistId)
            db.organise().clearCustomGroups(playlistId)
            db.organise().clearGroupPrefs(playlistId)
            db.favorites().clear(playlistId)
            db.history().clear(playlistId)
            db.playlists().delete(playlistId)
        }
    }

    suspend fun xtreamAccount(playlistId: Long): XtreamAccount? = withContext(Dispatchers.IO) {
        val p = db.playlists().get(playlistId)?.takeIf { it.type == PlaylistType.XTREAM } ?: return@withContext null
        val creds = XtreamCredentials(p.xtreamServer.orEmpty(), p.xtreamUser.orEmpty(), p.xtreamPass.orEmpty())
        runCatching { http.getOrThrow(XtreamUrls.api(creds), p.userAgent, "Account").use { XtreamJson.account(it.body.source()) } }.getOrNull()
    }

    private fun String?.blankToNull() = this?.trim()?.takeIf { it.isNotEmpty() }
}
