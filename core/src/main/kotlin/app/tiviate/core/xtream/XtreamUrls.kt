package app.tiviate.core.xtream

import java.net.URLEncoder

object XtreamUrls {

    /** Accepts what users paste (bare host, trailing slash, a full player_api/get.php link) and returns `scheme://host[:port]`. */
    fun normalizeServer(raw: String): String {
        var s = raw.trim()
        if (!s.contains("://")) s = "http://$s"
        val scheme = s.substringBefore("://")
        val authority = s.substringAfter("://").substringBefore('/').substringBefore('?')
        return "$scheme://$authority"
    }

    fun api(c: XtreamCredentials, action: String? = null): String {
        val base = "${c.server}/player_api.php?username=${enc(c.username)}&password=${enc(c.password)}"
        return if (action == null) base else "$base&action=${enc(action)}"
    }

    fun live(c: XtreamCredentials, streamId: Long, ext: String = "ts"): String =
        "${c.server}/live/${c.username}/${c.password}/$streamId.$ext"

    fun xmltv(c: XtreamCredentials): String =
        "${c.server}/xmltv.php?username=${enc(c.username)}&password=${enc(c.password)}"

    /**
     * Picks the live stream container. Some providers only allow HLS (`["m3u8"]`), so "ts" is used
     * only when the account permits it and the user hasn't asked for HLS.
     */
    fun preferredExtension(allowedFormats: List<String>, preferHls: Boolean): String {
        val allowed = allowedFormats.map { it.lowercase() }
        return when {
            allowed.isEmpty() -> if (preferHls) "m3u8" else "ts"
            preferHls && "m3u8" in allowed -> "m3u8"
            "ts" in allowed -> "ts"
            "m3u8" in allowed -> "m3u8"
            else -> allowed.first()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
