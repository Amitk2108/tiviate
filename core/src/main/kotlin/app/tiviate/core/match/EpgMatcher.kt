package app.tiviate.core.match

import app.tiviate.core.xmltv.XmltvChannel

data class EpgRef(val sourceId: Long, val xmltvId: String) {
    /** Compact form stored on channels and programmes: "sourceId:xmltvId". */
    fun encode(): String = "$sourceId:$xmltvId"
}

/**
 * Matches playlist channels to XMLTV channels: exact `tvg-id` first (case-insensitive), then the
 * normalized display name. When several sources know a channel, the lowest priority number wins;
 * on equal priority the first one added wins.
 */
class EpgMatcher {

    private class Candidate(val priority: Int, val ref: EpgRef)

    private val byId = HashMap<String, Candidate>()
    private val byName = HashMap<String, Candidate>()

    fun add(sourceId: Long, priority: Int, channel: XmltvChannel) {
        val candidate = Candidate(priority, EpgRef(sourceId, channel.id))
        offer(byId, channel.id.lowercase(), candidate)
        for (name in channel.displayNames) {
            val key = NameNormalizer.normalize(name)
            if (key.isNotEmpty()) offer(byName, key, candidate)
        }
    }

    fun match(tvgId: String?, names: List<String>): EpgRef? {
        if (!tvgId.isNullOrBlank()) byId[tvgId.trim().lowercase()]?.let { return it.ref }
        for (name in names) {
            val key = NameNormalizer.normalize(name)
            if (key.isNotEmpty()) byName[key]?.let { return it.ref }
        }
        return null
    }

    private fun offer(map: HashMap<String, Candidate>, key: String, candidate: Candidate) {
        val existing = map[key]
        if (existing == null || candidate.priority < existing.priority) map[key] = candidate
    }
}
