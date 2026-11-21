package app.tiviate.core.m3u

import okio.BufferedSource

/**
 * Streaming M3U/M3U8 playlist parser. Reads one line at a time, so memory use is
 * independent of playlist size. Malformed entries are skipped and counted.
 */
object M3uParser {
    /** A stream link on its own line: a scheme, "://", no spaces (so HTML and other text are not channels). */
    private val BARE_URL = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://\S+$""")


    private val EPG_HEADER_KEYS = listOf("url-tvg", "x-tvg-url", "tvg-url")

    fun parse(
        source: BufferedSource,
        onHeader: (M3uHeader) -> Unit = {},
        onEntry: (M3uEntry) -> Unit,
    ): M3uStats {
        var pending: Pending? = null
        val kodiBefore = HashMap<String, String>() // #KODIPROP lines before the next #EXTINF
        var entries = 0
        var skipped = 0
        var firstLine = true

        while (true) {
            var line = source.readUtf8Line() ?: break
            if (firstLine) {
                line = line.removePrefix("﻿")
                firstLine = false
            }
            line = line.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U") -> onHeader(parseHeader(line.removePrefix("#EXTM3U")))
                line.startsWith("#EXTINF:") -> {
                    if (pending != null) skipped++
                    pending = parseExtinf(line.removePrefix("#EXTINF:")).also { it.kodi.putAll(kodiBefore); kodiBefore.clear() }
                }
                // Kodi properties (DRM, manifest type) come just before or just after #EXTINF.
                line.startsWith("#KODIPROP:") -> {
                    val prop = line.removePrefix("#KODIPROP:")
                    val eq = prop.indexOf('=')
                    if (eq > 0) (pending?.kodi ?: kodiBefore)[prop.substring(0, eq).trim().lowercase()] = prop.substring(eq + 1).trim()
                }
                line.startsWith("#EXTGRP:") -> pending?.extGroup = line.removePrefix("#EXTGRP:").trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:") -> pending?.let { applyVlcOpt(it, line.removePrefix("#EXTVLCOPT:")) }
                line.startsWith("#") -> Unit // Other directives (#KODIPROP, #EXTHTTP…) are not used yet.
                // A line without #EXTINF counts only if it is a link: an HTML error page or other text served
                // instead of the playlist must not turn into "channels".
                pending == null && !BARE_URL.matches(line) -> skipped++
                else -> {
                    onEntry((pending ?: Pending.forBareUrl(line).also { it.kodi.putAll(kodiBefore) }).toEntry(line))
                    kodiBefore.clear()
                    entries++
                    pending = null
                }
            }
        }
        if (pending != null) skipped++
        return M3uStats(entries = entries, skipped = skipped)
    }

    private fun parseHeader(rest: String): M3uHeader {
        val attrs = parseAttributes(rest)
        val epgUrls = EPG_HEADER_KEYS
            .mapNotNull { attrs[it] }
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return M3uHeader(epgUrls, attrs)
    }

    private fun parseExtinf(body: String): Pending {
        val comma = firstUnquotedComma(body)
        val attrSection = if (comma >= 0) body.substring(0, comma) else body
        val title = if (comma >= 0) body.substring(comma + 1).trim() else ""
        // The first token of the attribute section is the duration (e.g. "-1"); attributes follow it.
        val afterDuration = attrSection.trimStart().let { s ->
            val ws = s.indexOfFirst { it.isWhitespace() }
            if (ws < 0) "" else s.substring(ws)
        }
        return Pending(title = title, attrs = parseAttributes(afterDuration))
    }

    private fun firstUnquotedComma(s: String): Int {
        var quote: Char? = null
        for (i in s.indices) {
            val c = s[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == ',' -> return i
            }
        }
        return -1
    }

    /** Parses `key="v"`, `key='v'` and `key=v` pairs. Keys are lower-cased. */
    internal fun parseAttributes(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && s[i].isWhitespace()) i++
            val keyStart = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace()) i++
            if (i >= n || s[i] != '=') {
                // Token without a value (stray word) — skip it.
                while (i < n && !s[i].isWhitespace()) i++
                continue
            }
            val key = s.substring(keyStart, i).lowercase()
            i++ // '='
            val value: String
            if (i < n && (s[i] == '"' || s[i] == '\'')) {
                val q = s[i++]
                val start = i
                while (i < n && s[i] != q) i++
                value = s.substring(start, i)
                if (i < n) i++ // closing quote
            } else {
                val start = i
                while (i < n && !s[i].isWhitespace()) i++
                value = s.substring(start, i)
            }
            if (key.isNotEmpty()) out[key] = value.trim()
        }
        return out
    }

    private fun applyVlcOpt(p: Pending, opt: String) {
        val eq = opt.indexOf('=')
        if (eq <= 0) return
        val key = opt.substring(0, eq).trim().lowercase()
        val value = opt.substring(eq + 1).trim().ifEmpty { return }
        when (key) {
            "http-user-agent" -> p.userAgent = value
            "http-referrer", "http-referer" -> p.referrer = value
        }
    }

    private class Pending(val title: String, val attrs: Map<String, String>) {
        var extGroup: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        /** `#KODIPROP` properties, lowercased keys. */
        val kodi = HashMap<String, String>()

        fun attr(key: String): String? = attrs[key]?.takeIf { it.isNotBlank() }

        /** `drm_legacy=keysystem|license…` (newer Kodi) or `license_type` + `license_key`. */
        private fun drm(): Pair<String?, String?> {
            kodi["inputstream.adaptive.drm_legacy"]?.let { legacy ->
                return app.tiviate.core.drm.Drm.scheme(legacy.substringBefore('|')) to legacy.substringAfter('|', "").ifBlank { null }
            }
            val scheme = app.tiviate.core.drm.Drm.scheme(kodi["inputstream.adaptive.license_type"])
            return scheme to kodi["inputstream.adaptive.license_key"]?.ifBlank { null }?.takeIf { scheme != null }
        }

        fun toEntry(url: String): M3uEntry {
            val tvgName = attr("tvg-name")
            val name = title.ifEmpty { tvgName ?: nameFromUrl(url) }
            return M3uEntry(
                name = name,
                url = url,
                tvgId = attr("tvg-id"),
                tvgName = tvgName,
                tvgLogo = attr("tvg-logo") ?: attr("logo"),
                groupTitle = attr("group-title") ?: extGroup,
                tvgChno = attr("tvg-chno")?.toIntOrNull() ?: attr("channel-number")?.toIntOrNull(),
                catchup = attr("catchup") ?: attr("catchup-type"),
                catchupSource = attr("catchup-source"),
                catchupDays = attr("catchup-days")?.toIntOrNull(),
                userAgent = userAgent ?: attr("user-agent") ?: attr("http-user-agent"),
                referrer = referrer ?: attr("http-referrer") ?: attr("referrer"),
                attrs = attrs,
                drmScheme = drm().first,
                drmLicense = drm().second,
                manifestType = kodi["inputstream.adaptive.manifest_type"]?.lowercase()?.ifBlank { null },
            )
        }

        companion object {
            fun forBareUrl(url: String) = Pending(title = "", attrs = emptyMap())
        }
    }

    private fun nameFromUrl(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifEmpty { url }
}
