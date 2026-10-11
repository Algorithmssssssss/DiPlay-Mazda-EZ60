package com.shilapi.xcertplay.media

/**
 * How loud the CarPlay music being played is, overall and in its bass (40–180 Hz, where the beat is), for lights
 * that follow it (the EZ-60's ambient lights). The audio thread only stores numbers here, and only while [wanted];
 * the reader takes the loudest levels since its last read.
 */
object MusicLevel {
    /** The loudest overall and bass RMS (0..1) since the previous [take]. */
    data class Peak(val level: Double, val bass: Double)

    @Volatile var wanted = false
    @Volatile private var level = 0.0
    @Volatile private var bass = 0.0
    /** System.nanoTime of the last music written to the speakers, 0 before any. */
    @Volatile var atNanos = 0L
        private set

    /** On the audio thread: the RMS of a block just written and of its bass. Never throws. */
    internal fun record(rms: Double, bassRms: Double) {
        if (rms > level) level = rms
        if (bassRms > bass) bass = bassRms
        atNanos = System.nanoTime()
    }

    fun take(): Peak {
        val peak = Peak(level, bass)
        level = 0.0
        bass = 0.0
        return peak
    }
}
