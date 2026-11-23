package app.tiviate.core.catchup

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * Archive ("catch-up") links for programmes that already aired. Covers the formats IPTV playlists use:
 * `catchup="default"` / `"append"` with a `catchup-source` template, `"shift"`, `"flussonic"`, and
 * `"xc"` (Xtream Codes timeshift, also used for Xtream accounts).
 */
object Catchup {

    /** How far back a channel that announces catch-up without `catchup-days` is assumed to reach. */
    const val DEFAULT_DAYS = 7

    /** Whether a programme from [startMs] to [endMs] can be watched from the archive. */
    fun available(type: String?, source: String?, days: Int?, startMs: Long, endMs: Long, nowMs: Long): Boolean {
        if (!supported(type, source) || endMs > nowMs) return false
        val reach = (days?.takeIf { it > 0 } ?: DEFAULT_DAYS) * 86_400_000L
        return startMs >= nowMs - reach
    }

    /** Whether the channel's playlist entry describes an archive Tiviate can play. */
    fun supported(type: String?, source: String?): Boolean =
        type?.trim()?.lowercase() in setOf("xc", "shift", "flussonic", "flussonic-hls", "flussonic-ts", "fs") || !source.isNullOrBlank()

    /**
     * The archive links for the programme from [startMs] to [endMs], best first (empty when the channel has
     * none): the player tries the next one when a link doesn't open.
     * [zone]: the Xtream server's time zone, as its timeshift links use the server's local time.
     */
    fun urls(type: String?, source: String?, liveUrl: String, startMs: Long, endMs: Long, nowMs: Long, zone: ZoneId = ZoneOffset.UTC): List<String> {
        if (!supported(type, source)) return emptyList()
        val t = Times(startMs / 1000, endMs / 1000, nowMs / 1000)
        return when (val kind = type?.trim()?.lowercase()) {
            "xc" -> listOfNotNull(xtream(liveUrl, t, zone))
            "shift" -> listOf(liveUrl + (if ('?' in liveUrl) "&" else "?") + "utc=${t.start}&lutc=${t.now}")
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(liveUrl, t, preferTs = kind == "flussonic-ts" || kind == "fs")
            "append" -> listOf(liveUrl + expand(source!!, t))
            // "default" (or no type): the source is the whole link; a relative one is added to the stream link.
            else -> listOf(expand(source!!, t).let { if (it.contains("://")) it else liveUrl + it })
        }
    }

    private class Times(val start: Long, val end: Long, val now: Long) {
        val duration get() = (end - start).coerceAtLeast(0)
    }

    private val XTREAM_LIVE = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?(?:\?.*)?$""")

    /** `http://host/live/user/pass/123.ts` → `http://host/timeshift/user/pass/<minutes>/<Y-m-d:H-M>/123.ts`. */
    private fun xtream(liveUrl: String, t: Times, zone: ZoneId): String? {
        val m = XTREAM_LIVE.find(liveUrl) ?: return null
        val (base, user, pass, id) = m.destructured
        val minutes = (t.duration + 59) / 60
        val start = format(t.start, "Y-m-d:H-M", zone)
        return "$base/timeshift/$user/$pass/$minutes/$start/$id.ts"
    }

    /**
     * Flussonic. Servers differ by version and by the shape of the live link, so every form they use is offered:
     * `…/index.m3u8` → `archive-<start>-<duration>.m3u8` and the older names; `…/mono.m3u8` keeps its track;
     * `…/mpegts` → `timeshift_abs-<start>.ts`. Any other link: its last part is the output's name, or the
     * stream itself.
     */
    private fun flussonic(liveUrl: String, t: Times, preferTs: Boolean): List<String> {
        val query = liveUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val path = liveUrl.substringBefore('?').trimEnd('/')
        val dir = path.substringBeforeLast('/')
        val file = path.substringAfterLast('/')
        val span = "${t.start}-${t.duration}"
        val ago = t.now - t.start
        fun hls(base: String) = listOf("archive-$span", "index-$span", "timeshift_abs-${t.start}", "timeshift_rel-$ago").map { "$base/$it.m3u8" }
        fun ts(base: String) = listOf("$base/timeshift_abs-${t.start}.ts", "$base/archive-$span.ts", "$base/archive-$span.m3u8")
        val track = file.removeSuffix(".m3u8")
        val links = when {
            file == "mpegts" || file.endsWith(".ts") -> ts(dir)
            file == "index.m3u8" -> hls(dir)
            file.endsWith(".m3u8") -> listOf("$dir/$track-$span.m3u8", "$dir/archive-$span.m3u8", "$dir/$track-timeshift_rel-$ago.m3u8")
            else -> listOfNotNull(dir.takeIf { '/' in it.substringAfter("://") }, path).flatMap { if (preferTs) ts(it) else hls(it) }
        }
        return links.map { it + query }
    }

    private val PLACEHOLDER = Regex("""\$?\{([A-Za-z-]+)(?::([^}]*))?\}""")

    /** Fills a `catchup-source` template ({utc}, {utcend}, {lutc}, {duration}, {Y}{m}{d}{H}{M}{S}, ${start}, …). */
    private fun expand(template: String, t: Times): String = PLACEHOLDER.replace(template) { m ->
        val name = m.groupValues[1]
        val arg = m.groupValues[2]
        val divisor = arg.toLongOrNull()?.takeIf { it > 0 }
        fun time(sec: Long) = if (arg.isNotEmpty() && divisor == null) format(sec, arg, ZoneOffset.UTC) else sec.toString()
        when (name) {
            "utc", "start", "timestamp-start" -> time(t.start)
            "utcend", "end" -> time(t.end)
            "lutc", "now", "timestamp" -> time(t.now)
            "duration" -> (t.duration / (divisor ?: 1)).toString()
            "offset" -> ((t.now - t.start) / (divisor ?: 1)).toString()
            "Y", "m", "d", "H", "M", "S" -> format(t.start, name, ZoneOffset.UTC)
            else -> m.value // unknown: left as written
        }
    }

    /** Formats epoch seconds with Y m d H M S letters (anything else is copied). */
    private fun format(sec: Long, pattern: String, zone: ZoneId): String {
        val d = Instant.ofEpochSecond(sec).atZone(zone)
        return buildString {
            for (c in pattern) append(
                when (c) {
                    'Y' -> d.year.toString()
                    'm' -> String.format(Locale.ROOT, "%02d", d.monthValue)
                    'd' -> String.format(Locale.ROOT, "%02d", d.dayOfMonth)
                    'H' -> String.format(Locale.ROOT, "%02d", d.hour)
                    'M' -> String.format(Locale.ROOT, "%02d", d.minute)
                    'S' -> String.format(Locale.ROOT, "%02d", d.second)
                    else -> c.toString()
                },
            )
        }
    }
}
