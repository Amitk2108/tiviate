package app.tiviate.core.drm

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.Base64

/**
 * How a protected channel is unlocked, as a playlist describes it (Kodi's `#KODIPROP` lines): a licence
 * server to ask, or ClearKey keys given in the playlist. Tiviate only plays what the provider unlocks; it
 * never works around protection.
 */
data class DrmLicense(
    /** Licence server (Widevine, PlayReady, or ClearKey from a server). */
    val url: String? = null,
    /** Extra request headers for the licence server. */
    val headers: Map<String, String> = emptyMap(),
    /** ClearKey keys from the playlist: key ID → key, both as 32 lowercase hex digits. */
    val clearKeys: Map<String, String> = emptyMap(),
)

object Drm {
    const val WIDEVINE = "widevine"
    const val CLEARKEY = "clearkey"
    const val PLAYREADY = "playready"

    /** `com.widevine.alpha` → widevine, `org.w3.clearkey` → clearkey, …; null for anything else. */
    fun scheme(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        "com.widevine.alpha", "widevine" -> WIDEVINE
        "org.w3.clearkey", "clearkey" -> CLEARKEY
        "com.microsoft.playready", "com.microsoft.playready.recommendation", "playready" -> PLAYREADY
        else -> null
    }

    /**
     * Reads a `license_key`: a licence URL (`url|Header=value&Other=value|…`), ClearKey pairs
     * (`kid:key,kid:key` in hex), or JSON (`{"kid":"key"}` in hex, or a JWK set `{"keys":[…]}`).
     */
    fun license(raw: String?): DrmLicense? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            val parts = text.split('|')
            val headers = parts.getOrNull(1).orEmpty().split('&').mapNotNull { h ->
                val eq = h.indexOf('=')
                if (eq <= 0) null else decode(h.substring(0, eq)) to decode(h.substring(eq + 1))
            }.toMap()
            return DrmLicense(url = parts[0], headers = headers)
        }
        val keys = if (text.startsWith("{")) jsonKeys(text) else pairKeys(text)
        return keys.takeIf { it.isNotEmpty() }?.let { DrmLicense(clearKeys = it) }
    }

    /** The JWK set Android's ClearKey takes: `{"keys":[{"kty":"oct","k":…,"kid":…}],"type":"temporary"}`. */
    fun clearKeyResponse(keys: Map<String, String>): String = keys.entries.joinToString(",", "{\"keys\":[", "],\"type\":\"temporary\"}") { (kid, key) ->
        "{\"kty\":\"oct\",\"k\":\"${b64url(hex(key))}\",\"kid\":\"${b64url(hex(kid))}\"}"
    }

    /**
     * A W3C "common" PSSH box naming [keyIds]. Streams that only say "ClearKey" in their manifest need it
     * to start the decryption session.
     */
    fun commonPssh(keyIds: List<String>): ByteArray {
        val size = 4 + 4 + 4 + 16 + 4 + 16 * keyIds.size + 4
        return ByteBuffer.allocate(size).apply {
            putInt(size)
            put("pssh".toByteArray())
            putInt(0x01000000) // version 1, no flags
            put(hex("1077efecc0b24d02ace33c1e52e2fb4b"))
            putInt(keyIds.size)
            keyIds.forEach { put(hex(it)) }
            putInt(0) // no data
        }.array()
    }

    private fun pairKeys(text: String): Map<String, String> = text.split(',', ';', ' ', '\n').mapNotNull { pair ->
        val kid = cleanHex(pair.substringBefore(':', ""))
        val key = cleanHex(pair.substringAfter(':', ""))
        if (kid != null && key != null) kid to key else null
    }.toMap()

    /** `{"kid":"key"}` (hex) or a JWK set (base64url). */
    private fun jsonKeys(text: String): Map<String, String> {
        if (text.contains("\"keys\"")) {
            val k = Regex(""""k"\s*:\s*"([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
            val kid = Regex(""""kid"\s*:\s*"([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
            return kid.zip(k).mapNotNull { (i, v) -> fromB64OrHex(i)?.let { a -> fromB64OrHex(v)?.let { b -> a to b } } }.toMap()
        }
        return Regex(""""([^"]+)"\s*:\s*"([^"]+)"""").findAll(text).mapNotNull { m ->
            val kid = cleanHex(m.groupValues[1]) ?: fromB64OrHex(m.groupValues[1])
            val key = cleanHex(m.groupValues[2]) ?: fromB64OrHex(m.groupValues[2])
            if (kid != null && key != null) kid to key else null
        }.toMap()
    }

    private fun cleanHex(s: String): String? = s.trim().replace("-", "").lowercase().takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    private fun fromB64OrHex(s: String): String? = cleanHex(s) ?: runCatching {
        Base64.getUrlDecoder().decode(s.trim().replace('+', '-').replace('/', '_').trimEnd('=')).takeIf { it.size == 16 }?.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }.getOrNull()

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
