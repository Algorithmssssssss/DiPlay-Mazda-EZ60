package com.shilapi.xcertplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Where the EZ-60's 3D AR-HUD (car_feature_hud_type=3) shows a navigation map, read as the car's own navigation
 * reads it (WT_TinnoveNavigation: CarImpl, ARHudManager, layout_3d_arhud_presentation). Read-only.
 */
internal object MazdaArHudLayout {
    const val GEAR = 826278449 // VirtualCarSensorManager.SENSOR_TYPE_CURRENT_GEAR
    const val HUD_MODE = 826279576 // VirtualCarCabinManager.ID_HUD_MODE_SET
    const val MAP_LAYOUT = 826279579 // VirtualCarCabinManager.ID_HUD_MAP_LAYOUT
    const val GEAR_PARK = 4

    // The car's map on "MeterDisplay#0": 390x350 at (1640,161), or at (70,161) when the driver's layout puts it left.
    const val DISPLAY_NAME = "MeterDisplay#0"
    const val MAP_WIDTH = 390
    const val MAP_HEIGHT = 350
    const val MAP_TOP = 161
    const val MAP_LEFT_ON_RIGHT = 1640
    const val MAP_LEFT_ON_LEFT = 70

    data class Readings(val gear: Int?, val hudMode: Int?, val mapLayout: Int?) {
        override fun toString() = "gear=${gear ?: "unread"} hudMode=${hudMode ?: "unread"} mapLayout=${mapLayout ?: "unread"}"
    }

    enum class Side(val left: Int) { RIGHT(MAP_LEFT_ON_RIGHT), LEFT(MAP_LEFT_ON_LEFT) }

    /** Where the map goes on the display, in its pixels. */
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * The map's box. The car's size is its navigation's own. The AR-HUD projects about the top-left 2016x630 of the
     * display, with the car's speed and gear left of x 1285; the largest box, 735x560 at (1285,60), read best at the
     * glass on 100" AR-HUD cars (EZNav, 2026-10-17), and the large one sits between. The left layout keeps the car's
     * size: where the car then draws its speed and gear is not measured.
     */
    fun box(side: Side, size: MazdaHudSettings.ArMapSize): Box = when {
        side == Side.LEFT -> Box(MAP_LEFT_ON_LEFT, MAP_TOP, MAP_WIDTH, MAP_HEIGHT)
        size == MazdaHudSettings.ArMapSize.LARGEST -> Box(1285, 60, 735, 560)
        size == MazdaHudSettings.ArMapSize.LARGE -> Box(1460, 110, 560, 455)
        else -> Box(MAP_LEFT_ON_RIGHT, MAP_TOP, MAP_WIDTH, MAP_HEIGHT)
    }

    /**
     * The map's side as the car's navigation picks it (ARHudManager.x and .u), or null when the car shows no map: in
     * Park, with the HUD off (mode 0) or in its "easy" mode (2), and whenever a reading fails (the car's navigation
     * also takes a failed gear reading as Park). Mode 3 means custom: 5 with map layout 1, else 4. Then 5 puts the map
     * left; 1 (classic), 4, 6 and any other mode put it right. An AR-HUD car reported mode 5 (2026-10-17).
     */
    fun side(readings: Readings): Side? {
        if (readings.gear == null || readings.gear == GEAR_PARK) return null
        val mode = readings.hudMode ?: return null
        return when (if (mode == 3) (if (readings.mapLayout == 1) 5 else 4) else mode) {
            0, 2 -> null
            5 -> Side.LEFT
            else -> Side.RIGHT
        }
    }

    fun read(): Readings = Readings(
        gear = MazdaAmbientProbe.intValue(GEAR, 0),
        hudMode = MazdaAmbientProbe.intValue(HUD_MODE, 0),
        mapLayout = MazdaAmbientProbe.intValue(MAP_LAYOUT, 0),
    )
}

/**
 * Whether the running session's stream 111 was sized for a display of its own (a cluster, the EZ-60 HUD). Kept per
 * session, not per CarPlay screen: a screen that adopts the running session (after the car's home screen) would
 * otherwise hand stream 111 the main surface, and two decoders on one surface freeze the picture (EZ-60 report 19).
 */
internal object CarPlaySessionCluster {
    @JvmField @Volatile var streamOnDisplay = false
}

/**
 * What this CarPlay connection asked the iPhone for. It outlives the CarPlay screen: opening the car's home screen
 * from CarPlay closes the screen, and the next one adopts the running session without asking again.
 */
internal object MazdaArHudConnection {
    @Volatile var mapRequested = false
    @Volatile var size = MazdaHudSettings.ArMapSize.CAR
}

/**
 * The AR-HUD's video link, switched as the car's navigation switches it (DashboardVideoHelper): ask to show
 * (setVideoStatus 5); on onRequestLVDS(19) show the window and confirm (sendSwitchLVDSFinishEvent 0); on 20 ask
 * again after 200 ms, at most 8 times. Hide with setVideoStatus(6); on onIPStatus(6) remove the window and confirm.
 *
 * As the car's navigation does, every 19 and every 6 is confirmed with the finish event, and the window comes down
 * only for a hide DiPlay sent; DiPlay removes it at once when it hides. A 6 also comes without a hide: about 5 s
 * after each grant on 100" AR-HUD cars, and when the panel revokes the grant on entering Park, about 1 ms before the
 * gear reads P (EZNav, 2026-10-17). On such a 6 the controller reads the car again ([recheck]); out of Park it asks
 * again on its next reading. A grant DiPlay did not ask for means another app (a HUD video player, the car's own
 * navigation guiding) took the link: DiPlay removes its window, leaves the link alone and waits for the next route
 * ([routeEnded]). Any failure turns the map off until [MazdaArHud] is created again (next connection).
 * One thread only: the caller's worker.
 */
internal class MazdaArHudLink(
    private val calls: Calls,
    private val ui: Ui,
    private val log: (String) -> Unit,
    private val now: () -> Long,
    private val schedule: (delayMillis: Long, action: () -> Unit) -> Unit,
    private val recheck: () -> Unit = {},
) {
    interface Calls {
        fun register(): Boolean
        fun unregister()
        fun setVideoStatus(status: Int): Boolean
        fun finish(): Boolean
    }

    interface Ui {
        fun show()
        fun hide()
    }

    enum class State { IDLE, REQUESTING, SHOWN, HIDING, STOOD_DOWN, FAILED }

    var state = State.IDLE
        private set
    private var registered = false
    private var attempts = 0
    private var lastRequestAt = Long.MIN_VALUE / 2
    private var timer = 0

    /** Ask for the link and show the window once the car switches it. Only from [State.IDLE]. */
    fun show() {
        if (state != State.IDLE) return
        if (!registered) {
            registered = calls.register()
            if (!registered) return fail("the car refused the video callback", undo = false)
        }
        attempts = 0
        request()
    }

    /** Give the link back: the route ended, the car went into Park, or its HUD mode shows no map. Window first. */
    fun hide() {
        if (state != State.REQUESTING && state != State.SHOWN) return
        disarm()
        ui.hide()
        state = State.HIDING
        lastRequestAt = now()
        if (!calls.setVideoStatus(HIDE)) return hidden("hide request refused")
        arm(HIDE_TIMEOUT_MILLIS) { hidden("no reply to hide") }
    }

    /** A new route may try again after another app took the link. */
    fun routeEnded() {
        hide()
        if (state == State.STOOD_DOWN) state = State.IDLE
    }

    /** The connection ends: hide at once without waiting for the car, and stop listening. */
    fun close() {
        disarm()
        if (state == State.REQUESTING || state == State.SHOWN || state == State.HIDING) {
            if (state != State.HIDING) calls.setVideoStatus(HIDE)
            ui.hide()
            log("HUD: AR map link given back (connection closed)")
        }
        state = State.FAILED
        if (registered) calls.unregister()
        registered = false
    }

    fun onRequestLvds(value: Int) {
        val awaited = now() - lastRequestAt <= REPLY_GRACE_MILLIS
        when {
            value == READY && state == State.REQUESTING -> {
                disarm()
                state = State.SHOWN
                ui.show()
                log("HUD: AR map link switched on after $attempts request(s); map shown")
            }
            value == RETRY && state == State.REQUESTING -> {
                disarm()
                if (attempts >= MAX_ATTEMPTS) return fail("the car declined $attempts requests", undo = false)
                arm(RETRY_DELAY_MILLIS) { if (state == State.REQUESTING) request() }
            }
            // Our own request's late or repeated replies (the car's navigation retries alongside us).
            awaited || state == State.HIDING -> Unit
            value == READY || value == RETRY -> standDown("another app switched the HUD link (onRequestLVDS $value)")
        }
        if (value == READY) calls.finish()
    }

    fun onIpStatus(value: Int) {
        if (value != IP_HIDDEN) return
        calls.finish()
        when (state) {
            State.HIDING -> hidden(null)
            // Perhaps the panel revoking the grant for Park: let the controller read the gear now.
            State.SHOWN, State.REQUESTING -> recheck()
            else -> Unit
        }
    }

    private fun request() {
        attempts++
        state = State.REQUESTING
        lastRequestAt = now()
        if (!calls.setVideoStatus(SHOW)) return fail("show request refused", undo = false)
        arm(REPLY_TIMEOUT_MILLIS) { fail("no reply from the car", undo = true) }
    }

    private fun hidden(reason: String?) {
        disarm()
        state = State.IDLE
        log("HUD: AR map link given back" + (reason?.let { " ($it)" } ?: ""))
    }

    private fun standDown(reason: String) {
        disarm()
        ui.hide()
        state = State.STOOD_DOWN
        log("HUD: AR map stands down until the route ends: $reason")
    }

    private fun fail(reason: String, undo: Boolean) {
        disarm()
        ui.hide()
        // Without any reply the link may have switched: give it back once. A declined request left it off.
        if (undo) calls.setVideoStatus(HIDE)
        state = State.FAILED
        log("HUD: AR map off until the next connection: $reason")
    }

    private fun arm(delayMillis: Long, action: () -> Unit) {
        val armed = ++timer
        schedule(delayMillis) { if (timer == armed) action() }
    }

    private fun disarm() {
        timer++
    }

    internal companion object {
        const val SHOW = 5
        const val HIDE = 6
        const val READY = 19
        const val RETRY = 20
        const val IP_HIDDEN = 6
        const val MAX_ATTEMPTS = 8
        const val RETRY_DELAY_MILLIS = 200L
        const val REPLY_TIMEOUT_MILLIS = 3_000L
        const val HIDE_TIMEOUT_MILLIS = 2_000L
        const val REPLY_GRACE_MILLIS = 2_000L
    }
}

/**
 * CarPlay's map (or the turn card) on the EZ-60 AR-HUD while CarPlay guides with the HUD's navigation focus, the car
 * is out of Park and its HUD mode shows a map. Switch: [MazdaHudSettings.arMap], off by default. [window] runs on any
 * thread and must post to the main thread itself.
 */
internal class MazdaArHud(
    private val log: (String) -> Unit,
    private val window: Window,
    private val read: () -> MazdaArHudLayout.Readings = MazdaArHudLayout::read,
) {
    interface Window {
        /** The HUD display to draw on exists; checked before asking the car for the link. */
        fun available(): Boolean
        fun show(side: MazdaArHudLayout.Side)
        fun move(side: MazdaArHudLayout.Side)
        fun hide()
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-mazda-ar-hud").apply { isDaemon = true } }
    private val callback: VideoCallback = VideoCallback { code, value ->
        runCatching { worker.execute { if (code == ON_REQUEST_LVDS) link.onRequestLvds(value) else link.onIpStatus(value) } }
    }
    // Worker thread only.
    private var service: IBinder? = null
    private var guiding = false
    private var side: MazdaArHudLayout.Side? = null
    private var readings: MazdaArHudLayout.Readings? = null
    private var noDisplayLogged = false
    private var poll: ScheduledFuture<*>? = null
    private val link: MazdaArHudLink = MazdaArHudLink(
        calls = object : MazdaArHudLink.Calls {
            override fun register(): Boolean = call(REGISTER) { writeStrongBinder(callback) }
            override fun unregister() { call(UNREGISTER) { writeStrongBinder(callback) } }
            override fun setVideoStatus(status: Int): Boolean = call(SET_VIDEO_STATUS) { writeInt(status) }
            override fun finish(): Boolean = call(SWITCH_FINISHED) { writeInt(0) }
        },
        ui = object : MazdaArHudLink.Ui {
            override fun show() { side?.let(window::show) }
            override fun hide() = window.hide()
        },
        log = log,
        now = System::currentTimeMillis,
        schedule = { delay, action -> runCatching { worker.schedule({ action() }, delay, TimeUnit.MILLISECONDS) } },
        // The panel's 6 for Park lands about 1 ms before the gear reads P: read now, and once more just after.
        recheck = {
            runCatching {
                worker.execute(::safeUpdate)
                worker.schedule(Runnable { safeUpdate() }, RECHECK_MILLIS, TimeUnit.MILLISECONDS)
            }
        },
    )

    /** CarPlay guides on the HUD with its navigation focus (true), or stopped or lost the focus (false). */
    fun setGuiding(value: Boolean) {
        runCatching { worker.execute { guidingOnWorker(value) } }
    }

    fun close() {
        runCatching {
            worker.execute {
                poll?.cancel(false)
                link.close()
            }
        }
        worker.shutdown()
    }

    private fun guidingOnWorker(value: Boolean) {
        if (value == guiding) return
        guiding = value
        if (value) {
            poll?.cancel(false)
            poll = worker.scheduleWithFixedDelay(::safeUpdate, 0, POLL_MILLIS, TimeUnit.MILLISECONDS)
        } else {
            poll?.cancel(false)
            poll = null
            link.routeEnded()
        }
    }

    // A throwing task would end the schedule silently; keep following the car instead.
    private fun safeUpdate() {
        if (!guiding) return
        runCatching(::update).onFailure { log("HUD: AR map update failed ${it.javaClass.simpleName}") }
    }

    /** Once a second while guiding: follow the car's gear, HUD mode and map layout. */
    private fun update() {
        val now = runCatching(read).getOrNull() ?: MazdaArHudLayout.Readings(null, null, null)
        if (now != readings) log("HUD: AR-HUD state $now")
        readings = now
        val wanted = MazdaArHudLayout.side(now)
        when {
            wanted == null -> link.hide()
            link.state == MazdaArHudLink.State.IDLE -> {
                if (!window.available()) {
                    if (!noDisplayLogged) log("HUD: AR map: no ${MazdaArHudLayout.DISPLAY_NAME} display; link not requested")
                    noDisplayLogged = true
                    return
                }
                side = wanted
                link.show()
            }
            link.state == MazdaArHudLink.State.SHOWN && wanted != side -> {
                side = wanted
                window.move(wanted)
            }
        }
    }

    /** One allowlisted video-link call on the car's navigation service; false when it failed. */
    private fun call(code: Int, write: Parcel.() -> Unit): Boolean {
        check(code in ALLOWED) { "not an AR-HUD video call: $code" }
        val binder = service?.takeIf { it.isBinderAlive } ?: MazdaHud.navigationService()?.also { service = it }
            ?: return false.also { log("HUD: AR map: car navigation service unavailable") }
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(MazdaHud.NAVIGATION_INTERFACE)
            request.write()
            binder.transact(code, request, reply, 0)
            reply.readException()
            true
        } catch (error: Exception) {
            log("HUD: AR map call $code refused ${error.javaClass.simpleName}: ${error.message}")
            if (!binder.isBinderAlive) service = null
            false
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    /** The car's replies about the video link (IDoubleVideoCallback). */
    private class VideoCallback(private val onReply: (code: Int, value: Int) -> Unit) : Binder() {
        init {
            attachInterface(null, VIDEO_CALLBACK_INTERFACE)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != ON_REQUEST_LVDS && code != ON_IP_STATUS) return super.onTransact(code, data, reply, flags)
            val value = runCatching {
                data.enforceInterface(VIDEO_CALLBACK_INTERFACE)
                data.readInt()
            }.getOrNull()
            if (value != null) onReply(code, value)
            reply?.writeNoException()
            return true
        }
    }

    internal companion object {
        private const val VIDEO_CALLBACK_INTERFACE = "com.incall.serversdk.interactive.callback.IDoubleVideoCallback"
        private const val ON_REQUEST_LVDS = 1
        private const val ON_IP_STATUS = 2

        private const val SWITCH_FINISHED = 1 // sendSwitchLVDSFinishEvent(int)
        private const val SET_VIDEO_STATUS = 72 // setVideoStatus(int): 5 show, 6 hide
        private const val REGISTER = 73 // registerVideoCallback(IDoubleVideoCallback)
        private const val UNREGISTER = 74 // unregisterVideoCallback(IDoubleVideoCallback)
        val ALLOWED = setOf(SWITCH_FINISHED, SET_VIDEO_STATUS, REGISTER, UNREGISTER)

        private const val POLL_MILLIS = 1_000L
        private const val RECHECK_MILLIS = 300L
    }
}
