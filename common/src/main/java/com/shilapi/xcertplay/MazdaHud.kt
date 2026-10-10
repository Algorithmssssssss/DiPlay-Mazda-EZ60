package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.MazdaInteractiveService.Companion.ABANDON_NAVI_FOCUS
import com.shilapi.xcertplay.MazdaInteractiveService.Companion.REMAIN
import com.shilapi.xcertplay.MazdaInteractiveService.Companion.REQUEST_NAVI_FOCUS
import com.shilapi.xcertplay.MazdaInteractiveService.Companion.ROAD
import com.shilapi.xcertplay.MazdaInteractiveService.Companion.STATUS
import com.shilapi.xcertplay.MazdaInteractiveService.Companion.TURN
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.util.concurrent.Executors

/**
 * CarPlay's turn-by-turn guidance on the Mazda EZ-60's windshield HUD. The car has no instrument cluster; its
 * HUD draws navigation that apps hand to the head unit's interaction service ([MazdaInteractiveService]), as the
 * car's navigation apps do. A window on the HUD's 800x480 display ("MeterDisplay#0") is projected only while an
 * app holds navigation focus (EZ-60 probe, 2026-10-11).
 *
 * Takes and gives back navigation focus, and sends the status, the next turn, the road names and what is left of
 * the route. Calls run on one background thread; [log] gets each step that matters.
 */
internal class MazdaHud(private val context: Context, private val log: (String) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "diplay-mazda-hud").apply { isDaemon = true } }
    private val callback = MazdaInteractiveService.FocusCallback(MazdaInteractiveService.NAVI_FOCUS_CALLBACK) {
        log("HUD: navigation focus changed value=$it")
    }
    // Worker thread only.
    private val service = MazdaInteractiveService(log)
    private var active = false
    private var sent: Frame? = null
    private var sentAtMillis = 0L

    /** One HUD update, in the car's vocabulary. */
    internal data class Frame(
        val turnId: Int,
        val distanceMeters: Int,
        val currentRoad: String,
        val nextRoad: String,
        val remainingMeters: Int,
        val remainingSeconds: Int,
    )

    /** Sends [guidance], or ends the HUD's navigation when there is none. Any thread. */
    fun update(guidance: ClusterTurnGuidance?) {
        val frame = guidance?.let(::frame)
        // After close the worker refuses new work; the host drops this instance then.
        runCatching { worker.execute { if (frame == null) stopOnWorker() else sendOnWorker(frame) } }
    }

    fun close() {
        runCatching { worker.execute(::stopOnWorker) }
        worker.shutdown()
    }

    private fun sendOnWorker(frame: Frame) {
        service.binder() ?: return
        if (!active) {
            val focus = service.call(REQUEST_NAVI_FOCUS) {
                writeString(context.packageName)
                writeStrongBinder(callback)
            }
            val status = service.call(STATUS) { writeInt(STATUS_NAVIGATING) }
            active = true
            sent = null
            log("HUD: navigation started on the car's service focus=$focus status=$status")
        }
        val previous = sent
        val now = System.currentTimeMillis()
        val turnOrRoadChanged = previous == null || previous.turnId != frame.turnId ||
            previous.currentRoad != frame.currentRoad || previous.nextRoad != frame.nextRoad
        // Like the car's navigation apps: a new turn or road at once, other changes at most once a second.
        if (frame == previous || (!turnOrRoadChanged && now - sentAtMillis < MIN_INTERVAL_MILLIS)) return
        if (previous == null || previous.currentRoad != frame.currentRoad || previous.nextRoad != frame.nextRoad) {
            service.call(ROAD) { writeString(frame.currentRoad); writeString(frame.nextRoad) }
        }
        val turn = service.call(TURN) { writeInt(frame.turnId); writeInt(frame.distanceMeters) }
        service.call(REMAIN) { writeInt(frame.remainingMeters); writeInt(frame.remainingSeconds) }
        if (previous == null || previous.turnId != frame.turnId) {
            log("HUD: turn sent turnId=${frame.turnId} distanceM=${frame.distanceMeters} accepted=$turn")
        }
        sent = frame
        sentAtMillis = now
    }

    private fun stopOnWorker() {
        if (!active) return
        active = false
        sent = null
        service.binder() ?: return
        // The car's navigation apps clear in this order, then give focus back.
        service.call(REMAIN) { writeInt(0); writeInt(0) }
        service.call(ROAD) { writeString(""); writeString("") }
        service.call(TURN) { writeInt(0); writeInt(0) }
        service.call(STATUS) { writeInt(STATUS_IDLE) }
        service.call(ABANDON_NAVI_FOCUS) { writeString(context.packageName); writeStrongBinder(callback) }
        log("HUD: guidance ended; HUD cleared")
    }

    internal companion object {
        // The HUD's 800x480 display and the part the car's own apps draw into (probe test card only).
        const val WIDTH = 800
        const val HEIGHT = 480
        const val VISIBLE_LEFT = 573
        const val VISIBLE_TOP = 167
        const val VISIBLE_RIGHT = 800
        const val VISIBLE_BOTTOM = 355

        private const val STATUS_NAVIGATING = 2
        private const val STATUS_IDLE = 0
        private const val MIN_INTERVAL_MILLIS = 1_000L

        /** DiPlay's guidance (AMap icon codes) in the car's HUD vocabulary. */
        fun frame(guidance: ClusterTurnGuidance): Frame = Frame(
            turnId = turnId(guidance.icon, guidance.roundaboutExit),
            distanceMeters = guidance.distanceMeters.coerceAtLeast(0),
            currentRoad = "",
            nextRoad = guidance.road,
            remainingMeters = guidance.remainingMeters?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            remainingSeconds = guidance.remainingSeconds?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        )

        /**
         * AMap icon (BydClusterFrame) -> the car's HUD turn icon, numbered as EZNav's frame builder sends them
         * (c2.B4.c and D2.p.b; EZNav's ↖ at 18 confirmed on the car's HUD, 2026-10-11). The HUD draws no arrow
         * for an unknown number.
         */
        fun turnId(amapIcon: Int, roundaboutExit: Int): Int = when (amapIcon) {
            2 -> 19 // left
            3 -> 20 // right
            4 -> 18 // slight left, keep left, ramp left
            5 -> 17 // slight right, keep right, ramp right
            6 -> 21 // sharp left
            7 -> 22 // sharp right
            8, 19 -> 23 // U-turn
            9 -> 16 // straight
            11, 17 -> if (roundaboutExit in 1..10) 73 + roundaboutExit else 53 // enter roundabout (take exit n)
            12, 18 -> 53 // leave roundabout
            15 -> 3 // destination
            else -> 0 // no maneuver: no arrow
        }
    }
}

/** The HUD's on/off choices; on by default on the EZ-60, the only car that shows them. */
internal object MazdaHudSettings {
    private const val PREFS = "diplay_mazda_hud"
    private const val KEY_ENABLED = "turn_by_turn"
    private const val KEY_SONG = "song"

    fun enabled(context: Context): Boolean = MazdaPlatform.isEz60 &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** The CarPlay song on the HUD ([MazdaHudMedia]). */
    fun song(context: Context): Boolean = MazdaPlatform.isEz60 &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SONG, true)

    fun saveSong(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SONG, enabled).apply()
    }
}
