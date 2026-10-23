package app.tiviate.core.match

import java.text.Normalizer

/** Reduces channel names to a comparable form: "UK | BBC One FHD" → "bbc one". */
object NameNormalizer {

    private val COUNTRY_PREFIX = Regex("""^\s*[A-Za-z]{2,3}\s*(?:[:|]\s*|\s-\s+)""")
    private val MARKS = Regex("""\p{Mn}+""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")
    private val QUALITY = Regex(
        """\b(?:hd|fhd|uhd|sd|hq|lq|4k|8k|hevc|h264|h265|x264|x265|hdr|hdr10|\d{2,3}fps|\d{3,4}[pi])\b""",
    )
    private val SPACES = Regex("""\s+""")

    fun normalize(name: String): String {
        val withoutPrefix = COUNTRY_PREFIX.replace(name, "")
        val ascii = MARKS.replace(Normalizer.normalize(withoutPrefix, Normalizer.Form.NFD), "")
        val words = NON_ALNUM.replace(ascii.lowercase(), " ")
        val stripped = QUALITY.replace(words, " ")
        return SPACES.replace(stripped, " ").trim().ifEmpty { SPACES.replace(words, " ").trim() }
    }
}
