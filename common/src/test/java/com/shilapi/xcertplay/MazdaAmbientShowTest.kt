package com.shilapi.xcertplay

import com.shilapi.xcertplay.MazdaAmbientShow.Companion.LIGHT_ZONES
import com.shilapi.xcertplay.MazdaAmbientShow.Companion.RHYTHM_BRIGHTNESS
import com.shilapi.xcertplay.MazdaAmbientShow.Companion.RHYTHM_COLOUR
import com.shilapi.xcertplay.MazdaAmbientShow.Companion.SCENE_MODE
import com.shilapi.xcertplay.MazdaAmbientShow.State
import com.shilapi.xcertplay.MazdaAmbientShow.Write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MazdaAmbientShowTest {
    private val writes = mutableListOf<Write>()
    private var accept = true
    private var sceneMode: Int? = 2
    private var now = 0L
    private var leftOn = false
    private val on = MazdaAmbientShow.CarSettings(musicSyncOn = true, fixedColour = true, colour = 6, brightness = 3)
    private val show = MazdaAmbientShow(
        write = { writes += it; accept },
        readSceneMode = { sceneMode },
        now = { now },
        log = {},
        leftOn = { leftOn = it },
    )

    @Test fun onlyTheFourLightWritesWithTheMediaCentersValuesAreAllowed() {
        assertTrue(MazdaAmbientShow.allowed(Write(SCENE_MODE, 0, 2)))
        assertTrue(MazdaAmbientShow.allowed(Write(SCENE_MODE, 0, 1)))
        assertTrue(MazdaAmbientShow.allowed(Write(LIGHT_ZONES, 0, byteArrayOf(-1, -1, -1))))
        assertTrue(MazdaAmbientShow.allowed(Write(LIGHT_ZONES, 0, byteArrayOf(0, 0, 0))))
        assertTrue(MazdaAmbientShow.allowed(Write(RHYTHM_COLOUR, 1, 6)))
        assertTrue(MazdaAmbientShow.allowed(Write(RHYTHM_BRIGHTNESS, 0, 100)))
        assertFalse(MazdaAmbientShow.allowed(Write(826278810, 0, 1))) // the driver's own brightness
        assertFalse(MazdaAmbientShow.allowed(Write(SCENE_MODE, 0, 3)))
        assertFalse(MazdaAmbientShow.allowed(Write(SCENE_MODE, 1, 2)))
        assertFalse(MazdaAmbientShow.allowed(Write(LIGHT_ZONES, 0, byteArrayOf(1, 2, 3))))
        assertFalse(MazdaAmbientShow.allowed(Write(RHYTHM_COLOUR, 1, 0)))
        assertFalse(MazdaAmbientShow.allowed(Write(RHYTHM_COLOUR, 1, 300)))
        assertFalse(MazdaAmbientShow.allowed(Write(RHYTHM_BRIGHTNESS, 0, 101)))
        assertFalse(MazdaAmbientShow.allowed(Write(RHYTHM_BRIGHTNESS, 0, "50")))
    }

    @Test fun musicModeOpensThenPulses() {
        show.tick(true, on, 0.3)
        assertEquals(State.ACTIVE, show.state)
        assertTrue(leftOn)
        assertEquals(listOf(LIGHT_ZONES, SCENE_MODE, RHYTHM_COLOUR), writes.map { it.property })
        writes.clear()
        now = 900
        show.tick(true, on, 0.3)
        val pulse = writes.last()
        assertEquals(RHYTHM_BRIGHTNESS, pulse.property)
        assertTrue(pulse.value as Int in 5..100)
    }

    @Test fun theCarNotEnteringMusicModeStopsAndHandsTheLightsBack() {
        show.tick(true, on, 0.3)
        sceneMode = 1
        now = 900
        writes.clear()
        show.tick(true, on, 0.3)
        assertEquals(State.FAILED, show.state)
        assertFalse(leftOn)
        assertEquals(listOf(SCENE_MODE, LIGHT_ZONES, RHYTHM_COLOUR, RHYTHM_BRIGHTNESS), writes.map { it.property })
        assertEquals(1, writes.first().value)
        writes.clear()
        show.tick(true, on, 0.3)
        assertTrue(writes.isEmpty())
    }

    @Test fun aRefusedWriteStopsUntilTheNextSession() {
        accept = false
        show.tick(true, on, 0.3)
        assertEquals(State.FAILED, show.state)
        accept = true
        writes.clear()
        show.tick(true, on, 0.3)
        assertTrue(writes.isEmpty())
        show.reset()
        show.tick(true, on, 0.3)
        assertEquals(State.ACTIVE, show.state)
    }

    @Test fun whenTheCarTakesTheLightsBackDiPlayLeavesThemAlone() {
        show.tick(true, on, 0.3)
        now = 900
        show.tick(true, on, 0.3)
        sceneMode = 1
        now = 3_000
        writes.clear()
        show.tick(true, on, 0.3)
        assertEquals(State.IDLE, show.state)
        assertTrue(writes.isEmpty())
        show.tick(true, on, 0.3)
        assertTrue(writes.isEmpty())
        // The music stops and starts again: DiPlay may try once more.
        show.tick(false, on, 0.0)
        sceneMode = 2
        show.tick(true, on, 0.3)
        assertEquals(State.ACTIVE, show.state)
    }

    // No tick runs while the music is stopped, so the stop comes through stop() (EZ-60 2026-10-21: a driver had to
    // turn the switch off and on to get the lights back).
    @Test fun afterTheCarTookTheLightsBackTheNextPlayOrSongTriesAgain() {
        show.tick(true, on, 0.3)
        now = 900
        show.tick(true, on, 0.3)
        sceneMode = 1
        now = 3_000
        show.tick(true, on, 0.3)
        assertEquals(State.IDLE, show.state)
        show.stop("music stopped")
        sceneMode = 2
        show.tick(true, on, 0.3)
        assertEquals(State.ACTIVE, show.state)

        now = 5_000
        show.tick(true, on, 0.3)
        sceneMode = 1
        now = 7_500
        show.tick(true, on, 0.3)
        assertEquals(State.IDLE, show.state)
        show.tick(true, on, 0.3)
        assertEquals("Still the same song: left alone", State.IDLE, show.state)
        show.newSong()
        sceneMode = 2
        show.tick(true, on, 0.3)
        assertEquals(State.ACTIVE, show.state)
    }

    @Test fun stoppingTheMusicHandsBackTheDriversColourAndBrightness() {
        show.tick(true, on, 0.3)
        writes.clear()
        show.tick(false, on, 0.0)
        assertEquals(State.IDLE, show.state)
        assertFalse(leftOn)
        assertEquals(listOf(Write(SCENE_MODE, 0, 1), Write(RHYTHM_COLOUR, 1, 6), Write(RHYTHM_BRIGHTNESS, 0, 3)),
            writes.filter { it.property != LIGHT_ZONES })
    }

    // EZ-60 report 20: opening DiPlay's screen from CarPlay ran the start-up hand-back while the show was running.
    @Test fun theStartUpHandBackLeavesARunningShowAlone() {
        show.tick(true, on, 0.3)
        writes.clear()
        show.restoreLeftOn(on)
        assertTrue(writes.isEmpty())
        assertTrue(leftOn)
        assertEquals(State.ACTIVE, show.state)
    }

    @Test fun theStartUpHandBackRestoresLightsAStoppedAppLeftOn() {
        leftOn = true
        show.restoreLeftOn(on)
        assertEquals(Write(SCENE_MODE, 0, 1), writes.first())
        assertFalse(leftOn)
    }

    @Test fun nothingHappensWithoutTheCarsMusicSync() {
        show.tick(true, on.copy(musicSyncOn = false), 0.3)
        show.tick(true, on.copy(brightness = 0), 0.3)
        assertTrue(writes.isEmpty())
        assertEquals(State.IDLE, show.state)
    }
}
