package app.tiviate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeChannelsTest {

    @Test
    fun aLinkLeadsBackToItsChannel() {
        val key = "tvg:bbc.one&uk=1 / HD"
        assertEquals(7L to key, HomeChannels.parse(HomeChannels.link(7, key)))
    }

    @Test
    fun otherLinksAreIgnored() {
        assertNull(HomeChannels.parse("tiviate://play")) // the row's own link just opens the app
        assertNull(HomeChannels.parse("tiviate://play?p=x&k=a"))
        assertNull(HomeChannels.parse("tiviate://play?p=3&k="))
        assertNull(HomeChannels.parse("https://play?p=3&k=a"))
        assertNull(HomeChannels.parse("not a link %%"))
        assertNull(HomeChannels.parse(null))
    }
}
