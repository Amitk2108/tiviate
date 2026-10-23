package app.tiviate.core.xtream

import com.squareup.moshi.JsonReader
import okio.BufferedSource

/**
 * Streaming readers for Xtream Codes `player_api.php` responses. Panels disagree on whether
 * numbers are JSON numbers or strings, so every scalar is read leniently.
 */
object XtreamJson {

    fun account(src: BufferedSource): XtreamAccount {
        val r = JsonReader.of(src).apply { isLenient = true }
        var account = XtreamAccount(null, null, null, null, authorized = false, allowedFormats = emptyList())
        if (r.peek() != JsonReader.Token.BEGIN_OBJECT) return account
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "user_info" && r.peek() == JsonReader.Token.BEGIN_OBJECT) account = readUserInfo(r) else r.skipValue()
        }
        r.endObject()
        return account
    }

    private fun readUserInfo(r: JsonReader): XtreamAccount {
        var auth = false
        var status: String? = null
        var exp: Long? = null
        var max: Int? = null
        var active: Int? = null
        var formats = emptyList<String>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "auth" -> auth = scalar(r).let { it == "1" || it.equals("true", ignoreCase = true) }
                "status" -> status = scalar(r)
                "exp_date" -> exp = scalar(r)?.toLongValue()?.takeIf { it > 0 }?.times(1_000L)
                "max_connections" -> max = scalar(r)?.toLongValue()?.toInt()
                "active_cons" -> active = scalar(r)?.toLongValue()?.toInt()
                "allowed_output_formats" -> formats = stringArray(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return XtreamAccount(status, exp, max, active, auth, formats)
    }

    fun categories(src: BufferedSource): List<XtreamCategory> {
        val r = JsonReader.of(src).apply { isLenient = true }
        val out = ArrayList<XtreamCategory>()
        if (r.peek() != JsonReader.Token.BEGIN_ARRAY) return out
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonReader.Token.BEGIN_OBJECT) { r.skipValue(); continue }
            var id: String? = null
            var name: String? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "category_id" -> id = scalar(r)
                    "category_name" -> name = scalar(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (!id.isNullOrBlank() && !name.isNullOrBlank()) out += XtreamCategory(id, name.trim())
        }
        r.endArray()
        return out
    }

    /** Emits each valid live stream; returns how many were emitted. Malformed items are skipped. */
    fun liveStreams(src: BufferedSource, onStream: (XtreamLiveStream) -> Unit): Int {
        val r = JsonReader.of(src).apply { isLenient = true }
        if (r.peek() != JsonReader.Token.BEGIN_ARRAY) return 0
        var count = 0
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonReader.Token.BEGIN_OBJECT) { r.skipValue(); continue }
            var id: Long? = null
            var num: Int? = null
            var name: String? = null
            var icon: String? = null
            var epg: String? = null
            var cat: String? = null
            var archive = false
            var archiveDays: Int? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "stream_id" -> id = scalar(r)?.toLongValue()
                    "num" -> num = scalar(r)?.toLongValue()?.toInt()
                    "name" -> name = scalar(r)?.trim()
                    "stream_icon" -> icon = scalar(r)?.trim()?.ifEmpty { null }
                    "epg_channel_id" -> epg = scalar(r)?.trim()?.ifEmpty { null }
                    "category_id" -> cat = scalar(r)?.ifEmpty { null }
                    "tv_archive" -> archive = scalar(r).let { it == "1" || it.equals("true", ignoreCase = true) }
                    "tv_archive_duration" -> archiveDays = scalar(r)?.toLongValue()?.toInt()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            val streamId = id ?: continue
            onStream(XtreamLiveStream(streamId, num, name?.ifEmpty { null } ?: "Channel $streamId", icon, epg, cat, archive, archiveDays))
            count++
        }
        r.endArray()
        return count
    }

    /** Reads any scalar as a string; objects/arrays are skipped and read as null. */
    private fun scalar(r: JsonReader): String? = when (r.peek()) {
        JsonReader.Token.STRING, JsonReader.Token.NUMBER -> r.nextString()
        JsonReader.Token.BOOLEAN -> r.nextBoolean().toString()
        JsonReader.Token.NULL -> r.nextNull<Unit>().let { null }
        else -> { r.skipValue(); null }
    }

    private fun stringArray(r: JsonReader): List<String> {
        if (r.peek() != JsonReader.Token.BEGIN_ARRAY) { r.skipValue(); return emptyList() }
        val out = ArrayList<String>()
        r.beginArray()
        while (r.hasNext()) scalar(r)?.let { out += it }
        r.endArray()
        return out
    }

    private fun String.toLongValue(): Long? = trim().toLongOrNull() ?: trim().toDoubleOrNull()?.toLong()
}
