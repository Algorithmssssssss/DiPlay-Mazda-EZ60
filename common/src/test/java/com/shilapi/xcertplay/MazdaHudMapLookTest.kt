package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The HUD map's look: which colour is the background, and the driver's choices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MazdaHudMapLookTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun rgb(red: Int, green: Int, blue: Int) = (0xff shl 24) or (red shl 16) or (green shl 8) or blue

    // A dark CarPlay map: mostly background with compression noise, grey roads, a blue route and a green park.
    @Test fun theBackgroundIsTheMostCommonColourEvenWithNoiseAndRoads() {
        val background = rgb(43, 43, 46)
        val pixels = IntArray(32 * 24) { i ->
            when {
                i % 7 == 0 -> rgb(110, 110, 112)
                i % 11 == 0 -> rgb(38, 120, 245)
                i < 120 -> rgb(36, 62, 44)
                else -> rgb(43 + i % 5 - 2, 43 + i % 3 - 1, 46 + i % 5 - 2)
            }
        }
        val found = MazdaHudMapLook.backgroundColor(pixels)
        assertFalse("Found #%06x".format(found and 0xffffff), MazdaHudMapLook.changed(background, found))
    }

    @Test fun aLightMapsBackgroundIsFoundToo() {
        val pixels = IntArray(32 * 24) { i -> if (i % 4 == 0) rgb(255, 255, 255) else rgb(242, 239, 233) }
        assertEquals(rgb(242, 239, 233), MazdaHudMapLook.backgroundColor(pixels))
    }

    // Google Maps at night beside the river (2026-10-21): the water outnumbered the land for 12 s and became the
    // background, so the land would have glowed. The land stays part of the background while it is still there.
    @Test fun waterOutnumberingTheLandDoesNotLightTheLandUp() {
        val land = rgb(28, 56, 87)
        val water = rgb(1, 32, 84)
        val pixels = IntArray(24 * 18) { i ->
            when {
                i % 10 == 0 -> rgb(150, 165, 180)
                i % 10 < 4 -> land
                else -> water
            }
        }
        assertEquals(water, MazdaHudMapLook.backgroundColor(pixels))
        assertEquals(land, MazdaHudMapLook.backgroundColor(pixels, previous = land))
        // Out on the water, with almost no land left in the picture, the water is the background.
        val openWater = IntArray(24 * 18) { i -> if (i % 20 == 0) land else water }
        assertEquals(water, MazdaHudMapLook.backgroundColor(openWater, previous = land))
    }

    @Test fun aLightMapIgnoresTheNightBackground() {
        val day = rgb(242, 239, 233)
        val pixels = IntArray(24 * 18) { i -> if (i % 3 == 0) rgb(28, 56, 87) else day }
        assertEquals(day, MazdaHudMapLook.backgroundColor(pixels, previous = rgb(28, 56, 87)))
    }

    @Test fun nightBackgroundsAreSubtractedAndDayOnesFaded() {
        assertTrue(MazdaHudMapLook.isDark(rgb(28, 56, 87)))
        assertTrue(MazdaHudMapLook.isDark(rgb(43, 43, 46)))
        assertFalse(MazdaHudMapLook.isDark(rgb(242, 239, 233)))
    }

    @Test fun noPictureMeansBlack() {
        assertEquals(rgb(0, 0, 0), MazdaHudMapLook.backgroundColor(IntArray(0)))
    }

    @Test fun smallMovesDoNotRedrawButANewMapColourDoes() {
        assertFalse(MazdaHudMapLook.changed(rgb(43, 43, 46), rgb(45, 42, 46)))
        assertTrue(MazdaHudMapLook.changed(rgb(43, 43, 46), rgb(242, 239, 233)))
    }

    // About a main road's width on each HUD box; never under a few pixels.
    @Test fun theLineTestReachesFurtherOnLargerMaps() {
        assertEquals(6.2f, MazdaHudMapLook.reach(178, 178), 0.1f)
        assertEquals(19.6f, MazdaHudMapLook.reach(735, 560), 0.1f)
        assertEquals(3f, MazdaHudMapLook.reach(40, 40), 0f)
    }

    @Test fun theWholeMapAtSeventyPercentIsTheDefaultAndChoicesAreKept() {
        val prefs = context.getSharedPreferences("diplay_mazda_hud", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(MazdaHudSettings.MapStyle.WHOLE, MazdaHudSettings.mapStyle(context))
        assertEquals(MazdaHudSettings.DEFAULT_SEE_THROUGH, MazdaHudSettings.mapSeeThrough(context))
        MazdaHudSettings.saveMapStyle(context, MazdaHudSettings.MapStyle.LINES)
        MazdaHudSettings.saveMapSeeThrough(context, 140)
        assertEquals(MazdaHudSettings.MapStyle.LINES, MazdaHudSettings.mapStyle(context))
        assertEquals(100, MazdaHudSettings.mapSeeThrough(context))
        assertTrue(MazdaHudSettings.DEFAULT_SEE_THROUGH in MazdaHudSettings.SEE_THROUGH_STEPS)
    }

    // Daylight drive, 2026-10-31: the whole map's roads hardly showed on the glass. They are lifted by default now.
    @Test fun roadsAreBrightenedByDefaultAndTheChoiceIsKept() {
        val prefs = context.getSharedPreferences("diplay_mazda_hud", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(MazdaHudSettings.DEFAULT_ROAD_BOOST, MazdaHudSettings.mapRoadBoost(context))
        assertTrue(MazdaHudSettings.DEFAULT_ROAD_BOOST in MazdaHudSettings.ROAD_BOOST_STEPS)
        MazdaHudSettings.saveMapRoadBoost(context, 0)
        assertEquals(0, MazdaHudSettings.mapRoadBoost(context))
        MazdaHudSettings.saveMapRoadBoost(context, 130)
        assertEquals(100, MazdaHudSettings.mapRoadBoost(context))
    }

    // The filter sets every uniform the shader declares; a missing one would leave the map unfiltered.
    @Test fun theShaderDeclaresTheRoadBrightness() {
        assertTrue(MazdaHudMapLook.SHADER.contains("uniform float roadBoost;"))
    }

    // The HUD window follows the slider while it is shown; it stops listening when it closes.
    @Test fun theWindowHearsLookChangesUntilItStops() {
        var heard = 0
        val stop = MazdaHudSettings.watchMapLook(context) { heard++ }
        MazdaHudSettings.saveMapSeeThrough(context, 30)
        MazdaHudSettings.saveMapStyle(context, MazdaHudSettings.MapStyle.WHOLE)
        MazdaHudSettings.saveMapRoadBoost(context, 40)
        MazdaHudSettings.saveMapView(context, MazdaHudSettings.MapView.FAR)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("Style, see-through and road brightness, not the map view", 3, heard)
        stop()
        MazdaHudSettings.saveMapSeeThrough(context, 50)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(3, heard)
    }
}
