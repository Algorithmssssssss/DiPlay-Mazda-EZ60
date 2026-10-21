package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Levels read from the car's sound output for Bluetooth music (Visualizer waveforms: 8-bit, 128 = silence). */
class MazdaCarOutputLevelTest {
    private fun tone(hz: Double, amplitude: Double, rate: Int = 48_000, size: Int = 1024) = ByteArray(size) { i ->
        (128 + 127 * amplitude * sin(2 * PI * hz * i / rate)).roundToInt().coerceIn(0, 255).toByte()
    }

    @Test fun silenceIsNothing() {
        val peak = MazdaCarOutputLevel.levels(ByteArray(1024) { 128.toByte() }, 48_000)
        assertEquals(0.0, peak.level, 0.001)
        assertEquals(0.0, peak.bass, 0.001)
    }

    @Test fun louderMusicReadsLouder() {
        val quiet = MazdaCarOutputLevel.levels(tone(440.0, 0.1), 48_000)
        val loud = MazdaCarOutputLevel.levels(tone(440.0, 0.8), 48_000)
        assertEquals(0.8 / 0.1, loud.level / quiet.level, 0.3)
    }

    // A bass kick reaches the bass level; a high note hardly does.
    @Test fun bassIsTheLowEnd() {
        val kick = MazdaCarOutputLevel.levels(tone(60.0, 0.5), 48_000)
        val cymbal = MazdaCarOutputLevel.levels(tone(6_000.0, 0.5), 48_000)
        assertTrue("kick ${kick.bass} vs level ${kick.level}", kick.bass > kick.level * 0.6)
        assertTrue("cymbal ${cymbal.bass}", cymbal.bass < cymbal.level * 0.1)
    }

    @Test fun anEmptyCaptureIsNothing() {
        assertEquals(0.0, MazdaCarOutputLevel.levels(ByteArray(0), 48_000).level, 0.0)
    }
}
