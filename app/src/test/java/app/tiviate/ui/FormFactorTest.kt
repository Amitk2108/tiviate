package app.tiviate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormFactorTest {

    @Test
    fun aTvIsATvWhateverItsSize() {
        assertEquals(FormFactor.TV, formFactorOf(isTv = true, smallestWidthDp = 540))
        assertEquals(FormFactor.TV, formFactorOf(isTv = true, smallestWidthDp = 1080))
    }

    @Test
    fun theShorterSideDecidesBetweenPhoneAndTablet() {
        // A phone turned sideways is about 915 dp wide but still 412 dp on its shorter side.
        assertEquals(FormFactor.PHONE, formFactorOf(isTv = false, smallestWidthDp = 412))
        assertEquals(FormFactor.PHONE, formFactorOf(isTv = false, smallestWidthDp = 599))
        assertEquals(FormFactor.TABLET, formFactorOf(isTv = false, smallestWidthDp = 600))
    }

    @Test
    fun phonesAndUprightTabletsGetTheTouchLayout() {
        assertTrue(usesPhoneLayout(FormFactor.PHONE, portrait = true))
        assertTrue(usesPhoneLayout(FormFactor.PHONE, portrait = false))
        assertTrue(usesPhoneLayout(FormFactor.TABLET, portrait = true))
        assertFalse("a tablet held sideways gets the TV layout", usesPhoneLayout(FormFactor.TABLET, portrait = false))
        assertFalse(usesPhoneLayout(FormFactor.TV, portrait = false))
    }
}
