package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.app.UiModeManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.ImageFormat
import android.graphics.Point
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * The EZ-60 probe: a read-only record of this head unit's screens, decoders, radios, keys and OEM window
 * state, and a timeline of window moves, OEM setting changes and key presses while recording. It gives the
 * Mazda port its on-car measurements and is included in the diagnostic report. Addresses and long
 * identifiers are masked; nothing is sent anywhere.
 */
internal object MazdaCockpitProbe {
    private const val PREFS = "diplay_ez60_probe"
    private const val KEY_RECORDING = "recording"
    private const val DIRECTORY = "ez60-probe"
    private const val SNAPSHOT_FILE = "snapshot.txt"
    private const val EVENTS_FILE = "events.txt"
    private const val CRASH_FILE = "crashes.txt"
    private const val MAX_CRASH_BYTES = 64 * 1024
    private const val MAX_CRASH_LINES = 120
    private const val SESSION_LOG_LINES = 300
    private const val SETUP_LINES = 150
    private const val VIDEO_STATS_LINES = 240
    private val setupLine = Regex("(?i)MHz|GHz|frequency|channel|P2P|Wi-Fi|hotspot|station|Starting CarPlay|decoder|fps|Display|transport|session")
    private val periodicLine = Regex("iAP tunnel|video stats|audio stats|decoder stats|Receive:|THEME_DIAGNOSTIC")
    private const val MAX_EVENT_LINES = 1500
    private const val MAX_SETTINGS_PER_TABLE = 200
    private const val MAX_PROPERTIES = 500
    private val MIMES = setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)

    // Panel sizes of the 5120x1440 cockpit (driver panel, with the vehicle panel, a whole half, the whole screen).
    private val VIDEO_TARGETS = listOf(
        Triple(2496, 1080, 30), Triple(2496, 1080, 60), Triple(1728, 1080, 60),
        Triple(2560, 1440, 60), Triple(5120, 1440, 30), Triple(5120, 1440, 60),
    )
    private val CONFIGURE_SIZES = listOf(2496 to 1080, 5120 to 1440)
    private val settingName = Regex("(?i)screen|vpa|cui|split|dual|passenger|copilot|driver|left|right|day|night|theme|hud|carplay|projection")
    // OEM telemetry and device lists carry phone names and addresses: record that they changed, not the value.
    private val privateSetting = Regex("(?i)buried|bluetooth_devices|address|account|password|passphrase|ssid|token")
    private val projectionPackage = Regex("(?i)carplay|carlife|carlink|hicar|ucar|projection|mirror|easyconn|cast")
    private val propertyPrefixes = listOf(
        "ro.product.", "ro.build.", "ro.board.", "ro.hardware", "ro.soc.", "ro.sf.", "ro.surface_flinger.",
        "ro.vendor.", "ro.odm.", "ro.boot.hardware", "ro.config.", "ro.car", "vendor.display", "persist.vendor.display",
        "ro.mediatek", "ro.vendor.mtk",
    )
    private val propertyKeyword = Regex(
        "(?i)display|lcd|screen|panel|dual|split|multi|hwc|surface|vehicle|mazda|changan|camzd|wtcl|j90|ivi|cockpit|" +
            "carplay|carlink|hicar|projection|codec|video|wifi|wlan|p2p|bluetooth|usb|audio",
    )
    private val privateProperty = Regex("(?i)serial|imei|meid|iccid|mac|addr|ssid|pass|key|token|secret|cert|sign|uuid|ether|\\.id$")
    private val propertyLine = Regex("^\\[([^]]+)]: \\[(.*)]$")
    private val windowConfiguration = Regex("winConfig=\\{[^}]*\\}|mWindowingMode=\\w+")

    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "diplay-ez60-probe").apply { isDaemon = true } }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val windows = WeakHashMap<Activity, WindowWatch>()
    @Volatile private var recording: Boolean? = null
    private var settingsObserver: ContentObserver? = null
    private var usbReceiver: BroadcastReceiver? = null
    @Volatile private var pendingLines = 0
    @Volatile private var crashRecorderInstalled = false

    private class WindowWatch(val tag: String) {
        var last: String? = null
        var listener: ViewTreeObserver.OnGlobalLayoutListener? = null
    }

    // ---- Recording ----

    fun isRecording(context: Context): Boolean = recording
        ?: prefs(context).getBoolean(KEY_RECORDING, false).also { recording = it }

    fun setRecording(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (enabled == isRecording(app)) return
        prefs(app).edit().putBoolean(KEY_RECORDING, enabled).apply()
        if (enabled) {
            writer.execute { File(directory(app), EVENTS_FILE).delete() }
            recording = true
            append(app, "probe", "recording started")
            startWatching(app)
            for ((activity, watch) in windows.entries.toList()) {
                watch.last = windowLine(activity)
                append(app, watch.tag, "window ${watch.last}")
            }
        } else {
            append(app, "probe", "recording stopped")
            recording = false
            settingsObserver?.let { app.contentResolver.unregisterContentObserver(it) }
            settingsObserver = null
            usbReceiver?.let { runCatching { app.unregisterReceiver(it) } }
            usbReceiver = null
        }
    }

    /** Follows [activity]'s window position, size and display while recording. Call from onCreate. */
    fun attach(activity: Activity, tag: String, restored: Boolean) {
        installCrashRecorder(activity)
        if (windows.containsKey(activity)) return
        val watch = WindowWatch(tag)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (!isRecording(activity)) return@OnGlobalLayoutListener
            val now = windowLine(activity)
            if (now != watch.last) {
                watch.last = now
                note(activity, tag, "window $now")
            }
        }
        watch.listener = listener
        windows[activity] = watch
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        if (isRecording(activity)) {
            startWatching(activity.applicationContext)
            note(activity, tag, "created restored=$restored")
        }
    }

    fun detach(activity: Activity) {
        val watch = windows.remove(activity) ?: return
        watch.listener?.let { activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        note(activity, watch.tag, "destroyed changingConfigurations=${activity.isChangingConfigurations} finishing=${activity.isFinishing}")
    }

    fun configuration(activity: Activity, tag: String, configuration: Configuration) {
        if (!isRecording(activity)) return
        note(activity, tag, "configuration ${configurationLine(configuration)} window ${windowLine(activity)}")
    }

    fun key(context: Context, tag: String, event: KeyEvent) {
        if (!isRecording(context)) return
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return
        val device = InputDevice.getDevice(event.deviceId)
        note(context, tag, "key ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"} " +
            "${KeyEvent.keyCodeToString(event.keyCode)}(${event.keyCode}) scan=${event.scanCode} " +
            "repeat=${event.repeatCount} source=0x${Integer.toHexString(event.source)} " +
            "device=${event.deviceId}:${device?.name}")
    }

    /** Adds [message] to the timeline while recording. */
    fun note(context: Context, tag: String, message: String) {
        if (isRecording(context)) append(context.applicationContext, tag, message)
    }

    private fun append(app: Context, tag: String, message: String) {
        val line = "${time()} [$tag] ${DiagnosticRedactor.mask(message)}"
        writer.execute {
            runCatching {
                val file = File(directory(app), EVENTS_FILE)
                file.appendText(line + "\n")
                if (++pendingLines >= 100) {
                    pendingLines = 0
                    val lines = file.readLines()
                    if (lines.size > MAX_EVENT_LINES) file.writeText(lines.takeLast(MAX_EVENT_LINES).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    // OEM screen state, anything else the head unit changes while the driver tries things, and USB devices.
    private fun startWatching(app: Context) {
        if (settingsObserver != null) return
        val resolver = app.contentResolver
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val segments = uri?.pathSegments ?: return
                if (segments.size < 2) return
                val value = if (privateSetting.containsMatchIn(segments[1])) "(changed)" else readSetting(resolver, segments[0], segments[1])
                note(app, "settings", "${segments[0]}.${segments[1]}=$value")
            }
        }
        for (uri in listOf(Settings.Global.CONTENT_URI, Settings.System.CONTENT_URI, Settings.Secure.CONTENT_URI)) {
            runCatching { resolver.registerContentObserver(uri, true, observer) }
        }
        settingsObserver = observer
        // An iPhone on the USB port, or nothing when the port is charge-only.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                val what = if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) "attached" else "detached"
                note(context, "usb", "$what ${device?.let(::usbLine) ?: "unknown device"}")
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess { usbReceiver = receiver }
    }

    private fun usbLine(device: UsbDevice): String =
        "${device.deviceName} vendor=0x${"%04x".format(Locale.US, device.vendorId)} " +
            "product=0x${"%04x".format(Locale.US, device.productId)} class=${device.deviceClass} " +
            "interfaces=${device.interfaceCount}" + if (device.vendorId == 0x05ac) " (Apple)" else ""

    /**
     * Keeps the stack trace of an uncaught exception for the probe report, recording or not, then lets
     * the app crash as before. Without ADB this is the only way a crash on the car reaches the report.
     */
    @Synchronized
    private fun installCrashRecorder(context: Context) {
        if (crashRecorderInstalled) return
        crashRecorderInstalled = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val file = File(directory(app), CRASH_FILE)
                if (file.length() > MAX_CRASH_BYTES) file.delete()
                val trace = android.util.Log.getStackTraceString(error).lineSequence().take(MAX_CRASH_LINES)
                    .joinToString("\n") { DiagnosticRedactor.mask(it) }
                file.appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "thread=${thread.name}\n$trace\n\n")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    // ---- Snapshot ----

    /** Window facts are read on the calling (main) thread, the rest in the background; [done] runs on main. */
    fun snapshot(activity: Activity, done: () -> Unit) {
        val app = activity.applicationContext
        val window = StringBuilder().also { out -> part(out) { windowSection(activity, out) } }
        Thread({
            val text = StringBuilder().apply {
                appendLine("=== DiPlay EZ-60 probe ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} " +
                    "app=${app.packageName} ${version(app)} ===")
                section("Platform") { platform(app, this) }
                section("Window (this screen)") { append(window) }
                section("Displays") { displays(app, this) }
                section("OEM settings") { settingsScan(app, this) }
                section("Video decoders") { video(this) }
                section("Connection") { connection(app, this) }
                section("Audio and location") { audioAndLocation(app, this) }
                section("Day/night and input") { dayNightAndInput(app, this) }
                section("Apps") { apps(app, this) }
            }.lineSequence().joinToString("\n") { DiagnosticRedactor.mask(it) }
            runCatching { File(directory(app), SNAPSHOT_FILE).writeText(text) }
            main.post { done() }
        }, "diplay-ez60-probe-snapshot").start()
    }

    /** The last snapshot and the recorded timeline, for the viewer, the phone page and the diagnostic report. */
    fun report(context: Context): String = buildString {
        val directory = directory(context)
        val snapshot = File(directory, SNAPSHOT_FILE)
        appendLine(if (snapshot.isFile) snapshot.readText().trimEnd()
            else "No EZ-60 probe snapshot yet (Settings → Diagnostics → Run EZ-60 probe).")
        appendLine()
        appendLine("--- EZ-60 probe events (recording=${isRecording(context)}) ---")
        val events = File(directory, EVENTS_FILE)
        append(if (events.isFile) events.readText() else "No recorded events.\n")
        appendLine()
        appendLine("--- EZ-60 probe crashes (most recent last) ---")
        val crashes = File(directory, CRASH_FILE)
        append(if (crashes.isFile) crashes.readText() else "No crash recorded.\n")
    }

    /**
     * [report] plus what the full diagnostic export adds on its own: recent process exits and the end of
     * the session log. For the probe viewer and the phone page, which are the only way out without ADB.
     */
    fun relayReport(context: Context): String = buildString {
        append(report(context))
        appendLine()
        appendLine("--- Recent process exits ---")
        appendLine(ProcessExitDiagnostics.report(context))
        appendLine()
        appendLine("--- Last display negotiation ---")
        appendLine(DisplayDiagnosticSnapshot.report(context))
        val log = File(context.filesDir, "logs/diplay.log")
        val lines = if (log.isFile) log.readLines() else emptyList()
        appendLine()
        // The tail is mostly periodic stats; how the session started (picture, decoder, Wi-Fi channel) is earlier.
        appendLine("--- Session log: setup and Wi-Fi lines ---")
        lines.filter { setupLine.containsMatchIn(it) && !periodicLine.containsMatchIn(it) }.take(SETUP_LINES)
            .forEach { line -> DiagnosticRedactor.redact(line)?.let(::appendLine) }
        appendLine()
        // One line per 5 s: how smooth the whole session was, which the tail alone often misses.
        appendLine("--- Video stats (whole session) ---")
        lines.filter { "video stats" in it }.takeLast(VIDEO_STATS_LINES)
            .forEach { line -> DiagnosticRedactor.redact(line)?.let(::appendLine) }
        appendLine()
        appendLine("--- Session log (last $SESSION_LOG_LINES lines) ---")
        if (lines.isEmpty()) appendLine("No session log.")
        else lines.takeLast(SESSION_LOG_LINES).forEach { line -> DiagnosticRedactor.redact(line)?.let(::appendLine) }
    }

    private fun StringBuilder.section(title: String, build: StringBuilder.() -> Unit) {
        appendLine()
        appendLine("--- $title ---")
        part(this) { build() }
    }

    private fun part(out: StringBuilder, build: () -> Unit) {
        try {
            build()
        } catch (error: Exception) {
            out.appendLine("unavailable: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun platform(context: Context, out: StringBuilder) {
        out.appendLine("manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} product=${Build.PRODUCT} " +
            "device=${Build.DEVICE} board=${Build.BOARD} hardware=${Build.HARDWARE}")
        out.appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} patch=${Build.VERSION.SECURITY_PATCH} " +
            "incremental=${Build.VERSION.INCREMENTAL}")
        out.appendLine("display=${Build.DISPLAY}")
        out.appendLine("fingerprint=${Build.FINGERPRINT}")
        if (Build.VERSION.SDK_INT >= 31) out.appendLine("soc=${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        val detection = MazdaPlatform.detect(context)
        out.appendLine("mazdaPlatform likely=${detection.likely} buildMatch=${detection.buildMatch} oemKeys=${detection.oemKeysPresent}")
        val features = context.packageManager.systemAvailableFeatures.mapNotNull { it.name }.sorted()
        out.appendLine("features(${features.size})=${features.joinToString(",")}")
        out.appendLine("properties:")
        properties().forEach { out.appendLine("  $it") }
    }

    internal fun interestingProperty(name: String): Boolean = !privateProperty.containsMatchIn(name) &&
        (propertyPrefixes.any(name::startsWith) || propertyKeyword.containsMatchIn(name))

    private fun properties(): List<String> {
        val process = ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.waitFor()
        return lines.mapNotNull { propertyLine.find(it) }
            .filter { interestingProperty(it.groupValues[1]) }
            .map { "${it.groupValues[1]}=${it.groupValues[2]}" }
            .take(MAX_PROPERTIES)
    }

    private fun windowSection(activity: Activity, out: StringBuilder) {
        out.appendLine(windowLine(activity))
        if (Build.VERSION.SDK_INT >= 30) {
            val manager = activity.windowManager
            out.appendLine("currentWindowMetrics=${manager.currentWindowMetrics.bounds.toShortString()} " +
                "maximumWindowMetrics=${manager.maximumWindowMetrics.bounds.toShortString()}")
        }
        val metrics = activity.resources.displayMetrics
        out.appendLine("resources ${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.densityDpi} " +
            "xdpi=${metrics.xdpi} ydpi=${metrics.ydpi}")
        out.appendLine("configuration=${activity.resources.configuration}")
        activity.window.decorView.rootWindowInsets?.let { out.appendLine("insets ${insetsLine(it)}") }
        activity.getSystemService(ActivityManager::class.java)?.appTasks?.forEachIndexed { index, task ->
            out.appendLine("task[$index] ${runCatching { task.taskInfo.toString() }.getOrElse { "unavailable" }}")
        }
        val keys = listOf(MazdaPlatform.KEY_SCREEN_SHOW, MazdaPlatform.KEY_VPA_LEFT, MazdaPlatform.KEY_VPA_RIGHT)
            .associateWith { readSetting(activity.contentResolver, "global", it) }
        keys.forEach { (key, value) -> out.appendLine("global.$key=$value") }
        out.appendLine("cockpitPanel ${cockpitPanelLine(activity, keys)}")
    }

    @Suppress("DEPRECATION")
    private fun cockpitPanelLine(activity: Activity, keys: Map<String, String>): String {
        val decor = activity.window.decorView
        val location = IntArray(2).also(decor::getLocationOnScreen)
        val real = Point().also(activity.windowManager.defaultDisplay::getRealSize)
        val side = MazdaCockpitGeometry.side(location[0], decor.width, real.x)
        fun key(name: String) = keys[name]?.toIntOrNull() ?: 0
        val narrow = MazdaCockpitGeometry.narrow(side, key(MazdaPlatform.KEY_SCREEN_SHOW), key(MazdaPlatform.KEY_VPA_LEFT),
            key(MazdaPlatform.KEY_VPA_RIGHT))
        val panel = MazdaCockpitGeometry.panel(side, narrow)
        return "side=$side narrow=$narrow panel=[${panel.left},${panel.top}][${panel.right},${panel.bottom}] " +
            "${panel.width}x${panel.height} (window coordinates)"
    }

    @Suppress("DEPRECATION")
    private fun windowLine(activity: Activity): String {
        val decor = activity.window.decorView
        val location = IntArray(2).also(decor::getLocationOnScreen)
        val display = activity.windowManager.defaultDisplay
        val real = Point().also(display::getRealSize)
        val bounds = if (Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds.toShortString()
            else "${decor.width}x${decor.height}"
        val half = when {
            real.x <= 0 || decor.width <= 0 -> "?"
            decor.width >= real.x * 9 / 10 -> "full"
            location[0] + decor.width / 2 >= real.x / 2 -> "right"
            else -> "left"
        }
        return "display=${display.displayId} real=${real.x}x${real.y} bounds=$bounds " +
            "decor=${decor.width}x${decor.height}@${location[0]},${location[1]} half=$half " +
            "multiWindow=${activity.isInMultiWindowMode} cockpit=${MzdCarAppLayout.isCockpitWindow(activity)}"
    }

    private fun configurationLine(configuration: Configuration): String {
        val night = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val windowing = windowConfiguration.find(configuration.toString())?.value ?: "-"
        return "density=${configuration.densityDpi} sw=${configuration.smallestScreenWidthDp} " +
            "w=${configuration.screenWidthDp} h=${configuration.screenHeightDp} orientation=${configuration.orientation} " +
            "night=$night uiType=${configuration.uiMode and Configuration.UI_MODE_TYPE_MASK} $windowing"
    }

    private fun insetsLine(insets: WindowInsets): String {
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets)
        val types = listOf(
            "status" to WindowInsetsCompat.Type.statusBars(), "navigation" to WindowInsetsCompat.Type.navigationBars(),
            "caption" to WindowInsetsCompat.Type.captionBar(), "cutout" to WindowInsetsCompat.Type.displayCutout(),
            "ime" to WindowInsetsCompat.Type.ime(), "gestures" to WindowInsetsCompat.Type.systemGestures(),
            "mandatoryGestures" to WindowInsetsCompat.Type.mandatorySystemGestures(),
            "tappable" to WindowInsetsCompat.Type.tappableElement(),
        )
        return types.joinToString(" ") { (name, type) ->
            val inset = compat.getInsets(type)
            "$name=${inset.left},${inset.top},${inset.right},${inset.bottom}"
        } + if (Build.VERSION.SDK_INT >= 30) " raw=$insets" else ""
    }

    @Suppress("DEPRECATION")
    private fun displays(context: Context, out: StringBuilder) {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return
        for (display in manager.displays) {
            val real = DisplayMetrics().also(display::getRealMetrics)
            val widthMm = if (real.xdpi > 0) real.widthPixels / real.xdpi * 25.4f else 0f
            val heightMm = if (real.ydpi > 0) real.heightPixels / real.ydpi * 25.4f else 0f
            out.appendLine("display ${display.displayId} name=${display.name} flags=0x${Integer.toHexString(display.flags)} " +
                "state=${display.state} rotation=${display.rotation} real=${real.widthPixels}x${real.heightPixels} " +
                "density=${real.densityDpi} xdpi=${real.xdpi} ydpi=${real.ydpi} " +
                "sizeFromDpi=${widthMm.toInt()}x${heightMm.toInt()}mm refresh=${display.refreshRate}")
            out.appendLine("  modes=${display.supportedModes.joinToString { "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" }} " +
                "current=${display.mode.modeId}")
            out.appendLine("  info=$display")
        }
    }

    private fun settingsScan(context: Context, out: StringBuilder) {
        val tables = listOf("global" to Settings.Global.CONTENT_URI, "system" to Settings.System.CONTENT_URI,
            "secure" to Settings.Secure.CONTENT_URI)
        for ((table, uri) in tables) {
            val rows = runCatching {
                context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { cursor ->
                    var total = 0
                    val matches = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        total++
                        val name = cursor.getString(0) ?: continue
                        if (settingName.containsMatchIn(name)) {
                            matches += "$table.$name=${if (privateSetting.containsMatchIn(name)) "(set)" else cursor.getString(1)}"
                        }
                    }
                    total to matches
                }
            }
            val result = rows.getOrNull()
            when {
                rows.isFailure -> out.appendLine("$table: blocked (${rows.exceptionOrNull()?.javaClass?.simpleName})")
                result == null -> out.appendLine("$table: no cursor")
                else -> {
                    out.appendLine("$table: rows=${result.first} matching=${result.second.size}")
                    result.second.sorted().take(MAX_SETTINGS_PER_TABLE).forEach { out.appendLine("  $it") }
                }
            }
        }
    }

    private fun readSetting(resolver: ContentResolver, table: String, name: String): String = try {
        when (table) {
            "global" -> Settings.Global.getString(resolver, name)
            "system" -> Settings.System.getString(resolver, name)
            "secure" -> Settings.Secure.getString(resolver, name)
            else -> null
        } ?: "(unset)"
    } catch (_: SecurityException) {
        "(blocked)"
    }

    private fun video(out: StringBuilder) {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type in MIMES } }
        for (info in codecs) for (mime in info.supportedTypes.filter { it in MIMES }) {
            val capabilities = info.getCapabilitiesForType(mime)
            val video = capabilities.videoCapabilities ?: continue
            // Secure decoders need a crypto session, and aliases repeat another entry.
            val trial = (Build.VERSION.SDK_INT < 29 || (info.isHardwareAccelerated && !info.isAlias)) &&
                !info.name.endsWith(".secure")
            out.appendLine("decoder ${info.name} $mime" +
                (if (Build.VERSION.SDK_INT >= 29) " hardware=${info.isHardwareAccelerated} vendor=${info.isVendor} alias=${info.isAlias}" else "") +
                " instances=${capabilities.maxSupportedInstances}")
            out.appendLine("  widths=${video.supportedWidths} heights=${video.supportedHeights} " +
                "align=${video.widthAlignment}x${video.heightAlignment} fps=${video.supportedFrameRates} bitrate=${video.bitrateRange}")
            val maxLevel = capabilities.profileLevels.maxOfOrNull { it.level } ?: 0
            out.appendLine("  lowLatency=${capabilities.isFeatureSupported("low-latency")} " +
                "adaptive=${capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)} " +
                "tunneled=${capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)} " +
                "maxLevel=0x${Integer.toHexString(maxLevel)}")
            if (Build.VERSION.SDK_INT >= 29) {
                video.supportedPerformancePoints?.let { out.appendLine("  performancePoints=${it.joinToString()}") }
            }
            for ((width, height, fps) in VIDEO_TARGETS) {
                val supported = runCatching { video.areSizeAndRateSupported(width, height, fps.toDouble()) }.getOrDefault(false)
                val achievable = runCatching { video.getAchievableFrameRatesFor(width, height) }.getOrNull()
                out.appendLine("  ${width}x$height@$fps supported=$supported achievable=$achievable")
            }
            if (trial) for ((width, height) in CONFIGURE_SIZES) {
                out.appendLine("  start ${width}x$height plain=${tryDecoder(info.name, mime, width, height, false)} " +
                    "tuned=${tryDecoder(info.name, mime, width, height, true)}")
            }
        }
    }

    /** Configures and starts [name] on an off-screen surface, as DiPlay's decoder does, without any frames. */
    private fun tryDecoder(name: String, mime: String, width: Int, height: Int, tuned: Boolean): String {
        var reader: ImageReader? = null
        var codec: MediaCodec? = null
        return try {
            reader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 2)
            codec = MediaCodec.createByCodecName(name)
            val format = MediaFormat.createVideoFormat(mime, width, height)
            if (tuned) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
                if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, reader.surface, null, 0)
            codec.start()
            "ok"
        } catch (error: Exception) {
            val detail = (error as? MediaCodec.CodecException)?.diagnosticInfo
            error.javaClass.simpleName + (detail?.let { "($it)" } ?: "")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { reader?.close() }
        }
    }

    private fun connection(context: Context, out: StringBuilder) {
        val packages = context.packageManager
        context.applicationContext.getSystemService(WifiManager::class.java)?.let { wifi ->
            val parts = mutableListOf("enabled=${wifi.isWifiEnabled}", "5GHz=${wifi.is5GHzBandSupported}", "p2p=${wifi.isP2pSupported}")
            if (Build.VERSION.SDK_INT >= 30) parts += listOf(
                "6GHz=${wifi.is6GHzBandSupported}", "staAp=${wifi.isStaApConcurrencySupported}",
                "11ac=${wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AC)}",
                "11ax=${wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX)}",
            )
            if (Build.VERSION.SDK_INT >= 31) parts += listOf(
                "bridgedAp=${wifi.isBridgedApConcurrencySupported}", "staBridgedAp=${wifi.isStaBridgedApConcurrencySupported}",
                "localOnlySta=${wifi.isStaConcurrencyForLocalOnlyConnectionsSupported}",
            )
            out.appendLine("wifi ${parts.joinToString(" ")}")
        }
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { network ->
            runCatching {
                val addresses = network.inetAddresses.toList()
                out.appendLine("interface ${network.name} up=${network.isUp} virtual=${network.isVirtual} " +
                    "pointToPoint=${network.isPointToPoint} mtu=${network.mtu} " +
                    "ipv4=${addresses.any { it is Inet4Address }} ipv6=${addresses.any { it is Inet6Address }}")
            }
        }
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
        out.appendLine("bluetooth present=${bluetooth != null} enabled=${runCatching { bluetooth?.isEnabled }.getOrNull()} " +
            "classic=${packages.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)} " +
            "le=${packages.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}")
        // Wired CarPlay runs through a VPN: whether Android already allows it, asks, or refuses.
        val vpn = runCatching { android.net.VpnService.prepare(context) }
        val noConfigVpn = runCatching {
            context.getSystemService(android.os.UserManager::class.java)
                ?.hasUserRestriction(android.os.UserManager.DISALLOW_CONFIG_VPN)
        }.getOrNull()
        out.appendLine("vpn consent=" + vpn.fold(
            { it?.component?.flattenToShortString()?.let { screen -> "asks via $screen" } ?: "already allowed" },
            { "error ${it.javaClass.simpleName}: ${it.message}" },
        ) + " noConfigVpn=$noConfigVpn")
        out.appendLine("usb host=${packages.hasSystemFeature(PackageManager.FEATURE_USB_HOST)} " +
            "accessory=${packages.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY)}")
        context.getSystemService(UsbManager::class.java)?.deviceList?.values?.forEach { device ->
            out.appendLine("usb device ${usbLine(device)}")
        }
    }

    private fun audioAndLocation(context: Context, out: StringBuilder) {
        val packages = context.packageManager
        context.getSystemService(AudioManager::class.java)?.let { audio ->
            out.appendLine("audio sampleRate=${audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} " +
                "framesPerBuffer=${audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)} " +
                "fixedVolume=${audio.isVolumeFixed} mode=${audio.mode} " +
                "automotive=${packages.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)} " +
                "lowLatency=${packages.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)}")
            for ((label, flag) in listOf("output" to AudioManager.GET_DEVICES_OUTPUTS, "input" to AudioManager.GET_DEVICES_INPUTS)) {
                audio.getDevices(flag).forEach { device ->
                    out.appendLine("$label type=${audioType(device.type)} product=${device.productName} " +
                        (if (Build.VERSION.SDK_INT >= 28) "address=${device.address} " else "") +
                        "channels=${device.channelCounts.joinToString(",")} rates=${device.sampleRates.joinToString(",")}")
                }
            }
        }
        context.getSystemService(LocationManager::class.java)?.let { location ->
            out.appendLine("location providers=${location.allProviders} " +
                "gps=${runCatching { location.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()}" +
                if (Build.VERSION.SDK_INT >= 28) " gnss=${location.gnssHardwareModelName} year=${location.gnssYearOfHardware}" else "")
        }
    }

    private fun audioType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "mic"
        AudioDeviceInfo.TYPE_BUS -> "bus"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt-a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt-sco"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb-device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        AudioDeviceInfo.TYPE_FM_TUNER -> "fm-tuner"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote-submix"
        else -> type.toString()
    } + "($type)"

    private fun dayNightAndInput(context: Context, out: StringBuilder) {
        val configuration = context.resources.configuration
        val uiMode = context.getSystemService(UiModeManager::class.java)
        val light = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT)
        out.appendLine("uiMode ${configurationLine(configuration)} managerNight=${uiMode?.nightMode} " +
            "modeType=${uiMode?.currentModeType} lightSensor=${light?.name}")
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            val x = device.getMotionRange(MotionEvent.AXIS_X)
            val y = device.getMotionRange(MotionEvent.AXIS_Y)
            out.appendLine("input $id ${device.name} sources=${sourceNames(device.sources)} " +
                "vendor=0x${"%04x".format(Locale.US, device.vendorId)} product=0x${"%04x".format(Locale.US, device.productId)} " +
                "virtual=${device.isVirtual}" + (if (Build.VERSION.SDK_INT >= 29) " external=${device.isExternal}" else "") +
                if (x != null && y != null) " x=${x.min}..${x.max} y=${y.min}..${y.max}" else "")
        }
    }

    private fun sourceNames(sources: Int): String = listOf(
        InputDevice.SOURCE_TOUCHSCREEN to "touchscreen", InputDevice.SOURCE_KEYBOARD to "keyboard",
        InputDevice.SOURCE_DPAD to "dpad", 0x00400000 to "rotary", InputDevice.SOURCE_MOUSE to "mouse",
        InputDevice.SOURCE_TOUCHPAD to "touchpad", InputDevice.SOURCE_JOYSTICK to "joystick",
        InputDevice.SOURCE_GAMEPAD to "gamepad",
    ).filter { (bits, _) -> (sources and bits) == bits }.joinToString("+") { it.second }
        .ifEmpty { "0x${Integer.toHexString(sources)}" }

    @Suppress("DEPRECATION")
    private fun apps(context: Context, out: StringBuilder) {
        val packages = context.packageManager
        fun query(category: String) = packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            .map { it.activityInfo.packageName }.distinct().sorted()
        val launchers = query(Intent.CATEGORY_LAUNCHER)
        out.appendLine("launcher apps(${launchers.size})=${launchers.joinToString(",")}")
        out.appendLine("projection-like=${launchers.filter(projectionPackage::containsMatchIn)}")
        out.appendLine("home apps=${query(Intent.CATEGORY_HOME)}")
    }

    private fun version(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun time() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY).apply { mkdirs() }
}
