package app.tiviate.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.tiviate.ui.t

/** A film's or series' trailer: the provider's YouTube video, or a YouTube search when it has none that works. */
object Trailer {
    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

    fun uri(trailer: String?, title: String): String {
        val t = trailer?.trim().orEmpty()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            YOUTUBE_ID.matches(t) -> "https://www.youtube.com/watch?v=$t"
            else -> "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode("$title trailer", "UTF-8")
        }
    }

    /** Opens it in the YouTube app (or a browser); says so when the device has neither. */
    fun open(context: Context, trailer: String?, title: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri(trailer, title))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, t("No app on this device can play the trailer."), Toast.LENGTH_LONG).show() }
    }
}
