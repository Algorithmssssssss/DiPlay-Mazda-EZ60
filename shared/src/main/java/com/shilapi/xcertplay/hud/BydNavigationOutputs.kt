package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Nonblocking boundary between phone control messages and vendor services. */
object BydNavigationOutputs {
    /** Recover a journaled interrupted output when the app opens, even before a phone reconnects. */
    fun onAppOpened(context: Context) {
        BydBluetoothSuspend.onAppOpened(context)
        BydOemClusterNavi.restoreIfNeeded(context)
        BydDiLink3ClusterOutput.restoreIfNeeded(context)
        com.shilapi.xcertplay.network.WifiScanPause.restoreIfNeeded(context)
        if (BydStandaloneHudOutput.available(context)) start(context)
        // Read the battery early, so a reading is ready when CarPlay identifies (see batteryStatus).
        if (BydOutputSettings.batteryToIphoneActive(context)) BydBatteryStatus.start(context)
        // DiLink 3 creates its cluster map display only once the cluster has projected.
        if (BydOutputSettings.enabled(context)) BydClusterBridge.prepareProjectionDisplay(context.applicationContext)
    }
    fun setDiagnosticHold(hold: Boolean) { BydStandaloneHudOutput.syntheticHold = hold }
    @Volatile private var useStandalone = false
    // The dashboard card keeps the last instruction across a wireless handoff's NoRouteSet.
    private val overlay = TurnGuidanceFeed(BydHudRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
        keepAcrossNoRoute = true,
    ))
    // A head-up display clears when the driver ends the route or arrives, and the iPhone resends guidance about
    // every 2 s while a route runs, even parked (EZ-60 report 13). NoRouteSet hides it only if no route follows
    // within the hide: Google Maps resuming a route mid-drive sends NoRouteSet, Loading and RouteSet in bursts up
    // to 2 s apart, and ending on each left the EZ-60 HUD without its map for minutes (report 23).
    private val headUp = TurnGuidanceFeed(headUpRoute())

    internal fun headUpRoute(nanoTime: () -> Long = System::nanoTime) = BydHudRouteState(
        nanoTime = nanoTime,
        staleRouteNs = 30_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
        keepAcrossNoRoute = true,
        keepManeuversOnEnd = true,
    )
    private val standalone = NavigationOutputWorker("diplay-standalone-output", BydStandaloneNavigationBridge::clear)
    private val hud = NavigationOutputWorker("diplay-hud-output", BydHudBridge::clear)
    private val cluster = NavigationOutputWorker("diplay-cluster-output", BydClusterBridge::clear)
    private val fceCluster = NavigationOutputWorker("diplay-fce-cluster-output", FceClusterBridge::clear)

    /** The host reports whether its CarPlay map window is on the cluster (see [BydClusterMapPause]). */
    fun setClusterMapShown(shown: Boolean) {
        BydClusterMapPause.clusterMapShown = shown
        BydClusterBridge.setMapShown(shown)
    }

    /** The running CarPlay session: told every second whether the cluster currently shows the map. */
    fun setClusterStreamControl(control: (Boolean) -> Unit) { BydClusterMapPause.streamControl = control }

    /** Latest ADB wheel-menu navi mode, or null when it cannot be read. */
    fun clusterNaviMode(): BydClusterNaviMode? = BydClusterMapPause.lastNaviMode

    /** Called whenever the ADB navi mode changes. Pass null to stop following. */
    fun setClusterNaviModeListener(listener: ((BydClusterNaviMode?) -> Unit)?) {
        BydClusterMapPause.onNaviMode = listener
        listener?.invoke(BydClusterMapPause.lastNaviMode)
    }

    fun clearClusterStreamControl(control: (Boolean) -> Unit) {
        if (BydClusterMapPause.streamControl == control) BydClusterMapPause.streamControl = null
    }

    /**
     * The car's battery for the iPhone's vehicle status; starts reading it over adb. The electric
     * vehicle is declared only once a reading is there (see withVehicleStatusFrom).
     */
    fun batteryStatus(context: Context): com.shilapi.xcertplay.transport.VehicleStatusProvider =
        BydBatteryStatus.also { it.start(context) }

    /** The car's wheel speed and gear for the iPhone's dead reckoning; read over adb while asked for. */
    fun wheelSpeed(context: Context): com.shilapi.xcertplay.transport.VehicleSpeedSource =
        BydWheelSpeedSource.attach(context)
    /** Whether the car is in P (read over adb), or null when it cannot tell. Blocking. */
    fun parked(context: Context): Boolean? = BydParkedState.parked(context.applicationContext)

    fun start(context: Context) {
        val app = context.applicationContext
        useStandalone = BydStandaloneHudOutput.available(app)
        if (useStandalone) standalone.start { BydStandaloneNavigationBridge.initialize(app) }
        else {
            hud.start { BydHudBridge.initialize(app) }
            cluster.start { BydClusterBridge.initialize(app) }
        }
        if (FceClusterBridge.available()) fceCluster.start { FceClusterBridge.initialize(app) }
        BydClusterMapPause.initialize(app)
        BydClusterSong.attach(app)
        BydCarPlayCall.attach(app)
    }

    internal fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == ClusterSongState.NOW_PLAYING_UPDATE) {
            BydClusterSong.onFrame(frame)
            return
        }
        if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE) {
            BydCarPlayCall.onFrame(frame)
            return
        }
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        val owned = frame // Iap2Frame is immutable and defensively copies its payload.
        overlay.accept(owned)
        headUp.accept(owned)
        fceCluster.submit { FceClusterBridge.onFrame(owned) }
        if (useStandalone) standalone.submit { BydStandaloneNavigationBridge.onFrame(owned) }
        else {
            hud.submit { BydHudBridge.onFrame(owned) }
            cluster.submit { BydClusterBridge.onFrame(owned) }
        }
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) = overlay.setListener(listener)

    /**
     * Live next-turn state for a head-up display, which must clear when the route ends: Arrived at once, NoRouteSet
     * once no route follows within the hide (5 s).
     */
    fun setHeadUpGuidanceListener(listener: ((ClusterTurnGuidance?) -> Unit)?) = headUp.setListener(listener)

    /** Where the head-up feed reports each change of the iPhone's route state, for diagnostics. */
    fun setHeadUpRouteLog(log: ((String) -> Unit)?) { headUp.stateLog = log }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() {
        overlay.refresh()
        headUp.refresh()
    }

    /** The dashboard song setting changed; applies at once. */
    fun clusterSongChanged(enabled: Boolean) = BydClusterSong.settingChanged(enabled)

    /** The CarPlay call setting changed; applies at once. */
    fun carPlayCallsChanged(enabled: Boolean) = BydCarPlayCall.settingChanged(enabled)

    /** A CarPlay session became active; ready the call card so calls show without the watcher's start delay. */
    fun carPlaySessionStarted() = BydCarPlayCall.sessionStarted()

    /** The iPhone's current call, for the steering wheel's call keys. */
    fun carPlayCall(): CarPlayCallCard? = BydCarPlayCall.current()

    /** The dashboard song's "only when it changes" setting changed; applies at once. */
    fun clusterSongOnChangeChanged() = BydClusterSong.onChangeSettingChanged()

    /** A short note where the song shows on the dashboard; needs the same ADB access as the song. */
    fun dashboardNote(text: String, source: Int? = null) = BydClusterSong.note(text, source)

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    fun endNow(preserveTurnOverlay: Boolean = false) {
        standalone.clear(); hud.clear(); cluster.clear(); fceCluster.clear(); BydClusterSong.end(); BydCarPlayCall.end()
        // Only a wireless session replacement retains the card. Explicit controller close
        // and wired disconnect still clear it immediately.
        if (!preserveTurnOverlay) {
            overlay.clear()
            headUp.clear()
        } else {
            overlay.holdAcrossDrop()
        }
    }
}

/** One consumer's view of the route: its own route state, the guidance last published, and its listener. */
internal class TurnGuidanceFeed(private val route: BydHudRouteState) {
    @Volatile private var listener: ((ClusterTurnGuidance?) -> Unit)? = null
    @Volatile var stateLog: ((String) -> Unit)? = null
    private val lock = Any()
    private var published: ClusterTurnGuidance? = null
    private var loggedState: Int? = null

    fun setListener(next: ((ClusterTurnGuidance?) -> Unit)?) {
        listener = next
        val current = synchronized(lock) { current().also { published = it } }
        next?.invoke(current)
    }

    fun accept(frame: Iap2Frame) {
        var note: String? = null
        val change = synchronized(lock) {
            route.accept(frame.messageId, frame.payload).also {
                val state = route.lastRouteState
                if (state != null && state != loggedState) {
                    loggedState = state
                    note = "HUD: route state=$state maneuvers=${route.knownManeuvers} guiding=${route.currentApple() != null}"
                }
            }
        }
        note?.let { message -> stateLog?.let { runCatching { it(message) } } }
        if (change != BydHudRouteChange.NONE) refresh()
    }

    /** Publishes the guidance if it changed, including a route that went stale without a frame. */
    fun refresh() {
        val next = synchronized(lock) {
            val current = current()
            if (current == published) return
            published = current
            current
        }
        listener?.invoke(next)
    }

    fun clear() {
        synchronized(lock) {
            route.clear()
            loggedState = null
        }
        refresh()
    }

    /** See [BydHudRouteState.holdAcrossDrop]. */
    fun holdAcrossDrop() = synchronized(lock) { route.holdAcrossDrop() }

    fun current(): ClusterTurnGuidance? = synchronized(lock) {
        route.currentApple()?.let { apple ->
            ClusterTurnGuidance.from(BydClusterFrame.from(apple)).copy(
                arrivalEpochSeconds = apple.arrivalEpochSeconds,
                remainingSeconds = apple.remainingSeconds,
                remainingMeters = apple.remainingMeters,
            )
        }
    }
}
