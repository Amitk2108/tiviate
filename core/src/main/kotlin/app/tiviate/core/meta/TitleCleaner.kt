package app.tiviate.core.meta

import java.text.Normalizer
import java.util.Calendar

/**
 * IPTV names carry extras ("EN | Carrie (2013) [4K]"); online services want the plain title ("Carrie") and the
 * year (2013).
 */
object TitleCleaner {
    /** Leading language/country tags: "EN | ", "DE: ", "[FR] ", "(US) ". */
    private val PREFIX = Regex("""^\s*(?:(?:\[[^\]]{1,12}]|\([A-Za-z]{2,3}\)|[A-Za-z]{2,3}\s*[|:\-–])\s*)+""")
    private val BRACKETS = Regex("""\[[^\]]*]""")
    private val QUALITY = Regex("""\b(?:4K|UHD|FHD|HD|SD|HDR|HEVC|H\.?26[45]|x26[45]|1080p|720p|2160p|MULTI|SUB|DUB|VOSTFR)\b""", RegexOption.IGNORE_CASE)
    private val YEAR_IN_PARENS = Regex("""\((\d{4})\)""")
    private val TRAILING_YEAR = Regex("""\s(\d{4})\s*$""")
    private val MARKS = Regex("""\p{M}+""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    fun year(name: String): Int? {
        val stripped = strip(name)
        val y = (YEAR_IN_PARENS.find(stripped) ?: TRAILING_YEAR.find(stripped))?.groupValues?.get(1)?.toInt() ?: return null
        return y.takeIf { it in 1900..thisYear() + 1 }
    }

    fun clean(name: String): String {
        var s = strip(name)
        val y = year(name)
        if (y != null) {
            s = s.replace(YEAR_IN_PARENS) { if (it.groupValues[1].toInt() == y) " " else it.value }
            s = TRAILING_YEAR.find(s)?.takeIf { it.groupValues[1].toInt() == y }?.let { s.removeRange(it.range) } ?: s
        }
        s = s.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '|', ':', ' ')
        return s.ifEmpty { name.trim() } // a title that is only a year ("1917")
    }

    /** Same title, ignoring case, accents, punctuation and the extras. */
    fun same(a: String, b: String): Boolean = key(clean(a)) == key(clean(b))

    private fun strip(name: String) = name.replace(PREFIX, "").replace(BRACKETS, " ").replace(QUALITY, " ").replace(Regex("""\s+"""), " ").trim()

    private fun key(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(NON_ALNUM, "")

    private fun thisYear() = Calendar.getInstance().get(Calendar.YEAR)
}
