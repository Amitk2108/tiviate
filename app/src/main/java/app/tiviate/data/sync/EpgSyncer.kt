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
    private val pastMs: Long = DAY,
    private val futureMs: Long = 7 * DAY,
) {

    suspend fun syncForPlaylist(playlistId: Long, onProgress: (Int) -> Unit = {}): EpgSyncResult = withContext(Dispatchers.IO) {
        val playlist = db.playlists().get(playlistId) ?: return@withContext EpgSyncResult(0, 0, 0)
        ensureAutoSource(playlist)
        val wanted = WantedChannels.from(db.channels().matchRows(playlistId).map { Triple(it.tvgId, it.tvgName, it.name) })

        var total = 0
        var ok = 0
        var failed = 0
        for (source in db.epg().sourcesFor(playlistId)) {
            val ua = if (source.origin == EpgOrigin.AUTO) playlist.userAgent else null
            try {
                val base = total
                total += syncSource(source, ua, wanted) { onProgress(base + it) }
                ok++
            } catch (e: CancellationException) {
                db.epg().deleteGeneration(source.id, source.activeGeneration + 1)
                throw e
            } catch (e: Exception) {
                failed++
                db.epg().deleteGeneration(source.id, source.activeGeneration + 1)
                db.epg().source(source.id)?.let { db.epg().updateSource(it.copy(lastError = e.message ?: e.javaClass.simpleName)) }
            }
        }
        EpgLinker(db).relink(playlistId)
        db.epg().deleteEndedBefore(clock() - pastMs)
        EpgSyncResult(total, ok, failed)
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

    private suspend fun syncSource(source: EpgSourceEntity, userAgent: String?, wanted: WantedChannels, onProgress: (Int) -> Unit): Int {
        val generation = source.activeGeneration + 1
        db.epg().deleteGeneration(source.id, generation)
        val now = clock()
        val handler = Writer(source, generation, wanted, onProgress)
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
    ) : XmltvHandler {
        val channels = ArrayList<EpgChannelEntity>()
        private val wantedIds = HashSet<String>()
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
            val ref = matcher.match(row.tvgId, listOfNotNull(row.tvgName, row.name))?.encode()
            val icon = ref?.let(icons::get)
            if (ref == row.epgRef && icon == row.logoFallback) null else Triple(row.id, ref, icon)
        }
        if (updates.isEmpty()) return
        db.runInTransaction { for ((id, ref, icon) in updates) db.channels().setEpgBlocking(id, ref, icon) }
    }
}
