package app.tiviate.core.xmltv

import java.io.InputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Streaming XMLTV parser. Programmes are emitted only for wanted channels and only when they
 * overlap [windowStartMs, windowEndMs). A programme without `stop` ends at the next programme's
 * start on the same channel, or one hour after its own start if none follows.
 */
class XmltvParser(
    private val newPullParser: () -> XmlPullParser,
    private val windowStartMs: Long,
    private val windowEndMs: Long,
) {

    fun parse(input: InputStream, handler: XmltvHandler) {
        val p = newPullParser()
        runCatching { p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        runCatching { p.setFeature(RELAXED, true) }
        p.setInput(input, null)

        val openEnded = HashMap<String, XmltvProgramme>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> handler.onChannel(readChannel(p))
                    "programme" -> readProgramme(p, handler, openEnded)
                }
            }
            event = p.next()
        }
        for (prog in openEnded.values) emit(prog.copy(stopMs = prog.startMs + ONE_HOUR), handler)
    }

    private fun readChannel(p: XmlPullParser): XmltvChannel {
        val id = p.getAttributeValue(null, "id").orEmpty().trim()
        val names = ArrayList<String>(2)
        var icon: String? = null
        val depth = p.depth
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.depth == depth)) break
            if (e != XmlPullParser.START_TAG) continue
            when (p.name) {
                "display-name" -> readText(p).takeIf { it.isNotEmpty() }?.let { names += it }
                "icon" -> if (icon == null) icon = p.getAttributeValue(null, "src")?.trim()?.ifEmpty { null }
            }
        }
        return XmltvChannel(id, names, icon)
    }

    private fun readProgramme(p: XmlPullParser, handler: XmltvHandler, openEnded: MutableMap<String, XmltvProgramme>) {
        val channel = p.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = p.getAttributeValue(null, "start")?.let(XmltvTime::parse)
        val stop = p.getAttributeValue(null, "stop")?.let(XmltvTime::parse)
        if (channel.isEmpty() || start == null || !handler.wantsChannel(channel)) {
            skipSubtree(p)
            return
        }

        var title: String? = null
        var subtitle: String? = null
        var desc: String? = null
        var category: String? = null
        var icon: String? = null
        var episode: String? = null
        var episodeIsOnscreen = false
        val depth = p.depth
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.depth == depth)) break
            if (e != XmlPullParser.START_TAG) continue
            when (p.name) {
                "title" -> if (title == null) title = readText(p)
                "sub-title" -> if (subtitle == null) subtitle = readText(p).ifEmpty { null }
                "desc" -> if (desc == null) desc = readText(p).ifEmpty { null }
                "category" -> if (category == null) category = readText(p).ifEmpty { null }
                "icon" -> if (icon == null) icon = p.getAttributeValue(null, "src")?.trim()?.ifEmpty { null }
                "episode-num" -> {
                    val onscreen = p.getAttributeValue(null, "system") == "onscreen"
                    val text = readText(p).ifEmpty { null }
                    if (text != null && (episode == null || (onscreen && !episodeIsOnscreen))) {
                        episode = text
                        episodeIsOnscreen = onscreen
                    }
                }
            }
        }

        val previous = openEnded.remove(channel)
        if (previous != null) {
            val end = if (start > previous.startMs) start else previous.startMs + ONE_HOUR
            emit(previous.copy(stopMs = end), handler)
        }
        val prog = XmltvProgramme(
            channelId = channel,
            startMs = start,
            stopMs = stop ?: -1L,
            title = title.orEmpty(),
            subtitle = subtitle,
            description = desc,
            category = category,
            iconUrl = icon,
            episode = episode,
        )
        if (stop == null || stop <= start) openEnded[channel] = prog else emit(prog, handler)
    }

    private fun emit(prog: XmltvProgramme, handler: XmltvHandler) {
        if (prog.stopMs > windowStartMs && prog.startMs < windowEndMs) handler.onProgramme(prog)
    }

    /** Reads all text inside the current element, including text of nested elements. */
    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = p.depth
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.depth == depth)) break
            if (e == XmlPullParser.TEXT) sb.append(p.text)
        }
        return sb.toString().trim()
    }

    private fun skipSubtree(p: XmlPullParser) {
        val depth = p.depth
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.depth == depth)) return
        }
    }

    private companion object {
        const val ONE_HOUR = 3_600_000L
        const val RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
    }
}
