package com.shilapi.xcertplay

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.util.concurrent.Executors

/**
 * CarPlay's turn-by-turn guidance on the Mazda EZ-60's windshield HUD. The car has no instrument cluster; its
 * HUD draws navigation that apps hand to the head unit's navigation service ("com.incall.double.
 * INTERACTIVE_SERVICE", IDouInteractiveManager), as the car's navigation apps do. A window on the HUD's 800x480
 * display ("MeterDisplay#0") is not projected (EZ-60 probe, 2026-10-11).
 *
 * Only the navigation calls in [ALLOWED] are made: take and give back navigation focus, and send the status,
 * the next turn, the road names and what is left of the route. Nothing else on that service, and no vehicle
 * properties. Calls run on one background thread; [log] gets each step that matters.
 */
internal class MazdaHud(
    private val context: Context,
    private val log: (String) -> Unit,
    /** Whether DiPlay guides on the HUD now: navigation started and the focus is DiPlay's. Worker thread. */
    private val onGuiding: (Boolean) -> Unit = {},
) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "diplay-mazda-hud").apply { isDaemon = true } }
    private val callback = FocusCallback { value ->
        log("HUD: navigation focus changed value=$value")
        runCatching { worker.execute { onFocusOnWorker(value) } }
    }
    // Worker thread only.
    private var service: IBinder? = null
    private var active = false
    private var sent: Frame? = null
    private var sentAtMillis = 0L
    private var routeLongestMeters = 0
    private var sentPercent = -1
    // The car's service says who shows navigation on the HUD; assumed granted on request until it says otherwise.
    private var holdsFocus = true
    private var unavailableLogged = false
    private var guidingPublished = false
    // The car is switched off: HUD navigation stays ended until it is on again ([setCarOff]).
    @Volatile private var carOff = false
    private val state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    init {
        runCatching { worker.execute(::reportUnclearedOnWorker) }
    }

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

    /**
     * The car was switched off (true) or on again (false). Off ends HUD navigation at once, as the car's own
     * navigation does: the head unit force-stops DiPlay about a second later, and nothing DiPlay set on the HUD
     * service may outlive it ([MazdaPowerWatch]). Updates queued before it are skipped. Any thread.
     */
    fun setCarOff(off: Boolean) {
        if (carOff == off) return
        carOff = off
        if (!off) return
        runCatching {
            worker.execute {
                if (active) log("HUD: car switched off; ending HUD navigation as the car's navigation does")
                stopOnWorker()
            }
        }
    }

    /** Tells [onGuiding] the current state again, in order with later changes (a listener joined late). */
    fun republishGuiding() {
        runCatching { worker.execute { runCatching { onGuiding(guidingPublished) } } }
    }

    private fun sendOnWorker(frame: Frame) {
        if (carOff) return
        val binder = service() ?: return
        if (!active) {
            val focus = call(binder, REQUEST_FOCUS) { writeString(context.packageName); writeStrongBinder(callback) }
            val status = call(binder, STATUS) { writeInt(STATUS_NAVIGATING) }
            active = true
            holdsFocus = true
            sent = null
            routeLongestMeters = 0
            sentPercent = -1
            log("HUD: navigation started on the car's service focus=$focus status=$status")
            // Cleared when guidance ends; still set at the next start means DiPlay was stopped while guiding.
            state.edit().putLong(KEY_GUIDING_SINCE, System.currentTimeMillis()).commit()
            publishGuiding()
        }
        // Another navigation app (the car's own included) took the HUD: never overwrite its guidance.
        if (!holdsFocus) return
        val previous = sent
        val now = System.currentTimeMillis()
        val turnOrRoadChanged = previous == null || previous.turnId != frame.turnId ||
            previous.currentRoad != frame.currentRoad || previous.nextRoad != frame.nextRoad
        // Like the car's navigation apps: a new turn or road at once, other changes at most once a second.
        if (frame == previous || (!turnOrRoadChanged && now - sentAtMillis < MIN_INTERVAL_MILLIS)) return
        if (previous == null || previous.currentRoad != frame.currentRoad || previous.nextRoad != frame.nextRoad) {
            call(binder, ROAD) { writeString(frame.currentRoad); writeString(frame.nextRoad) }
        }
        val turn = call(binder, TURN) { writeInt(frame.turnId); writeInt(frame.distanceMeters) }
        call(binder, REMAIN) { writeInt(frame.remainingMeters); writeInt(frame.remainingSeconds) }
        // The car's navigation also fills the HUD's route progress: driven / (driven + remaining).
        routeLongestMeters = maxOf(routeLongestMeters, frame.remainingMeters)
        val percent = progressPercent(routeLongestMeters, frame.remainingMeters)
        if (percent != sentPercent) {
            call(binder, PERCENT) { writeInt(percent) }
            sentPercent = percent
        }
        if (previous == null || previous.turnId != frame.turnId) {
            log("HUD: turn sent turnId=${frame.turnId} distanceM=${frame.distanceMeters} accepted=$turn")
        }
        sent = frame
        sentAtMillis = now
    }

    private fun stopOnWorker() {
        if (!active) return
        val binder = service()
        active = false
        sent = null
        if (binder == null) return publishGuiding()
        // The car's navigation apps clear in this order, then give focus back. Only the focus holder clears:
        // after another app took the HUD, its guidance stays.
        if (holdsFocus) {
            call(binder, REMAIN) { writeInt(0); writeInt(0) }
            call(binder, ROAD) { writeString(""); writeString("") }
            call(binder, TURN) { writeInt(0); writeInt(0) }
            call(binder, STATUS) { writeInt(STATUS_IDLE) }
        }
        call(binder, ABANDON_FOCUS) { writeString(context.packageName); writeStrongBinder(callback) }
        state.edit().remove(KEY_GUIDING_SINCE).commit()
        log(if (holdsFocus) "HUD: guidance ended; HUD cleared" else "HUD: guidance ended; HUD left to the other app")
        publishGuiding()
    }

    private fun reportUnclearedOnWorker() {
        val since = state.getLong(KEY_GUIDING_SINCE, 0L)
        if (since == 0L) return
        log("HUD: the last run was stopped while guiding on the HUD (started " +
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(since)) +
            ") before it could clear the HUD")
        state.edit().remove(KEY_GUIDING_SINCE).commit()
    }

    private fun onFocusOnWorker(value: Int?) {
        if (!active) return
        val holds = value == FOCUS_GAINED
        if (holds == holdsFocus) return
        holdsFocus = holds
        if (holds) sent = null // resend everything to the HUD now it is ours again
        else log("HUD: another navigation app took the HUD; DiPlay stops sending until it is returned")
        publishGuiding()
    }

    private fun publishGuiding() {
        val guiding = active && holdsFocus
        if (guiding == guidingPublished) return
        guidingPublished = guiding
        runCatching { onGuiding(guiding) }
    }

    private fun service(): IBinder? {
        service?.takeIf { it.isBinderAlive }?.let { return it }
        service = null
        val manager = serviceManager(SERVICE_MANAGER) ?: return unavailable("service manager not registered")
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(SERVICE_MANAGER_INTERFACE)
            request.writeString(NAVIGATION_SERVICE)
            manager.transact(GET_SERVER, request, reply, 0)
            reply.readException()
            reply.readStrongBinder()?.also { service = it; unavailableLogged = false }
                ?: unavailable("navigation service not offered")
        } catch (error: Exception) {
            unavailable("lookup failed ${error.javaClass.simpleName}")
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    private fun unavailable(reason: String): IBinder? {
        if (!unavailableLogged) log("HUD: car navigation service unavailable: $reason")
        unavailableLogged = true
        return null
    }

    /** One allowlisted call; false when the service refused it. */
    private fun call(binder: IBinder, code: Int, write: Parcel.() -> Unit): Boolean {
        check(code in ALLOWED) { "not a HUD navigation call: $code" }
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(NAVIGATION_INTERFACE)
            request.write()
            binder.transact(code, request, reply, 0)
            reply.readException()
            true
        } catch (error: Exception) {
            log("HUD: call $code refused ${error.javaClass.simpleName}: ${error.message}")
            if (!binder.isBinderAlive) service = null
            false
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    /** The car tells the focus holder when another navigation app takes or returns the HUD. */
    private class FocusCallback(private val onFocus: (Int?) -> Unit) : Binder() {
        init {
            attachInterface(null, FOCUS_CALLBACK_INTERFACE)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..3) return super.onTransact(code, data, reply, flags)
            val value = runCatching {
                data.enforceInterface(FOCUS_CALLBACK_INTERFACE)
                data.readInt()
            }.getOrNull()
            if (code == ON_FOCUS_CHANGE) onFocus(value)
            reply?.writeNoException()
            return true
        }
    }

    internal companion object {
        // The HUD's 800x480 display and the part the car's own apps draw into (probe test card only).
        const val WIDTH = 800
        const val HEIGHT = 480
        const val VISIBLE_LEFT = 573
        const val VISIBLE_TOP = 167
        const val VISIBLE_RIGHT = 800
        const val VISIBLE_BOTTOM = 355

        private const val SERVICE_MANAGER = "com.incall.SVR_MNG_SERVICE"
        private const val SERVICE_MANAGER_INTERFACE = "com.incall.serversdk.server.ISvrManager"
        private const val GET_SERVER = 6
        private const val NAVIGATION_SERVICE = "com.incall.double.INTERACTIVE_SERVICE"
        const val NAVIGATION_INTERFACE = "com.incall.serversdk.interactive.IDouInteractiveManager"
        private const val FOCUS_CALLBACK_INTERFACE = "com.incall.serversdk.interactive.callback.INaviFocusCallback"
        private const val ON_FOCUS_CHANGE = 3
        private const val FOCUS_GAINED = 1

        private const val STATUS = 22 // sendNavigateStatus(int)
        private const val PERCENT = 50 // sendNavigatePercent(int), route progress 0-100
        private const val TURN = 24 // sendNavigateTurnInfo(int turnId, int distanceMeters)
        private const val ROAD = 26 // sendNavigateRoadInfo(String current, String next)
        private const val REMAIN = 27 // sendNavigateRemainInfo(int meters, int seconds)
        private const val REQUEST_FOCUS = 63 // requestNaviFocus(String package, INaviFocusCallback)
        private const val ABANDON_FOCUS = 64 // abandonNaviFocus(String package, INaviFocusCallback)
        val ALLOWED = setOf(STATUS, TURN, ROAD, REMAIN, PERCENT, REQUEST_FOCUS, ABANDON_FOCUS)

        private const val STATE_PREFS = "diplay_mazda_hud_state"
        private const val KEY_GUIDING_SINCE = "guiding_since"

        // The car's own navigation (WT_TinnoveNavigation): 1 not navigating, 2 navigating, 3 simulating.
        private const val STATUS_NAVIGATING = 2
        private const val STATUS_IDLE = 1
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

        /** Driven share of the route, from the longest remaining distance seen since guidance started. */
        fun progressPercent(longestMeters: Int, remainingMeters: Int): Int =
            if (longestMeters <= 0) 0 else ((longestMeters - remainingMeters) * 100L / longestMeters).toInt().coerceIn(0, 100)

        /**
         * AMap icon (BydClusterFrame) -> the car's HUD turn icon, as the car's own navigation (WT_TinnoveNavigation,
         * DashboardConverter) numbers them; 16, 18, 19 and 3 were confirmed on the car's HUD on 2026-10-11.
         * Roundabouts have one set per direction: counter-clockwise (right-hand traffic) and clockwise (left-hand
         * traffic). The HUD draws no arrow for an unknown number.
         */
        fun turnId(amapIcon: Int, roundaboutExit: Int): Int = when (amapIcon) {
            2 -> 19 // left
            3 -> 20 // right
            4 -> 18 // slight left, keep left, ramp left
            5 -> 17 // slight right, keep right, ramp right
            6 -> 21 // sharp left
            7 -> 22 // sharp right
            8 -> 23 // U-turn to the left (right-hand traffic)
            19 -> 24 // U-turn to the right (left-hand traffic)
            9 -> 16 // straight
            11 -> if (roundaboutExit in 1..10) 41 + roundaboutExit else 53 // counter-clockwise roundabout, exit n
            17 -> if (roundaboutExit in 1..10) 73 + roundaboutExit else 70 // clockwise roundabout, exit n
            12 -> 63 // leave a counter-clockwise roundabout
            18 -> 68 // leave a clockwise roundabout
            15 -> 3 // destination
            else -> 0 // no maneuver: no arrow
        }

        /** The car's navigation service ([NAVIGATION_INTERFACE]), or null; no logging. Not on the main thread. */
        fun navigationService(): IBinder? {
            val manager = serviceManager(SERVICE_MANAGER) ?: return null
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                request.writeInterfaceToken(SERVICE_MANAGER_INTERFACE)
                request.writeString(NAVIGATION_SERVICE)
                manager.transact(GET_SERVER, request, reply, 0)
                reply.readException()
                reply.readStrongBinder()
            } catch (_: Exception) {
                null
            } finally {
                request.recycle()
                reply.recycle()
            }
        }

        @Suppress("DiscouragedPrivateApi", "PrivateApi")
        private fun serviceManager(name: String): IBinder? = runCatching {
            val manager = Class.forName("android.os.ServiceManager")
            val lookup = runCatching { manager.getMethod("checkService", String::class.java) }.getOrNull()
                ?: manager.getMethod("getService", String::class.java)
            lookup.invoke(null, name) as IBinder?
        }.getOrNull()
    }
}

/** The HUD's choices; on by default on the EZ-60, the only car that shows it. */
internal object MazdaHudSettings {
    private const val PREFS = "diplay_mazda_hud"
    private const val KEY_ENABLED = "turn_by_turn"
    private const val KEY_MAP_VIEW = "map_view"

    /** How much map the HUD shows, as the stream size asked of the iPhone in percent of the HUD area. */
    enum class MapView(val scalePercent: Int) { NEAR(75), NORMAL(100), FAR(150) }

    fun mapView(context: Context): MapView = runCatching {
        MapView.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MAP_VIEW, null) ?: MapView.NORMAL.name)
    }.getOrDefault(MapView.NORMAL)

    fun saveMapView(context: Context, view: MapView) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MAP_VIEW, view.name).apply()
    }

    fun enabled(context: Context): Boolean = MazdaPlatform.isEz60 &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** CarPlay's map on the AR-HUD ([MazdaArHud]); off by default: it switches the car's HUD video link. */
    fun arMap(context: Context): Boolean = enabled(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AR_MAP, false)

    fun saveArMap(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AR_MAP, enabled).apply()
    }

    private const val KEY_AR_MAP = "ar_hud_map"
}
