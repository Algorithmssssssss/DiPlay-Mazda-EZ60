package com.shilapi.xcertplay

import com.shilapi.xcertplay.MazdaCockpitGeometry.Panel
import com.shilapi.xcertplay.MazdaCockpitGeometry.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MazdaCockpitGeometryTest {
    @Test fun windowsOnEachHalfOfTheCockpit() {
        // Measured on the EZ-60: the swipe moves a 2560-wide window between x=0 and x=2560 of 5120.
        assertEquals(Side.DRIVER, MazdaCockpitGeometry.side(windowLeft = 0, windowWidth = 2560, displayWidth = 5120))
        assertEquals(Side.PASSENGER, MazdaCockpitGeometry.side(windowLeft = 2560, windowWidth = 2560, displayWidth = 5120))
        assertEquals(Side.DRIVER, MazdaCockpitGeometry.side(windowLeft = 0, windowWidth = 5120, displayWidth = 5120))
        // Not a cockpit: a single 2560x1440 screen is always the driver's.
        assertEquals(Side.DRIVER, MazdaCockpitGeometry.side(windowLeft = 0, windowWidth = 2560, displayWidth = 2560))
    }

    @Test fun driverPanelMatchesTheCarsOwnApps() {
        assertEquals(Panel(64, 192, 2560, 1272), MazdaCockpitGeometry.panel(Side.DRIVER, narrow = false))
        assertEquals(Panel(832, 192, 2560, 1272), MazdaCockpitGeometry.panel(Side.DRIVER, narrow = true))
    }

    @Test fun passengerPanelMirrorsTheDriverPanelAtTheSameSizes() {
        val wide = MazdaCockpitGeometry.panel(Side.PASSENGER, narrow = false)
        val narrow = MazdaCockpitGeometry.panel(Side.PASSENGER, narrow = true)
        assertEquals(Panel(0, 192, 2496, 1272), wide)
        assertEquals(Panel(0, 192, 1728, 1272), narrow)
        // CarPlay's two view areas (2496 and 1728 wide, 1080 high) fit either half unchanged.
        assertEquals(2496 to 1080, wide.width to wide.height)
        assertEquals(1728 to 1080, narrow.width to narrow.height)
    }

    @Test fun theHudCardSitsInTheProjectedPartOfTheHudSurface() {
        // The car's navigation app draws its HUD into this 227x188 corner of the 800x480 display.
        assertTrue(MazdaHud.VISIBLE_LEFT in 0 until MazdaHud.VISIBLE_RIGHT && MazdaHud.VISIBLE_RIGHT <= MazdaHud.WIDTH)
        assertTrue(MazdaHud.VISIBLE_TOP in 0 until MazdaHud.VISIBLE_BOTTOM && MazdaHud.VISIBLE_BOTTOM <= MazdaHud.HEIGHT)
        assertEquals(227 to 188, (MazdaHud.VISIBLE_RIGHT - MazdaHud.VISIBLE_LEFT) to (MazdaHud.VISIBLE_BOTTOM - MazdaHud.VISIBLE_TOP))
    }

    @Test fun eachHalfNarrowsOnlyForItsOwnPanelOrAssistant() {
        assertTrue(MazdaCockpitGeometry.narrow(Side.DRIVER, screenShow = 1, vpaLeft = 0, vpaRight = 0))
        assertTrue(MazdaCockpitGeometry.narrow(Side.DRIVER, screenShow = 0, vpaLeft = 1, vpaRight = 0))
        assertFalse(MazdaCockpitGeometry.narrow(Side.DRIVER, screenShow = 0, vpaLeft = 0, vpaRight = 1))
        assertTrue(MazdaCockpitGeometry.narrow(Side.PASSENGER, screenShow = 0, vpaLeft = 0, vpaRight = 1))
        assertFalse(MazdaCockpitGeometry.narrow(Side.PASSENGER, screenShow = 1, vpaLeft = 1, vpaRight = 0))
    }
}
