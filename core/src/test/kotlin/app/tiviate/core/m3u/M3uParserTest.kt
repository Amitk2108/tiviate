package app.tiviate.core.m3u

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private fun parse(text: String): Triple<List<M3uHeader>, List<M3uEntry>, M3uStats> {
        val headers = mutableListOf<M3uHeader>()
        val entries = mutableListOf<M3uEntry>()
        val stats = M3uParser.parse(Buffer().writeUtf8(text), onHeader = { headers += it }, onEntry = { entries += it })
        return Triple(headers, entries, stats)
    }

    @Test
    fun parsesBasicAttributes() {
        val (_, entries, stats) = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="cnn.us" tvg-name="CNN" tvg-logo="http://logo/cnn.png" group-title="News" tvg-chno="7",CNN
            http://host/live/1.ts
            """.trimIndent(),
        )
        val e = entries.single()
        assertEquals("CNN", e.name)
        assertEquals("http://host/live/1.ts", e.url)
        assertEquals("cnn.us", e.tvgId)
        assertEquals("CNN", e.tvgName)
        assertEquals("http://logo/cnn.png", e.tvgLogo)
        assertEquals("News", e.groupTitle)
        assertEquals(7, e.tvgChno)
        assertEquals(M3uStats(entries = 1, skipped = 0), stats)
    }

    @Test
    fun titleKeepsCommasAfterFirstUnquotedComma() {
        val (_, entries, _) = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="A, B" group-title="News",CNN, International
            http://h/1
            """.trimIndent(),
        )
        assertEquals("CNN, International", entries.single().name)
        assertEquals("A, B", entries.single().tvgName)
        assertEquals("News", entries.single().groupTitle)
    }

    @Test
    fun singleQuotedAndUnquotedAttributes() {
        val (_, entries, _) = parse(
            """
            #EXTM3U
            #EXTINF:0 tvg-id='bbc1' group-title=UK tvg-logo="",BBC One
            http://h/2
            """.trimIndent(),
        )
        val e = entries.single()
        assertEquals("bbc1", e.tvgId)
        assertEquals("UK", e.groupTitle)
        assertNull("blank attribute values are treated as absent", e.tvgLogo)
        assertEquals("BBC One", e.name)
    }

    @Test
    fun handlesBomCrlfAndMissingHeader() {
        val text = "﻿#EXTINF:-1 group-title=\"G\",Chan A\r\nhttp://h/a\r\n\r\n#EXTINF:-1,Chan B\r\nhttp://h/b\r\n"
        val (headers, entries, _) = parse(text)
        assertEquals(listOf("Chan A", "Chan B"), entries.map { it.name })
        assertEquals(listOf("http://h/a", "http://h/b"), entries.map { it.url })
        assertEquals(emptyList<M3uHeader>(), headers)
    }

    @Test
    fun headerEpgUrls() {
        val (headers, _, _) = parse(
            """
            #EXTM3U url-tvg="http://a/epg.xml.gz,http://b/epg.xml" x-tvg-url="http://c/x.xml"
            #EXTINF:-1,X
            http://h/x
            """.trimIndent(),
        )
        assertEquals(listOf("http://a/epg.xml.gz", "http://b/epg.xml", "http://c/x.xml"), headers.single().epgUrls)
    }

    @Test
    fun extgrpAppliesWhenNoGroupTitle() {
        val (_, entries, _) = parse(
            """
            #EXTM3U
            #EXTINF:-1,One
            #EXTGRP:Sports
            http://h/1
            #EXTINF:-1 group-title="Movies",Two
            #EXTGRP:Ignored
            http://h/2
            """.trimIndent(),
        )
        assertEquals(listOf("Sports", "Movies"), entries.map { it.groupTitle })
    }

    @Test
    fun extvlcoptUserAgentAndReferrer() {
        val (_, entries, _) = parse(
            """
            #EXTM3U
            #EXTINF:-1,Secure
            #EXTVLCOPT:http-user-agent=MyAgent/1.0
            #EXTVLCOPT:http-referrer=https://ref.example/
            https://h/s.m3u8
            """.trimIndent(),
        )
        assertEquals("MyAgent/1.0", entries.single().userAgent)
        assertEquals("https://ref.example/", entries.single().referrer)
    }

    @Test
    fun catchupAttributesStored() {
        val (_, entries, _) = parse(
            """
            #EXTM3U
            #EXTINF:-1 catchup="default" catchup-source="http://h/{utc}" catchup-days="3",Arch
            http://h/a
            """.trimIndent(),
        )
        val e = entries.single()
        assertEquals("default", e.catchup)
        assertEquals("http://h/{utc}", e.catchupSource)
        assertEquals(3, e.catchupDays)
    }

    @Test
    fun skipsEntryWithoutUrlAndCounts() {
        val (_, entries, stats) = parse(
            """
            #EXTM3U
            #EXTINF:-1,Broken
            #EXTINF:-1,Good
            http://h/good
            """.trimIndent(),
        )
        assertEquals(listOf("Good"), entries.map { it.name })
        assertEquals(1, stats.skipped)
        assertEquals(1, stats.entries)
    }

    @Test
    fun urlLineWithoutExtinfUsesUrlAsName() {
        val (_, entries, _) = parse("#EXTM3U\nhttp://h/plain/stream.ts\n")
        assertEquals("http://h/plain/stream.ts", entries.single().url)
        assertEquals("stream.ts", entries.single().name)
    }

    @Test
    fun trailingEntryWithoutUrlIsSkipped() {
        val (_, entries, stats) = parse("#EXTM3U\n#EXTINF:-1,A\nhttp://h/a\n#EXTINF:-1,Dangling\n")
        assertEquals(1, entries.size)
        assertEquals(1, stats.skipped)
    }
}
