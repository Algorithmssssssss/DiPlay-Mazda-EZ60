package com.shilapi.xcertplay

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings

/**
 * The EZ-60's power state, from the setting its head unit writes ("vehicle_acc_status": 2 when the car is switched
 * off; 5 then 4 when it starts). Within about a second of switching off, the head unit force-stops DiPlay and
 * sleeps (report of 2026-10-14: the flag at 17:27:00.2, DiPlay's last line at 17:27:01.0, a user_requested exit).
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
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            val now = isOff(context)
            if (now == off) return
            off = now
            runCatching { onChange(now) }
        }
    }

    fun start() {
        runCatching {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY_ACC_STATUS), false, observer)
        }
    }

    fun stop() {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        thread.quitSafely()
    }

    internal companion object {
        const val KEY_ACC_STATUS = "vehicle_acc_status"
        const val ACC_OFF = 2

        fun isOff(context: Context): Boolean = MazdaPlatform.isEz60 && try {
            Settings.Global.getInt(context.contentResolver, KEY_ACC_STATUS, -1) == ACC_OFF
        } catch (_: SecurityException) {
            false
        }
    }
}
