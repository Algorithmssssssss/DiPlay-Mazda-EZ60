package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import org.junit.Assert.assertEquals
import org.junit.Test

class MazdaHudTest {
    @Test fun turnsUseTheCarsHudIcons() {
        // AMap icon codes from DiPlay's guidance -> the turnIds the car's navigation apps send.
        assertEquals(19, MazdaHud.turnId(2, 0))
        assertEquals(20, MazdaHud.turnId(3, 0))
        assertEquals(18, MazdaHud.turnId(4, 0))
        assertEquals(17, MazdaHud.turnId(5, 0))
        assertEquals(21, MazdaHud.turnId(6, 0))
        assertEquals(22, MazdaHud.turnId(7, 0))
        assertEquals(23, MazdaHud.turnId(8, 0))
        assertEquals(24, MazdaHud.turnId(19, 0))
        assertEquals(16, MazdaHud.turnId(9, 0))
        assertEquals(3, MazdaHud.turnId(15, 0))
        assertEquals(0, MazdaHud.turnId(0, 0))
    }

    @Test fun roundaboutsCarryTheExitNumberInTheirDirection() {
        // Counter-clockwise (right-hand traffic): exits 1-10 are 42-51.
        assertEquals(42, MazdaHud.turnId(11, 1))
        assertEquals(43, MazdaHud.turnId(11, 2))
        assertEquals(51, MazdaHud.turnId(11, 10))
        assertEquals(53, MazdaHud.turnId(11, 0))
        assertEquals(53, MazdaHud.turnId(11, 12))
        assertEquals(63, MazdaHud.turnId(12, 2))
        // Clockwise (left-hand traffic): exits 1-10 are 74-83.
        assertEquals(74, MazdaHud.turnId(17, 1))
        assertEquals(83, MazdaHud.turnId(17, 10))
        assertEquals(70, MazdaHud.turnId(17, 0))
        assertEquals(68, MazdaHud.turnId(18, 0))
    }

    @Test fun progressIsTheDrivenShareOfTheLongestRemainingDistance() {
        assertEquals(0, MazdaHud.progressPercent(0, 0))
        assertEquals(0, MazdaHud.progressPercent(12_400, 12_400))
        assertEquals(25, MazdaHud.progressPercent(12_400, 9_300))
        assertEquals(100, MazdaHud.progressPercent(12_400, 0))
        assertEquals(0, MazdaHud.progressPercent(12_400, 13_000))
    }

    @Test fun aFrameCarriesDistanceRoadAndWhatIsLeft() {
        val guidance = ClusterTurnGuidance(icon = 2, roundaboutExit = 0, distanceMeters = 350, road = "Street 214",
            remainingSeconds = 960, remainingMeters = 12_400)
        assertEquals(MazdaHud.Frame(19, 350, "", "Street 214", 12_400, 960), MazdaHud.frame(guidance))
        assertEquals(MazdaHud.Frame(16, 0, "", "", 0, 0),
            MazdaHud.frame(ClusterTurnGuidance(icon = 9, roundaboutExit = 0, distanceMeters = -5, road = "")))
    }

    @Test fun onlyNavigationCallsAreAllowed() {
        // Status, turn, road, remaining route, route progress, and taking and giving back navigation focus.
        assertEquals(setOf(22, 24, 26, 27, 50, 63, 64), MazdaHud.ALLOWED)
    }

    @Test fun mapViewsAskForSmallerOrLargerStreamsThanTheHudArea() {
        fun size(view: MazdaHudSettings.MapView) = com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.config(
            MazdaHudScreen.AREA_WIDTH, MazdaHudScreen.AREA_HEIGHT, scalePercent = view.scalePercent,
        ).let { it.widthPixels to it.heightPixels }
        // The car's own 178x178 box (EZNav, 2026-10-17); the map views ask for less or more.
        assertEquals(136 to 136, size(MazdaHudSettings.MapView.NEAR))
        assertEquals(176 to 176, size(MazdaHudSettings.MapView.NORMAL))
        assertEquals(264 to 264, size(MazdaHudSettings.MapView.FAR))
    }
}
