package com.shilapi.xcertplay

/**
 * The EZ-60 ambient lights following CarPlay music, as the car's media center does for its own player
 * (com.tinnove.mediacenter, CarAtmoManager): music mode on (light zones open, scene mode 2), the rhythm colour, a
 * rhythm brightness from the music level about 8 times a second, then the lights handed back (scene mode 1, zones
 * closed, the driver's colour and brightness). How the brightness follows the music is [MazdaAmbientDynamics].
 * Pure logic; [MazdaAmbientLights] runs it on one thread.
 *
 * Safety: only [allowed] writes leave this class. Any write that fails, or the car not entering music mode, stops
 * the show for the rest of the CarPlay session and hands the lights back. If the car or the media center takes the
 * lights back, the show stops without writing until the music starts again.
 */
internal class MazdaAmbientShow(
    private val write: (Write) -> Boolean,
    private val readSceneMode: () -> Int?,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    private val leftOn: (Boolean) -> Unit,
) {
    /** One property write, in the media center's form. */
    data class Write(val property: Int, val area: Int, val value: Any) {
        override fun toString() = "$property area=$area value=${MazdaAmbientProbe.describe(value)}"
    }

    /** The car's own ambient settings (Settings.Global), as the media center reads them. */
    data class CarSettings(val musicSyncOn: Boolean, val fixedColour: Boolean, val colour: Int, val brightness: Int)

    enum class State { IDLE, ACTIVE, FAILED }

    var state = State.IDLE
        private set
    private var verifyAtMillis = 0L
    private var nextCheckMillis = 0L
    private var nextKeepAliveMillis = 0L
    private val dynamics = MazdaAmbientDynamics()
    private var sentBrightness = -1
    private var sentColour = -1
    private var cycleStep = 0
    private var restoreSettings: CarSettings? = null
    // The car or media center took the lights back: wait for the music to stop or the next song before trying again.
    private var yielded = false

    /**
     * One tick. [wanted]: switch on, CarPlay music playing with fresh audio, and DiPlay's music in focus; [level] and
     * [bass]: the loudest music since the last tick.
     */
    fun tick(wanted: Boolean, settings: CarSettings, level: Double, bass: Double = 0.0,
        tuning: MazdaAmbientTuning = MazdaAmbientTuning()) {
        if (state == State.FAILED) return
        if (!wanted) yielded = false
        val run = wanted && !yielded && settings.musicSyncOn && settings.brightness != 0
        when {
            state == State.IDLE && run -> open(settings)
            state == State.ACTIVE && !run -> close("music stopped or the lights were turned off")
            state == State.ACTIVE -> pulse(settings, level, bass, tuning)
        }
    }

    /**
     * The music stopped, or the CarPlay session or the switch ended: hand the lights back now. Lights the car took
     * back may be tried again: no tick runs while the music is stopped, so the tick never saw it stop (an EZ-60
     * driver had to turn the switch off and on to get the lights back).
     */
    fun stop(reason: String) {
        yielded = false
        if (state == State.ACTIVE) close(reason)
    }

    /** A new song: lights the car took back during the last one may be tried again, once. */
    fun newSong() {
        if (!yielded) return
        yielded = false
        log("Ambient: new song; trying the lights again")
    }

    /** A new CarPlay session may try again after a failure. */
    fun reset() {
        if (state == State.FAILED) state = State.IDLE
        yielded = false
    }

    /**
     * DiPlay stopped while the lights were in music mode: hand them back with [settings]. A show running in this
     * process owns them (its screens are recreated when the driver moves between CarPlay and Settings): left alone.
     */
    fun restoreLeftOn(settings: CarSettings) {
        if (state == State.ACTIVE) return
        log("Ambient: handing back lights left in music mode")
        handBack(settings)
        leftOn(false)
    }

    private fun open(settings: CarSettings) {
        restoreSettings = settings
        leftOn(true)
        dynamics.reset()
        sentBrightness = -1
        sentColour = -1
        cycleStep = 0
        state = State.ACTIVE
        if (!send(Write(LIGHT_ZONES, 0, ZONES_OPEN)) || !send(Write(SCENE_MODE, 0, SCENE_MUSIC))) return
        if (!send(Write(RHYTHM_COLOUR, COLOUR_AREA, colourFor(settings)))) return
        val time = now()
        verifyAtMillis = time + VERIFY_DELAY_MILLIS
        nextCheckMillis = verifyAtMillis
        nextKeepAliveMillis = time + KEEP_ALIVE_MILLIS
        log("Ambient: music mode on colour=${colourFor(settings)} fixed=${settings.fixedColour}")
    }

    private fun pulse(settings: CarSettings, level: Double, bass: Double, tuning: MazdaAmbientTuning) {
        val time = now()
        if (time >= nextCheckMillis) {
            val mode = readSceneMode()
            if (mode != SCENE_MUSIC) {
                if (time - verifyAtMillis < CHECK_EVERY_MILLIS) {
                    fail("the car did not enter music mode (scene mode=$mode)")
                } else {
                    // Someone else (the car, its media center) owns the lights now: leave them alone.
                    state = State.IDLE
                    yielded = true
                    leftOn(false)
                    log("Ambient: the car took the lights back (scene mode=$mode); stopped")
                }
                return
            }
            nextCheckMillis = time + CHECK_EVERY_MILLIS
        }
        restoreSettings = settings
        if (time >= nextKeepAliveMillis) {
            if (!send(Write(LIGHT_ZONES, 0, ZONES_OPEN)) || !send(Write(SCENE_MODE, 0, SCENE_MUSIC))) return
            nextKeepAliveMillis = time + KEEP_ALIVE_MILLIS
        }
        val colour = if (settings.fixedColour) colourFor(settings) else cycledColour(settings.colour)
        if (colour != sentColour && !send(Write(RHYTHM_COLOUR, COLOUR_AREA, colour))) return
        sentColour = colour
        val brightness = dynamics.brightness(level, bass, time, tuning)
        if (brightness != sentBrightness && !send(Write(RHYTHM_BRIGHTNESS, 0, brightness))) return
        sentBrightness = brightness
    }

    private fun close(reason: String) {
        state = State.IDLE
        restoreSettings?.let(::handBack)
        leftOn(false)
        log("Ambient: lights handed back ($reason)")
    }

    private fun fail(reason: String) {
        state = State.FAILED
        log("Ambient: stopped: $reason; off until the next CarPlay connection")
        runCatching { restoreSettings?.let(::handBack) }
        leftOn(false)
    }

    // The media center's restore: scene mode normal, zones closed, the driver's colour and brightness.
    private fun handBack(settings: CarSettings) {
        for (step in listOf(
            Write(SCENE_MODE, 0, SCENE_NORMAL),
            Write(LIGHT_ZONES, 0, ZONES_CLOSED),
            Write(RHYTHM_COLOUR, COLOUR_AREA, colourFor(settings)),
            Write(RHYTHM_BRIGHTNESS, 0, settings.brightness.coerceIn(0, MAX_BRIGHTNESS)),
        )) runCatching { if (allowed(step)) write(step) }
    }

    private fun send(step: Write): Boolean {
        val ok = allowed(step) && runCatching { write(step) }.getOrDefault(false)
        if (!ok) fail("write refused: $step")
        return ok
    }

    // The media center's mode 0: step through the colour bar from the driver's colour, 4 steps at a time.
    private fun cycledColour(base: Int): Int {
        cycleStep = (cycleStep + 1) % COLOUR_STEPS
        val colour = base.coerceIn(1, MAX_COLOUR) + cycleStep * 4
        return if (colour > MAX_COLOUR) colour - MAX_COLOUR else colour
    }

    internal companion object {
        const val SCENE_MODE = 826278772
        const val LIGHT_ZONES = 829424549
        const val RHYTHM_COLOUR = 826278822
        const val RHYTHM_BRIGHTNESS = 826278823
        const val SCENE_NORMAL = 1
        const val SCENE_MUSIC = 2
        const val COLOUR_AREA = 1 // the EZ-60's 256-colour light bar
        const val MAX_COLOUR = 256
        const val MAX_BRIGHTNESS = 100
        private const val COLOUR_STEPS = 64
        val ZONES_OPEN = byteArrayOf(-1, -1, -1)
        val ZONES_CLOSED = byteArrayOf(0, 0, 0)
        const val TICK_MILLIS = 120L // the media center's rhythm brightness cadence
        private const val VERIFY_DELAY_MILLIS = 800L
        private const val CHECK_EVERY_MILLIS = 2_000L
        private const val KEEP_ALIVE_MILLIS = 1_000L

        fun colourFor(settings: CarSettings): Int = settings.colour.coerceIn(1, MAX_COLOUR)

        /** The only writes this show can make, each with the values the media center uses. */
        fun allowed(step: Write): Boolean = when (step.property) {
            SCENE_MODE -> step.area == 0 && (step.value == SCENE_NORMAL || step.value == SCENE_MUSIC)
            LIGHT_ZONES -> step.area == 0 && step.value is ByteArray &&
                (step.value.contentEquals(ZONES_OPEN) || step.value.contentEquals(ZONES_CLOSED))
            RHYTHM_COLOUR -> step.area == COLOUR_AREA && step.value is Int && step.value in 1..MAX_COLOUR
            RHYTHM_BRIGHTNESS -> step.area == 0 && step.value is Int && step.value in 0..MAX_BRIGHTNESS
            else -> false
        }
    }
}
