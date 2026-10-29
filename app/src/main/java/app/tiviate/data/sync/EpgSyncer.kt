package app.tiviate.data.sync

import app.tiviate.core.io.Decompress
import app.tiviate.core.match.EpgMatcher
import app.tiviate.core.match.NameNormalizer
import app.tiviate.core.xmltv.XmltvChannel
import app.tiviate.core.xmltv.XmltvHandler
import app.tiviate.core.xmltv.XmltvParser
import app.tiviate.core.xmltv.XmltvProgramme
import app.tiviate.data.db.EpgChannelEntity
import app.tiviate.data.db.EpgOrigin
import app.tiviate.data.db.EpgSourceEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.ProgrammeEntity
import app.tiviate.data.db.TiviateDatabase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.xmlpull.v1.XmlPullParser

data class EpgSyncResult(val programmes: Int, val okSources: Int, val failedSources: Int)

/**
 * Refreshes every guide source that applies to a playlist. New programmes are written under the
 * next generation and only become visible when the source's generation is swapped, so the guide
 * never goes empty while a refresh runs (or if it fails half way).
 */
class EpgSyncer(
    private val db: TiviateDatabase,
    private val http: OkHttpClient,
    private val newPullParser: () -> XmlPullParser,
    private val clock: () -> Long = System::currentTimeMillis,
    private val futureMs: Long = 7 * DAY,
    /** "Days of past programmes to keep" (setting): how far back the guide reaches. */
    private val pastDays: suspend () -> Int = { 1 },
) {

    suspend fun syncForPlaylist(playlistId: Long, onProgress: (Int) -> Unit = {}): EpgSyncResult =
        syncPlaylists(listOf(playlistId), onProgress)

    /**
     * Refreshes every source the playlists use, each source once. A playlist's own (AUTO) source keeps
     * the channels that playlist uses; a shared (MANUAL) source keeps the channels of every enabled
     * playlist, so refreshing one playlist never drops another playlist's guide.
     */
    suspend fun syncPlaylists(playlistIds: List<Long>, onProgress: (Int) -> Unit = {}): EpgSyncResult = withContext(Dispatchers.IO) {
        val playlists = playlistIds.distinct().mapNotNull { db.playlists().get(it) }
        if (playlists.isEmpty()) return@withContext EpgSyncResult(0, 0, 0)
        val pastMs = pastDays().coerceIn(1, 14) * DAY
        playlists.forEach { ensureAutoSource(it) }
        val sources = playlists.flatMap { db.epg().sourcesFor(it.id) }.distinctBy { it.id }
        val everyPlaylist = (db.playlists().all().filter { it.enabled }.map { it.id } + playlists.map { it.id }).distinct()
        var shared: WantedChannels? = null

        var total = 0
        var ok = 0
        var failed = 0
        for (source in sources) {
            val owner = playlists.firstOrNull { source.origin == EpgOrigin.AUTO && it.id == source.playlistId }
            val wanted = if (owner != null) wantedFor(listOf(owner.id)) else shared ?: wantedFor(everyPlaylist).also { shared = it }
            try {
                val base = total
                total += syncSource(source, owner?.userAgent, wanted, pastMs) { onProgress(base + it) }
                ok++
            } catch (e: CancellationException) {
                withContext(NonCancellable) { discardNextGeneration(source.id) }
                throw e
            } catch (e: Exception) {
                failed++
                discardNextGeneration(source.id)
                db.epg().source(source.id)?.let { db.epg().updateSource(it.copy(lastError = e.message ?: e.javaClass.simpleName)) }
            }
        }
        val relinked = if (shared != null) everyPlaylist else playlists.map { it.id }
        relinked.forEach { EpgLinker(db).relink(it) }
        db.epg().deleteEndedBefore(clock() - pastMs)
        EpgSyncResult(total, ok, failed)
    }

    private suspend fun wantedFor(playlistIds: List<Long>) =
        WantedChannels.from(playlistIds.flatMap { db.channels().matchRows(it) }.map { Triple(it.tvgId, it.tvgName, it.name) })

    /** Drops a half-written refresh; re-reads the source so a generation swapped meanwhile is never touched. */
    private suspend fun discardNextGeneration(sourceId: Long) {
        db.epg().source(sourceId)?.let { db.epg().deleteGeneration(it.id, it.activeGeneration + 1) }
    }

    private suspend fun ensureAutoSource(playlist: PlaylistEntity) {
        val url = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: playlist.detectedEpgUrl ?: return
        val existing = db.epg().autoSource(playlist.id)
        if (existing == null) {
            db.epg().insertSource(EpgSourceEntity(url = url, name = "${playlist.name} guide", origin = EpgOrigin.AUTO, playlistId = playlist.id))
        } else if (existing.url != url) {
            db.epg().updateSource(existing.copy(url = url))
        }
    }

    private suspend fun syncSource(source: EpgSourceEntity, userAgent: String?, wanted: WantedChannels, pastMs: Long, onProgress: (Int) -> Unit): Int {
        val generation = source.activeGeneration + 1
        db.epg().deleteGeneration(source.id, generation)
        val now = clock()
        // Channels assigned to this guide by hand are always wanted, even when no name or tvg-id matches.
        val assigned = db.channels().overridesForSource(source.id).map { it.substringAfter(':') }.toSet()
        val handler = Writer(source, generation, wanted, onProgress, assigned)
        http.getOrThrow(source.url, userAgent, "Guide").use { response ->
            XmltvParser(newPullParser, windowStartMs = now - pastMs, windowEndMs = now + futureMs)
                .parse(Decompress.wrap(response.body.byteStream()), handler)
        }
        handler.flush()
        if (handler.channels.isEmpty()) throw SyncException("Guide has no channels — is this an XMLTV file?")

        db.epg().replaceChannels(source.id, handler.channels)
        db.epg().swapGeneration(source.id, generation)
        db.epg().source(source.id)?.let { db.epg().updateSource(it.copy(lastSyncAt = now, lastError = null)) }
        return handler.count
    }

    private inner class Writer(
        private val source: EpgSourceEntity,
        private val generation: Int,
        private val wanted: WantedChannels,
        private val onProgress: (Int) -> Unit,
        assigned: Set<String> = emptySet(),
    ) : XmltvHandler {
        val channels = ArrayList<EpgChannelEntity>()
        private val wantedIds = HashSet<String>(assigned)
        private val buffer = ArrayList<ProgrammeEntity>(BATCH)
        private val offsetMs = source.timeOffsetMin * 60_000L
        var count = 0
            private set

        override fun onChannel(c: XmltvChannel) {
            channels += EpgChannelEntity(source.id, c.id, c.displayNames.joinToString(EpgChannelEntity.NAME_SEPARATOR), c.iconUrl)
            if (wanted.matches(c)) wantedIds += c.id
        }

        // Programmes listed before their <channel> element can still match directly by tvg-id.
        override fun wantsChannel(channelId: String) = channelId in wantedIds || wanted.hasTvgId(channelId)

        override fun onProgramme(p: XmltvProgramme) {
            buffer += ProgrammeEntity(
                sourceId = source.id,
                epgRef = "${source.id}:${p.channelId}",
                startMs = p.startMs + offsetMs,
                stopMs = p.stopMs + offsetMs,
                title = p.title,
                subtitle = p.subtitle,
                description = p.description,
                category = p.category,
                iconUrl = p.iconUrl,
                episode = p.episode,
                generation = generation,
            )
            count++
            if (buffer.size >= BATCH) flush()
        }

        fun flush() {
            if (buffer.isEmpty()) return
            db.epg().insertProgrammesBlocking(ArrayList(buffer))
            buffer.clear()
            onProgress(count)
        }
    }

    /** O(1) "does any playlist channel correspond to this guide channel?" checks. */
    private class WantedChannels(private val tvgIds: Set<String>, private val names: Set<String>) {
        fun hasTvgId(id: String) = id.lowercase() in tvgIds
        fun matches(c: XmltvChannel) = hasTvgId(c.id) || c.displayNames.any { NameNormalizer.normalize(it) in names }

        companion object {
            fun from(rows: List<Triple<String?, String?, String>>): WantedChannels {
                val ids = HashSet<String>()
                val names = HashSet<String>()
                for ((tvgId, tvgName, name) in rows) {
                    tvgId?.takeIf { it.isNotBlank() }?.let { ids += it.trim().lowercase() }
                    tvgName?.let { NameNormalizer.normalize(it).takeIf(String::isNotEmpty)?.let(names::add) }
                    NameNormalizer.normalize(name).takeIf(String::isNotEmpty)?.let(names::add)
                }
                return WantedChannels(ids, names)
            }
        }
    }

    companion object {
        const val DAY = 86_400_000L
        private const val BATCH = 500
    }
}

/** Re-applies EPG matches (and EPG logos) to a playlist's channels from the stored guide channel lists. */
class EpgLinker(private val db: TiviateDatabase) {

    suspend fun relink(playlistId: Long) {
        val sources = db.epg().sourcesFor(playlistId)
        if (sources.isEmpty()) return
        val matcher = EpgMatcher()
        val icons = HashMap<String, String?>()
        sources.forEachIndexed { priority, source ->
            for (row in db.epg().channels(source.id)) {
                val names = row.displayNames.split(EpgChannelEntity.NAME_SEPARATOR).filter { it.isNotEmpty() }
                matcher.add(source.id, priority, XmltvChannel(row.xmltvId, names, row.iconUrl))
                icons["${source.id}:${row.xmltvId}"] = row.iconUrl
            }
        }
        val updates = db.channels().matchRows(playlistId).mapNotNull { row ->
            val ref = row.epgOverride ?: matcher.match(row.tvgId, listOfNotNull(row.tvgName, row.name))?.encode()
            val icon = ref?.let(icons::get)
            if (ref == row.epgRef && icon == row.logoFallback) null else Triple(row.id, ref, icon)
        }
        if (updates.isEmpty()) return
        db.runInTransaction { for ((id, ref, icon) in updates) db.channels().setEpgBlocking(id, ref, icon) }
    }
}
