package app.tiviate.core.xmltv

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class XmltvParserTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private class Collector(private val wanted: Set<String>) : XmltvHandler {
        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<XmltvProgramme>()
        override fun onChannel(c: XmltvChannel) { channels += c }
        override fun wantsChannel(channelId: String) = channelId in wanted
        override fun onProgramme(p: XmltvProgramme) { programmes += p }
    }

    private fun parse(wanted: Set<String> = setOf("news.one", "sport.two")): Collector {
        val parser = XmltvParser(
            newPullParser = { KXmlParser() },
            windowStartMs = ms("2026-10-22T00:00:00Z"),
            windowEndMs = ms("2026-10-30T00:00:00Z"),
        )
        val collector = Collector(wanted)
        javaClass.getResourceAsStream("/xmltv/small.xml")!!.use { parser.parse(it, collector) }
        return collector
    }

    @Test
    fun parsesChannelsWithMultipleDisplayNamesAndIcon() {
        val c = parse().channels
        assertEquals(listOf("news.one", "sport.two", "ignored.three"), c.map { it.id })
        assertEquals(listOf("News One HD", "News 1"), c[0].displayNames)
        assertEquals("http://img/news1.png", c[0].iconUrl)
        assertEquals(null, c[1].iconUrl)
    }

    @Test
    fun programmeFieldsAndProgrammeIcon() {
        val p = parse().programmes.first { it.title == "Morning & News" }
        assertEquals("news.one", p.channelId)
        assertEquals(ms("2026-10-23T10:00:00Z"), p.startMs)
        assertEquals(ms("2026-10-23T11:00:00Z"), p.stopMs)
        assertEquals("Part 1", p.subtitle)
        assertEquals("Headlines.", p.description)
        assertEquals("News", p.category)
        assertEquals("S1E2", p.episode)
        assertEquals("https://img/prog1.jpg?w=740&fmt=jpg", p.iconUrl)
    }

    @Test
    fun missingStopUsesNextStartForSameChannel() {
        val p = parse().programmes.first { it.title == "Midday" }
        assertEquals(ms("2026-10-23T12:00:00Z"), p.stopMs)
    }

    @Test
    fun missingStopOnLastProgrammeDefaultsToOneHour() {
        val p = parse().programmes.first { it.title == "Last Without Stop" }
        assertEquals(ms("2026-10-23T11:00:00Z"), p.stopMs)
    }

    @Test
    fun skipsProgrammesOutsideWindow() {
        assertFalse(parse().programmes.any { it.title == "Too Old" })
    }

    @Test
    fun skipsUnwantedChannels() {
        val programmes = parse().programmes
        assertFalse(programmes.any { it.channelId == "ignored.three" })
        assertEquals(4, programmes.size)
    }

    @Test
    fun wantingNothingStillReportsChannels() {
        val c = parse(wanted = emptySet())
        assertTrue(c.programmes.isEmpty())
        assertEquals(3, c.channels.size)
    }
}
