package app.tiviate.data.sync

import app.tiviate.core.m3u.M3uParser
import app.tiviate.core.match.NameEditor
import app.tiviate.core.match.StableKeyAssigner
import app.tiviate.core.xtream.XtreamCredentials
import app.tiviate.core.xtream.XtreamJson
import app.tiviate.core.xtream.XtreamUrls
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import app.tiviate.data.db.TiviateDatabase
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.buffer
import okio.source

data class PlaylistSyncResult(val channels: Int, val skipped: Int, val detectedEpgUrl: String?)

/**
 * Downloads and parses a playlist straight into the database in batches. Rows are stamped with
 * this sync's time; rows from earlier syncs are deleted only after the whole playlist was read,
 * so a failed sync never empties the channel list.
 */
class PlaylistSyncer(
    private val db: TiviateDatabase,
    private val http: OkHttpClient,
    private val openLocal: (String) -> InputStream,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun sync(playlistId: Long, onProgress: (Int) -> Unit = {}): PlaylistSyncResult = withContext(Dispatchers.IO) {
        val playlist = db.playlists().get(playlistId) ?: throw SyncException("Playlist not found")
        val stamp = clock()
        try {
            val writer = ChannelWriter(playlist.id, stamp, onProgress)
            val prefixes = NameEditor.parse(playlist.namePrefixes)
            val suffixes = NameEditor.parse(playlist.nameSuffixes)
            writer.cleanName = { NameEditor.clean(it, prefixes, suffixes) }
            val result = when (playlist.type) {
                PlaylistType.M3U_URL -> http.getOrThrow(requireNotNull(playlist.url), playlist.userAgent, "Playlist").use {
                    syncM3u(it.body.byteStream(), writer)
                }
                PlaylistType.M3U_FILE -> openLocal(requireNotNull(playlist.url)).use { syncM3u(it, writer) }
                PlaylistType.XTREAM -> syncXtream(playlist, writer)
            }
            writer.flush()
            db.channels().deleteStale(playlist.id, keepStamp = stamp)
            db.playlists().update(
                playlist.copy(lastSyncAt = stamp, lastError = null, detectedEpgUrl = result.detectedEpgUrl ?: playlist.detectedEpgUrl),
            )
            EpgLinker(db).relink(playlist.id)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e as? SyncException ?: SyncException(e.message ?: e.javaClass.simpleName, e)
            db.playlists().get(playlist.id)?.let { db.playlists().update(it.copy(lastError = error.message)) }
            throw error
        }
    }

    private fun syncM3u(input: InputStream, writer: ChannelWriter): PlaylistSyncResult {
        val keys = StableKeyAssigner()
        var epgUrl: String? = null
        val stats = M3uParser.parse(
            input.source().buffer(),
            onHeader = { header -> if (epgUrl == null) epgUrl = header.epgUrls.firstOrNull() },
        ) { e ->
            val position = writer.count + 1
            writer.add(
                ChannelEntity(
                    playlistId = writer.playlistId,
                    stableKey = keys.keyFor(null, e.tvgId, e.name, e.groupTitle),
                    number = e.tvgChno ?: position,
                    name = e.name,
                    groupName = e.groupTitle,
                    logoUrl = e.tvgLogo,
                    streamUrl = e.url,
                    tvgId = e.tvgId,
                    tvgName = e.tvgName,
                    userAgent = e.userAgent,
                    referrer = e.referrer,
                    epgRef = null,
                    logoFallback = null,
                    catchupDays = e.catchupDays,
                    sortIndex = position,
                    syncStamp = writer.stamp,
                ),
            )
        }
        return PlaylistSyncResult(stats.entries, stats.skipped, epgUrl)
    }

    private fun syncXtream(p: PlaylistEntity, writer: ChannelWriter): PlaylistSyncResult {
        val creds = XtreamCredentials(
            XtreamUrls.normalizeServer(requireNotNull(p.xtreamServer)),
            requireNotNull(p.xtreamUser),
            requireNotNull(p.xtreamPass),
        )
        val account = http.getOrThrow(XtreamUrls.api(creds), p.userAgent, "Account").use { XtreamJson.account(it.body.source()) }
        if (!account.authorized) throw SyncException("Xtream login failed — check the server, username and password")
        val ext = XtreamUrls.preferredExtension(account.allowedFormats, p.preferHls)

        val categoryList = http.getOrThrow(XtreamUrls.api(creds, "get_live_categories"), p.userAgent, "Categories")
            .use { XtreamJson.categories(it.body.source()) }
        val categories = categoryList.associate { it.id to it.name }
        // Groups show in the provider's category order (not where their first channel happens to be):
        // each category gets a band of sort positions, and channels keep the provider's order inside it.
        val rank = HashMap<String, Int>().apply { categoryList.forEach { putIfAbsent(it.id, size) } }
        val band = Int.MAX_VALUE.toLong() / (categoryList.size + 2)

        val keys = StableKeyAssigner()
        val count = http.getOrThrow(XtreamUrls.api(creds, "get_live_streams"), p.userAgent, "Channels").use { response ->
            XtreamJson.liveStreams(response.body.source()) { s ->
                val position = writer.count + 1
                val group = s.categoryId?.let(categories::get)
                writer.add(
                    ChannelEntity(
                        playlistId = writer.playlistId,
                        stableKey = keys.keyFor(s.streamId, s.epgChannelId, s.name, group),
                        number = position, // provider "num" values often repeat; list order keeps number entry unambiguous
                        name = s.name,
                        groupName = group,
                        logoUrl = s.icon,
                        streamUrl = XtreamUrls.live(creds, s.streamId, ext),
                        tvgId = s.epgChannelId,
                        tvgName = null,
                        userAgent = null,
                        referrer = null,
                        epgRef = null,
                        logoFallback = null,
                        catchupDays = if (s.tvArchive) s.tvArchiveDays else null,
                        sortIndex = ((s.categoryId?.let(rank::get) ?: categoryList.size) * band + position)
                            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        syncStamp = writer.stamp,
                    ),
                )
            }
        }
        return PlaylistSyncResult(count, 0, XtreamUrls.xmltv(creds))
    }

    /** Buffers rows and writes them in transactions of [BATCH] to keep memory flat on large playlists. */
    private inner class ChannelWriter(val playlistId: Long, val stamp: Long, private val onProgress: (Int) -> Unit) {
        private val buffer = ArrayList<ChannelEntity>(BATCH)
        var cleanName: (String) -> String = { it }
        var count = 0
            private set

        /** Keeps the playlist's own name, shows the cleaned one, and dates new channels. */
        fun add(row: ChannelEntity) {
            buffer += row.copy(originalName = row.name, name = cleanName(row.name), addedAt = stamp)
            count++
            if (buffer.size >= BATCH) flush()
        }

        fun flush() {
            if (buffer.isEmpty()) return
            db.channels().upsertBlocking(playlistId, ArrayList(buffer))
            buffer.clear()
            onProgress(count)
        }
    }

    private companion object {
        const val BATCH = 500
    }
}
