package com.shilapi.xcertplay

import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    @Test fun otherHeadUnitsAreNeverOff() {
        Settings.Global.putInt(context.contentResolver, MazdaPowerWatch.KEY_ACC_STATUS, MazdaPowerWatch.ACC_OFF)
        assertFalse(MazdaPowerWatch.isOff(context))
    }
}
