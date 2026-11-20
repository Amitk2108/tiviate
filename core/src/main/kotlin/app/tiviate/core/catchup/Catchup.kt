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

    private fun supported(type: String?, source: String?): Boolean =
        type?.trim()?.lowercase() in setOf("xc", "shift", "flussonic", "flussonic-hls", "flussonic-ts", "fs") || !source.isNullOrBlank()

    /**
     * The archive link for the programme from [startMs] to [endMs], or null when the channel has none.
     * [zone]: the Xtream server's time zone, as its timeshift links use the server's local time.
     */
    fun url(type: String?, source: String?, liveUrl: String, startMs: Long, endMs: Long, nowMs: Long, zone: ZoneId = ZoneOffset.UTC): String? {
        if (!supported(type, source)) return null
        val t = Times(startMs / 1000, endMs / 1000, nowMs / 1000)
        return when (type?.trim()?.lowercase()) {
            "xc" -> xtream(liveUrl, t, zone)
            "shift" -> liveUrl + (if ('?' in liveUrl) "&" else "?") + "utc=${t.start}&lutc=${t.now}"
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(liveUrl, t)
            "append" -> liveUrl + expand(source!!, t)
            // "default" (or no type): the source is the whole link; a relative one is added to the stream link.
            else -> expand(source!!, t).let { if (it.contains("://")) it else liveUrl + it }
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

    /** Flussonic: `…/index.m3u8` → `…/archive-<start>-<duration>.m3u8`; `…/mpegts` → `…/timeshift_abs-<start>.ts`. */
    private fun flussonic(liveUrl: String, t: Times): String? {
        val query = liveUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val path = liveUrl.substringBefore('?')
        val dir = path.substringBeforeLast('/')
        val file = path.substringAfterLast('/')
        return when {
            file.endsWith(".m3u8") -> "$dir/archive-${t.start}-${t.duration}.m3u8$query"
            file == "mpegts" || file.endsWith(".ts") -> "$dir/timeshift_abs-${t.start}.ts$query"
            else -> null
        }
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
