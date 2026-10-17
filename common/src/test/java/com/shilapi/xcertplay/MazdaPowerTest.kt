package com.shilapi.xcertplay

import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The EZ-60 head unit writes vehicle_acc_status: 2 when the car is switched off, 5 then 4 when it starts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MazdaPowerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun tearDown() {
        MazdaPlatform.ez60ForTests = null
    }

    @Test fun onlyTheSwitchedOffValueCountsAsOff() {
        MazdaPlatform.ez60ForTests = true
        assertFalse("No reading is not off", MazdaPowerWatch.isOff(context))
        for ((value, off) in listOf(2 to true, 5 to false, 4 to false, 0 to false)) {
            Settings.Global.putInt(context.contentResolver, MazdaPowerWatch.KEY_ACC_STATUS, value)
            assertTrue("$value", MazdaPowerWatch.isOff(context) == off)
        }
    }

    // An AR-HUD car's head unit wrote vehicle_acc_status=2 and only an observer of all global settings heard it.
    @Test fun switchingOffReachesTheWatchThroughTheGlobalSettings() {
        MazdaPlatform.ez60ForTests = true
        Settings.Global.putInt(context.contentResolver, MazdaPowerWatch.KEY_ACC_STATUS, 4)
        val seen = mutableListOf<Boolean>()
        val watch = MazdaPowerWatch(context) { seen += it }
        try {
            assertTrue(watch.start() == "4")
            Settings.Global.putInt(context.contentResolver, "vehicle_acc_status", 2)
            context.contentResolver.notifyChange(Settings.Global.getUriFor("vehicle_acc_status"), null)
            shadowOf(watch.looper).idle()
            assertTrue("$seen", seen == listOf(true) && watch.off)
            Settings.Global.putInt(context.contentResolver, "vehicle_acc_status", 4)
            context.contentResolver.notifyChange(Settings.Global.getUriFor("vs_power_status_feedback_value"), null)
            shadowOf(watch.looper).idle()
            assertTrue("$seen", seen == listOf(true, false) && !watch.off)
        } finally {
            watch.stop()
        }
    }

    @Test fun onlyThePowerSettingsMakeTheWatchLookAgain() {
        for (name in listOf("vehicle_acc_status", "vs_power_status_feedback_value", "VEHICLE_SETTING_OFF_TIME_KEY", null)) {
            assertTrue("$name", MazdaPowerWatch.watches(name))
        }
        for (name in listOf("screen_brightness", "bluetooth_on", "key_screen_show")) assertFalse(name, MazdaPowerWatch.watches(name))
    }

    @Test fun otherHeadUnitsAreNeverOff() {
        Settings.Global.putInt(context.contentResolver, MazdaPowerWatch.KEY_ACC_STATUS, MazdaPowerWatch.ACC_OFF)
        assertFalse(MazdaPowerWatch.isOff(context))
    }
}
