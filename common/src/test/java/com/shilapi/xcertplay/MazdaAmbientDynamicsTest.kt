package com.shilapi.xcertplay

import com.shilapi.xcertplay.MazdaAmbientTuning.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MazdaAmbientDynamicsTest {
    private fun run(tuning: MazdaAmbientTuning, levels: List<Pair<Double, Double>>): List<Int> {
        val dynamics = MazdaAmbientDynamics()
        return levels.mapIndexed { i, (level, bass) -> dynamics.brightness(level, bass, i * 120L, tuning) }
    }

    @Test fun rhythmRisesGraduallyAndSmoothnessSlowsIt() {
        val loud = List(4) { 0.4 to 0.1 }
        val quick = run(MazdaAmbientTuning(Effect.RHYTHM, smoothness = 1, depth = 100), loud)
        val smooth = run(MazdaAmbientTuning(Effect.RHYTHM, smoothness = 5, depth = 100), loud)
        assertTrue(quick[1] > smooth[1])
        assertTrue(smooth.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(smooth[1] < 100)
    }

    @Test fun depthKeepsTheLightsUpBetweenBeats() {
        val quietThenLoud = listOf(0.4 to 0.1) + List(20) { 0.0 to 0.0 }
        assertTrue(run(MazdaAmbientTuning(Effect.RHYTHM, depth = 30), quietThenLoud).all { it >= 70 })
        assertTrue(run(MazdaAmbientTuning(Effect.BEAT, depth = 30), quietThenLoud).all { it >= 70 })
    }

    @Test fun aBassKickFlashesAndThenFades() {
        val steady = List(10) { 0.2 to 0.02 }
        val kick = listOf(0.4 to 0.12)
        val after = List(5) { 0.2 to 0.02 }
        val out = run(MazdaAmbientTuning(Effect.BEAT, smoothness = 3, depth = 100), steady + kick + after)
        assertEquals(100, out[10])
        assertTrue(out.drop(10).zipWithNext().all { (a, b) -> b <= a })
        assertTrue(out[15] < 100)
    }

    @Test fun steadyBassWithoutKicksDoesNotFlash() {
        val out = run(MazdaAmbientTuning(Effect.BEAT, depth = 100), List(30) { 0.2 to 0.02 })
        assertTrue(out.all { it == 5 })
    }

    @Test fun kicksCloserThanAQuarterSecondCountOnce() {
        val dynamics = MazdaAmbientDynamics()
        val tuning = MazdaAmbientTuning(Effect.BEAT, smoothness = 1, depth = 100)
        repeat(5) { dynamics.brightness(0.2, 0.02, it * 120L, tuning) }
        assertEquals(100, dynamics.brightness(0.4, 0.12, 600, tuning))
        // 120 ms later, another kick is ignored and the light keeps fading.
        assertTrue(dynamics.brightness(0.4, 0.2, 720, tuning) < 100)
    }

    @Test fun outputStaysInTheCarsRangeEvenForBadInput() {
        val out = run(MazdaAmbientTuning(Effect.RHYTHM),
            listOf(Double.NaN to Double.NaN, 5.0 to 5.0, -1.0 to -1.0, 0.3 to 0.1))
        assertTrue(out.all { it in 5..100 && it % 5 == 0 })
    }
}
