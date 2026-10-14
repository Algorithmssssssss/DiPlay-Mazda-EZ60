package com.shilapi.xcertplay

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import kotlin.math.roundToLong

/**
 * A made-up drive for trying the EZ-60 HUD in the Android emulator (tools/ez60-emulator on the ez60-emulator
 * branch), where CarPlay cannot connect. It feeds the two HUD parts that CarPlay guidance feeds: [MazdaHud] (the
 * car's navigation service) and [MazdaHudScreen] (DiPlay's window on the 800x480 HUD display).
 * Emulator only: in the car it would take the HUD from the car's own navigation.
 */
internal object MazdaHudPracticeRoute {
    const val LOG_TAG = "DiPlayHud"

    /** The next maneuver, [meters] after the previous one. Icons are AMap codes, as in CarPlay guidance. */
    internal data class Leg(val icon: Int, val roundaboutExit: Int, val road: String, val meters: Int)

    internal val LEGS = listOf(
        Leg(icon = 2, roundaboutExit = 0, road = "Harbor Road", meters = 450), // left
        Leg(icon = 3, roundaboutExit = 0, road = "Station Street", meters = 350), // right
        Leg(icon = 5, roundaboutExit = 0, road = "Ring Road", meters = 500), // keep right
        Leg(icon = 11, roundaboutExit = 2, road = "Park Avenue", meters = 450), // roundabout, second exit
        Leg(icon = 6, roundaboutExit = 0, road = "Mill Lane", meters = 300), // sharp left
        Leg(icon = 15, roundaboutExit = 0, road = "", meters = 250), // destination
    )
    internal val TOTAL_METERS = LEGS.sumOf { it.meters }

    // Fast-forwarded: 25 m of route each second, so the drive takes about 90 s. Time left is shown for 45 km/h.
    internal const val METERS_PER_SECOND = 25
    private const val SHOWN_METERS_PER_SECOND = 12.5
    // The arrival stays on the HUD this long before guidance ends.
    internal const val ARRIVED_SECONDS = 4

    /** The Android emulator, by its virtual hardware. */
    val available: Boolean get() = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"

    /** The guidance [elapsedSeconds] into the drive, or null once it is over. */
    fun guidanceAt(elapsedSeconds: Int): ClusterTurnGuidance? {
        val driven = elapsedSeconds.coerceAtLeast(0) * METERS_PER_SECOND
        if (driven >= TOTAL_METERS + ARRIVED_SECONDS * METERS_PER_SECOND) return null
        var legEnd = 0
        var leg = LEGS.last()
        for (candidate in LEGS) {
            legEnd += candidate.meters
            if (legEnd > driven) {
                leg = candidate
                break
            }
        }
        val remaining = (TOTAL_METERS - driven).coerceAtLeast(0)
        // Like a navigation app, the distance to the maneuver counts down in steps of 10 m.
        val toManeuver = (legEnd - driven).coerceAtLeast(0) / 10 * 10
        return ClusterTurnGuidance(leg.icon, leg.roundaboutExit, toManeuver, leg.road,
            remainingSeconds = (remaining / SHOWN_METERS_PER_SECOND).roundToLong(), remainingMeters = remaining.toLong())
    }
}

/** Plays [MazdaHudPracticeRoute] on the HUD, one update a second. Main thread only. */
internal class MazdaHudPracticeDrive(
    private val activity: Activity,
    private val log: (String) -> Unit,
    private val onFinished: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var hud: MazdaHud? = null
    private var screen: MazdaHudScreen? = null
    private var startedAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            val current = hud ?: return
            val guidance = MazdaHudPracticeRoute.guidanceAt(((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt())
            if (guidance == null) {
                stop()
                onFinished()
                return
            }
            current.update(guidance)
            screen?.guidance = guidance
            handler.postDelayed(this, 1000)
        }
    }

    val running: Boolean get() = hud != null

    /** False when there is no 800x480 HUD display to show DiPlay's window on. */
    fun start(): Boolean {
        if (running) return true
        val display = MazdaHudScreen.findDisplay(activity) ?: return false
        val window = MazdaHudScreen(activity, display) { }
        try {
            window.show()
        } catch (error: RuntimeException) {
            log("HUD practice route: window failed ${error.javaClass.simpleName}: ${error.message}")
            return false
        }
        // There is no CarPlay map to stream, so the window shows DiPlay's turn card.
        window.setMapActive(false)
        screen = window
        hud = MazdaHud(activity.applicationContext, log)
        startedAt = SystemClock.elapsedRealtime()
        log("HUD practice route: started on display ${display.displayId}, ${MazdaHudPracticeRoute.TOTAL_METERS} m")
        handler.post(tick)
        return true
    }

    /** Ends guidance on the HUD, as when the driver ends a CarPlay route, and closes DiPlay's HUD window. */
    fun stop() {
        val current = hud ?: return
        handler.removeCallbacks(tick)
        hud = null
        current.update(null)
        current.close()
        screen?.let { runCatching { it.dismiss() } }
        screen = null
        log("HUD practice route: ended")
    }
}
