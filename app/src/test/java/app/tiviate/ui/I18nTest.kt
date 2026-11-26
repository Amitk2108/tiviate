package app.tiviate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class I18nTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun backToEnglish() = I18n.use(context, "en")

    /** A device in Portuguese gets European Portuguese, except in Brazil (pull request #11). */
    @Test
    fun portugueseOutsideBrazilIsEuropeanPortuguese() {
        assertEquals("pt-PT", I18n.tagFor(Locale("pt", "PT")))
        assertEquals("pt-PT", I18n.tagFor(Locale("pt")))
        assertEquals("pt-BR", I18n.tagFor(Locale("pt", "BR")))
        assertEquals("de", I18n.tagFor(Locale.GERMANY))
    }

    @Test
    fun europeanPortugueseIsOfferedAndLoads() {
        assertTrue(I18n.LANGUAGES.any { it.first == "pt-PT" })
        I18n.use(context, "pt-PT")
        assertEquals("Prima Voltar novamente para sair", t("Press Back again to exit"))
    }
}
