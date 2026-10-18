package com.shilapi.xcertplay

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings

/**
 * The EZ-60's power state, from the setting its head unit writes ("vehicle_acc_status": 2 when the car is switched
 * off; 5 then 4 when it starts). A few seconds after switching off the head unit freezes DiPlay and sleeps, and it
 * force-stops DiPlay when the car starts again (2026-10-14: the flag at 17:27:00.2, DiPlay's last line 0.8 s later;
 * 2026-10-17 on an AR-HUD car: the flag at 18:55:20.6, the last line 2.7 s later, a user_requested exit at the next
 * start, 18:57:35). Nothing DiPlay runs at the end of a session gets to run.
 *
 * The car's own navigation ends its guidance on that change (AccObserver.exitGuide), which also takes it off the HUD.
 * DiPlay does the same through [onChange] so that nothing it set on the car's HUD service (navigation state and
 * focus, the AR-HUD video link) outlives it: a developer on the AR-HUD trim saw the 360 camera app crash after the
 * car was locked for 5 minutes with navigation left on the HUD. [onChange] runs on this watch's own thread, so
 * a busy main thread cannot delay it.
 */
internal class MazdaPowerWatch(private val context: Context, private val onChange: (off: Boolean) -> Unit) {
    private val thread = HandlerThread("diplay-mazda-power").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile var off: Boolean = isOff(context)
        private set
    // Subscribed to every global setting and filtered here, as the EZ-60 probe is: on an AR-HUD car an observer of
    // the key's own URI never heard vehicle_acc_status=2 while the probe's did (2026-10-17, 18:55:20).
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (watches(uri?.lastPathSegment)) check()
        }
    }

    internal val looper get() = thread.looper

    /** Starts watching; returns what the setting reads now, for the session log. */
    fun start(): String {
        runCatching { context.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer) }
        handler.post(::check)
        return runCatching { Settings.Global.getString(context.contentResolver, KEY_ACC_STATUS) }.getOrNull() ?: "unset"
    }

    private fun check() {
        val now = isOff(context)
        if (now == off) return
        off = now
        runCatching { onChange(now) }
    }

    fun stop() {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        thread.quitSafely()
    }

    internal companion object {
        const val KEY_ACC_STATUS = "vehicle_acc_status"
        const val ACC_OFF = 2
        // Written with it at switch-off, the first about 20 ms earlier: each one makes the watch read the state again.
        private val WATCHED = setOf(KEY_ACC_STATUS, "vs_power_status_feedback_value", "VEHICLE_SETTING_OFF_TIME_KEY")

        /** A change to one of the power settings, or one that does not say which setting changed. */
        fun watches(setting: String?): Boolean = setting == null || setting in WATCHED

        fun isOff(context: Context): Boolean = MazdaPlatform.isEz60 && try {
            Settings.Global.getInt(context.contentResolver, KEY_ACC_STATUS, -1) == ACC_OFF
        } catch (_: SecurityException) {
            false
        }
    }
}
