package app.tiviate.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import org.json.JSONObject

/**
 * Tiviate's menus in the viewer's language. Texts are looked up by their English wording in
 * `assets/i18n/<language>.json`; anything without a translation shows in English.
 */
object I18n {
    /** Languages offered in Settings → General → Language (tag → own name). */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "ar" to "العربية", "bg" to "Български", "bn" to "বাংলা", "cs" to "Čeština", "da" to "Dansk", "de" to "Deutsch",
        "el" to "Ελληνικά", "en" to "English", "es" to "Español", "fi" to "Suomi", "fr" to "Français", "gu" to "ગુજરાતી",
        "he" to "עברית", "hi" to "हिन्दी", "hr" to "Hrvatski", "hu" to "Magyar", "it" to "Italiano", "ka" to "ქართული",
        "kn" to "ಕನ್ನಡ", "lt" to "Lietuvių", "lv" to "Latviešu", "ml" to "മലയാളം", "mr" to "मराठी", "nb" to "Norsk",
        "nl" to "Nederlands", "pa" to "ਪੰਜਾਬੀ", "pl" to "Polski", "pt-BR" to "Português (Brasil)", "ro" to "Română",
        "ru" to "Русский", "sk" to "Slovenčina", "sl" to "Slovenščina", "sr" to "Српски", "sv" to "Svenska",
        "ta" to "தமிழ்", "te" to "తెలుగు", "tr" to "Türkçe", "uk" to "Українська", "vi" to "Tiếng Việt", "zh" to "中文",
    )

    private var table by mutableStateOf<Map<String, String>>(emptyMap())
    var current by mutableStateOf("en")
        private set

    /** Loads [tag], or the TV's language when null. */
    fun use(context: Context, tag: String?) {
        val wanted = tag ?: Locale.getDefault().let { if (it.language == "pt" && it.country == "BR") "pt-BR" else it.language }
        val resolved = when (wanted) { "iw" -> "he"; "in" -> "id"; else -> wanted }
        if (resolved == current && table.isNotEmpty()) return
        current = resolved
        table = if (resolved == "en") emptyMap() else load(context, resolved)
    }

    private fun load(context: Context, tag: String): Map<String, String> = runCatching {
        val json = context.assets.open("i18n/$tag.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { obj.getString(it) }
    }.getOrDefault(emptyMap())

    fun t(english: String): String = table[english] ?: english
}

/** Translates an English UI text (see [I18n]). */
fun t(english: String): String = I18n.t(english)

/** Translates a text with %s / %d placeholders, then fills them in. */
fun tf(english: String, vararg args: Any?): String = runCatching { I18n.t(english).format(*args) }.getOrDefault(english.format(*args))
