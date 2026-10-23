package app.tiviate.core.match

/**
 * The names editor: removes the provider's prefixes ("UK:", "IN |") and suffixes ("| FHD", "HEVC")
 * from channel names, case-insensitively. Lists are typed comma-separated.
 */
object NameEditor {

    fun parse(list: String?): List<String> =
        list.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.sortedByDescending { it.length }

    fun clean(name: String, prefixes: List<String>, suffixes: List<String>): String {
        if (prefixes.isEmpty() && suffixes.isEmpty()) return name
        var out = name.trim()
        prefixes.firstOrNull { out.startsWith(it, ignoreCase = true) }?.let { out = out.substring(it.length).trimStart() }
        suffixes.firstOrNull { out.endsWith(it, ignoreCase = true) }?.let { out = out.substring(0, out.length - it.length).trimEnd() }
        return out.ifEmpty { name.trim() } // never leave a channel without a name
    }
}
