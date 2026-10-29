package com.shilapi.xcertplay

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The EZ-60 closes the app on a three-finger swipe, so DiPlay's settings swipe uses two fingers there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Ez60SettingsSwipeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After fun tearDown() {
        MazdaPlatform.ez60ForTests = null
    }

    @Test fun otherHeadUnitsKeepThreeFingersAndEveryChoice() {
        MazdaPlatform.ez60ForTests = false
        assertEquals(3, AirPlayPersistence.loadSettingsGestureFingers(context))
        assertEquals(listOf(0, 2, 3, 4), AirPlayPersistence.settingsGestureFingerChoices())
    }

    @Test fun theEz60SwipesWithTwoFingersAndNeverThree() {
        MazdaPlatform.ez60ForTests = true
        assertEquals(2, AirPlayPersistence.loadSettingsGestureFingers(context))
        assertEquals(listOf(0, 2, 4), AirPlayPersistence.settingsGestureFingerChoices())
        // A three saved by an earlier build reads as two; the driver's other choices are kept.
        AirPlayPersistence.saveSettingsGestureFingers(context, 3)
        assertEquals(2, AirPlayPersistence.loadSettingsGestureFingers(context))
        for (kept in listOf(0, 4, 2)) {
            AirPlayPersistence.saveSettingsGestureFingers(context, kept)
            assertEquals(kept, AirPlayPersistence.loadSettingsGestureFingers(context))
        }
    }
}
