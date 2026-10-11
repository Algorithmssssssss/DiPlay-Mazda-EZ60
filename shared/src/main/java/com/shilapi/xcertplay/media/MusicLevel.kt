package com.shilapi.xcertplay.media

/**
 * How loud the CarPlay music being played is, for lights that follow it (the EZ-60's ambient lights). The audio
 * thread only stores a number here, and only while [wanted]; the reader takes the loudest level since its last
 * read.
 */
object MusicLevel {
    @Volatile var wanted = false
    @Volatile private var peak = 0.0
    /** System.nanoTime of the last music written to the speakers, 0 before any. */
    @Volatile var atNanos = 0L
        private set

    /** On the audio thread: the RMS (0..1) of a block just written. Never throws. */
    internal fun record(rms: Double) {
        if (rms > peak) peak = rms
        atNanos = System.nanoTime()
    }

    /** The loudest level since the previous call, 0..1. */
    fun takePeak(): Double {
        val value = peak
        peak = 0.0
        return value
    }
}
