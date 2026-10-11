package com.shilapi.xcertplay

import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How the EZ-60 lights follow the music, as the driver tuned it. */
internal data class MazdaAmbientTuning(
    val effect: Effect = Effect.RHYTHM,
    /** 1 (quick) to 5 (smooth): how fast the light rises and fades. */
    val smoothness: Int = 3,
    /** 10 to 100 %: how far the light dims below full between beats or in quiet passages. */
    val depth: Int = 60,
    /** 1 to 5: how small a bass kick counts as a beat (Beat only). */
    val sensitivity: Int = 3,
) {
    enum class Effect { BEAT, RHYTHM }

    companion object {
        val SMOOTHNESS = 1..5
        val DEPTHS = (10..100 step 10).toList()
        val SENSITIVITY = 1..5
    }
}

/**
 * The rhythm brightness for each tick, 5 to 100 in steps of 5. Rhythm: the light follows the music's loudness
 * through a rise and a fade set by the smoothness. Beat: the light jumps to full on each bass kick (a bass level
 * clearly above its recent average) and fades until the next.
 */
internal class MazdaAmbientDynamics {
    private var lastMillis: Long? = null
    private var loudest = MIN_LOUDEST
    private var averageBass = 0.0
    private var lastBeatMillis = Long.MIN_VALUE / 2
    private var light = 0.0

    fun reset() {
        lastMillis = null
        loudest = MIN_LOUDEST
        averageBass = 0.0
        lastBeatMillis = Long.MIN_VALUE / 2
        light = 0.0
    }

    fun brightness(level: Double, bass: Double, nowMillis: Long, tuning: MazdaAmbientTuning): Int {
        val elapsed = lastMillis?.let { (nowMillis - it).coerceIn(0, MAX_STEP_MILLIS) }?.toDouble() ?: 0.0
        lastMillis = nowMillis
        val smoothness = tuning.smoothness.coerceIn(MazdaAmbientTuning.SMOOTHNESS)
        val loud = if (level.isFinite()) level.coerceIn(0.0, 1.0) else 0.0
        val kick = if (bass.isFinite()) bass.coerceIn(0.0, 1.0) else 0.0
        when (tuning.effect) {
            MazdaAmbientTuning.Effect.RHYTHM -> {
                loudest = maxOf(loud, loudest * exp(-elapsed / LOUDEST_FADE_MILLIS), MIN_LOUDEST)
                val target = sqrt((loud / loudest).coerceIn(0.0, 1.0))
                val timeConstant = if (target > light) RISE_MILLIS * smoothness else FADE_MILLIS * smoothness
                light += (target - light) * (1 - exp(-elapsed / timeConstant))
            }
            MazdaAmbientTuning.Effect.BEAT -> {
                averageBass = if (averageBass == 0.0) kick
                    else averageBass + (kick - averageBass) * (1 - exp(-elapsed / BASS_AVERAGE_MILLIS))
                val threshold = 1.15 + 0.15 * (MazdaAmbientTuning.SENSITIVITY.last -
                    tuning.sensitivity.coerceIn(MazdaAmbientTuning.SENSITIVITY))
                val beat = kick > MIN_BASS && kick > averageBass * threshold &&
                    nowMillis - lastBeatMillis >= MIN_BEAT_GAP_MILLIS
                if (beat) {
                    light = 1.0
                    lastBeatMillis = nowMillis
                } else {
                    light *= exp(-elapsed / (BEAT_FADE_MILLIS * smoothness))
                }
            }
        }
        light = light.coerceIn(0.0, 1.0)
        val floor = 100 - tuning.depth.coerceIn(MazdaAmbientTuning.DEPTHS.first(), MazdaAmbientTuning.DEPTHS.last())
        val value = floor + (100 - floor) * light
        return ((value / 5).roundToInt() * 5).coerceIn(5, 100)
    }

    private companion object {
        const val MAX_STEP_MILLIS = 1_000L
        const val MIN_LOUDEST = 0.02
        const val LOUDEST_FADE_MILLIS = 8_000.0
        const val RISE_MILLIS = 40.0
        const val FADE_MILLIS = 150.0
        const val BEAT_FADE_MILLIS = 120.0
        const val BASS_AVERAGE_MILLIS = 1_200.0
        const val MIN_BASS = 0.003
        const val MIN_BEAT_GAP_MILLIS = 250L
    }
}
