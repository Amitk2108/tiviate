package app.tiviate.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTest {

    @Test
    fun everyCategoryIsRememberedAndRestored() {
        val all = listOf(Category.All, Category.Favorites, Category.Recent, Category.Group("Hindi News"), Category.Custom(7, "Mine: kids"))
        for (c in all) assertEquals(c, Category.restore(Category.remember(c)))
    }

    @Test
    fun oldSavedGroupNamesStillOpenTheirGroup() {
        assertEquals(Category.Group("Sports"), Category.restore("Sports"))
        assertEquals(Category.All, Category.restore(null))
    }
}
