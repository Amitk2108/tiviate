package app.tiviate.core.match

import app.tiviate.core.xmltv.XmltvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameNormalizerTest {

    @Test
    fun stripsCountryPrefixAndQualityTags() {
        assertEquals("cnn", NameNormalizer.normalize("US: CNN HD"))
        assertEquals("bbc one", NameNormalizer.normalize("UK | BBC One FHD"))
        assertEquals("sky sports 1", NameNormalizer.normalize("Sky Sports 1 (4K) HEVC"))
        assertEquals("espn", NameNormalizer.normalize("ESPN 50fps"))
        assertEquals("ca va", NameNormalizer.normalize("  Ça   Va! "))
        assertEquals("colors", NameNormalizer.normalize("Colors HD"))
        assertEquals("star sports 1 hindi", NameNormalizer.normalize("Star Sports 1 Hindi SD"))
    }

    @Test
    fun keepsShortNamesThatLookLikePrefixes() {
        assertEquals("abc", NameNormalizer.normalize("ABC"))
        assertEquals("tv 5", NameNormalizer.normalize("TV-5"))
    }
}

class EpgMatcherTest {

    private fun ch(id: String, vararg names: String) = XmltvChannel(id, names.toList(), null)

    @Test
    fun matchPrefersTvgIdCaseInsensitive() {
        val m = EpgMatcher()
        m.add(1, 0, ch("CNN.us", "Something Else"))
        m.add(1, 0, ch("other", "CNN"))
        assertEquals(EpgRef(1, "CNN.us"), m.match("cnn.US", listOf("CNN")))
    }

    @Test
    fun matchFallsBackToNormalizedDisplayName() {
        val m = EpgMatcher()
        m.add(2, 0, ch("1346", "Sonic Marathi"))
        assertEquals(EpgRef(2, "1346"), m.match("unknown-id", listOf("IN: Sonic Marathi HD")))
        assertEquals(EpgRef(2, "1346"), m.match(null, listOf("sonic marathi")))
    }

    @Test
    fun lowerPriorityNumberWins() {
        val m = EpgMatcher()
        m.add(7, 1, ch("x", "News"))
        m.add(3, 0, ch("x", "News"))
        assertEquals(EpgRef(3, "x"), m.match("x", emptyList()))
        assertEquals(EpgRef(3, "x"), m.match(null, listOf("News")))
    }

    @Test
    fun noMatchReturnsNull() {
        val m = EpgMatcher()
        m.add(1, 0, ch("a", "Alpha"))
        assertNull(m.match("zzz", listOf("Beta")))
        assertNull(m.match(null, emptyList()))
    }
}

class StableKeyAssignerTest {

    @Test
    fun stableKeyIgnoresUrlAndDisambiguatesDuplicates() {
        val a = StableKeyAssigner()
        assertEquals("x:12", a.keyFor(12, "ignored", "Name", "G"))
        val first = a.keyFor(null, "cnn.us", "CNN HD", "News")
        val second = a.keyFor(null, "cnn.us", "CNN HD", "News")
        assertEquals("t:cnn.us|cnn", first)
        assertEquals("t:cnn.us|cnn#2", second)
        assertEquals("n:bbc one|uk", a.keyFor(null, null, "BBC One", "UK"))
        assertNotEquals(a.keyFor(null, "same", "Alpha", null), a.keyFor(null, "same", "Beta", null))

        // Deterministic across runs fed the same sequence (resync)
        val b = StableKeyAssigner()
        b.keyFor(12, "ignored", "Name", "G")
        assertEquals(first, b.keyFor(null, "cnn.us", "CNN HD", "News"))
        assertEquals(second, b.keyFor(null, "cnn.us", "CNN HD", "News"))
    }

    @Test
    fun repeatedXtreamIdsAreDisambiguated() {
        val a = StableKeyAssigner()
        assertEquals("x:5", a.keyFor(5, null, "A", null))
        assertEquals("x:5#2", a.keyFor(5, null, "A", null))
    }
}
