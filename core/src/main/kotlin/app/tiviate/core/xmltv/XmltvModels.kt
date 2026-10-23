package app.tiviate.core.xmltv

data class XmltvChannel(val id: String, val displayNames: List<String>, val iconUrl: String?)

data class XmltvProgramme(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
    val iconUrl: String?,
    val episode: String?,
)

/** Receives parse results. [wantsChannel] lets the consumer skip programmes for channels it doesn't have. */
interface XmltvHandler {
    fun onChannel(c: XmltvChannel)
    fun wantsChannel(channelId: String): Boolean
    fun onProgramme(p: XmltvProgramme)
}
