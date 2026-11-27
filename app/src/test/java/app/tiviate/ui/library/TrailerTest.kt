package app.tiviate.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerTest {
    @Test
    fun aYouTubeIdOpensTheVideo() = assertEquals("https://www.youtube.com/watch?v=eRsGyueVLvQ", Trailer.uri("eRsGyueVLvQ", "Sintel"))

    @Test
    fun aLinkIsKept() = assertEquals("https://youtu.be/eRsGyueVLvQ", Trailer.uri(" https://youtu.be/eRsGyueVLvQ ", "Sintel"))

    @Test
    fun withoutAUsableTrailerItSearchesForOne() {
        assertEquals("https://www.youtube.com/results?search_query=Sintel+trailer", Trailer.uri(null, "Sintel"))
        assertEquals("https://www.youtube.com/results?search_query=Big+Buck+Bunny+trailer", Trailer.uri("abc", "Big Buck Bunny"))
    }
}
