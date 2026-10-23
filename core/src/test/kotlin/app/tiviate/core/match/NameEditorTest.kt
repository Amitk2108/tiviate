package app.tiviate.core.match

import org.junit.Assert.assertEquals
import org.junit.Test

class NameEditorTest {

    @Test
    fun parseSplitsOnCommasAndPutsLongestFirst() {
        assertEquals(listOf("IN |", "UK:"), NameEditor.parse(" UK: , IN | ,, "))
        assertEquals(emptyList<String>(), NameEditor.parse(null))
        assertEquals(emptyList<String>(), NameEditor.parse("  ,  "))
    }

    @Test
    fun cleanRemovesOnePrefixAndOneSuffixIgnoringCase() {
        val prefixes = NameEditor.parse("UK:, IN |")
        val suffixes = NameEditor.parse("| FHD, HEVC")
        assertEquals("BBC One", NameEditor.clean("UK: BBC One | FHD", prefixes, suffixes))
        assertEquals("Star Plus", NameEditor.clean("in | Star Plus hevc", prefixes, suffixes))
        assertEquals("CNN", NameEditor.clean("CNN", prefixes, suffixes))
    }

    @Test
    fun longerPrefixWinsOverItsShorterStart() {
        val prefixes = NameEditor.parse("UK, UK:")
        assertEquals("Sky News", NameEditor.clean("UK: Sky News", prefixes, emptyList()))
    }

    @Test
    fun neverLeavesAChannelWithoutAName() {
        assertEquals("HEVC", NameEditor.clean("HEVC", emptyList(), listOf("HEVC")))
        assertEquals("UK:", NameEditor.clean(" UK: ", listOf("UK:"), emptyList()))
    }

    @Test
    fun noListsKeepsTheNameUntouched() {
        assertEquals("  Odd  Name ", NameEditor.clean("  Odd  Name ", emptyList(), emptyList()))
    }
}
