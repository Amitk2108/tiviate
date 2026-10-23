package app.tiviate.data.db

/**
 * Runs an `IN (…)` query in chunks. SQLite on API ≤ 30 (Fire OS 7 is API 28) rejects more than
 * 999 bound variables, so lists are split to stay safely below that.
 */
suspend fun <T> chunkedIn(keys: List<String>, size: Int = 900, query: suspend (List<String>) -> List<T>): List<T> {
    if (keys.isEmpty()) return emptyList()
    if (keys.size <= size) return query(keys)
    val out = ArrayList<T>()
    for (chunk in keys.chunked(size)) out += query(chunk)
    return out
}
