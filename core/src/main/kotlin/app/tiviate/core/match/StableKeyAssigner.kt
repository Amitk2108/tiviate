package app.tiviate.core.match

/**
 * Gives each channel a key that survives resyncs, so favorites and history stay attached.
 * Stream URLs are never part of the key: providers rotate tokens inside them.
 * Feed channels in playlist order; the k-th repeat of the same key gets a `#k` suffix.
 */
class StableKeyAssigner {

    private val seen = HashMap<String, Int>()

    fun keyFor(xtreamStreamId: Long?, tvgId: String?, name: String, group: String?): String {
        val base = when {
            xtreamStreamId != null -> "x:$xtreamStreamId"
            !tvgId.isNullOrBlank() -> "t:${tvgId.trim()}|${norm(name)}"
            else -> "n:${norm(name)}|${group?.let(::norm).orEmpty()}"
        }
        val occurrence = (seen[base] ?: 0) + 1
        seen[base] = occurrence
        return if (occurrence == 1) base else "$base#$occurrence"
    }

    private fun norm(s: String) = NameNormalizer.normalize(s).ifEmpty { s.trim().lowercase() }
}
