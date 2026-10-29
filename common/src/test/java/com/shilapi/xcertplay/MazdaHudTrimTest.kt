package com.shilapi.xcertplay

import com.shilapi.xcertplay.MazdaCarFeatures.Hud
import com.shilapi.xcertplay.MazdaHudSettings.ArMapSize
import com.shilapi.xcertplay.MazdaHudSettings.Trim
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The driver's trim choice decides which HUD DiPlay draws for (Settings → Mazda EZ-60 → Windshield HUD). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MazdaHudTrimTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun automaticFollowsWhatTheCarReports() {
        assertEquals(Trim.AUTOMATIC, MazdaHudSettings.trim(context))
        assertEquals(Hud.AUGMENTED_REALITY, MazdaHudSettings.hud(context) { Hud.AUGMENTED_REALITY })
        assertEquals(Hud.STANDARD, MazdaHudSettings.hud(context) { Hud.STANDARD })
    }

    @Test fun aPickedTrimWinsOverTheCarsReport() {
        MazdaHudSettings.saveTrim(context, Trim.OPTION_3)
        assertEquals(Hud.AUGMENTED_REALITY, MazdaHudSettings.hud(context) { Hud.STANDARD })
        MazdaHudSettings.saveTrim(context, Trim.OPTION_1_2)
        assertEquals(Hud.STANDARD, MazdaHudSettings.hud(context) { Hud.AUGMENTED_REALITY })
    }

    @Test fun anUnknownStoredTrimIsAutomatic() {
        context.getSharedPreferences("diplay_mazda_hud", android.content.Context.MODE_PRIVATE).edit()
            .putString("trim", "OPTION_9").commit()
        assertEquals(Trim.AUTOMATIC, MazdaHudSettings.trim(context))
    }

    // On the 100-inch AR-HUD, 735x560 at (1285,60) read best at the glass (EZNav); a saved choice is kept.
    @Test fun theArHudMapIsLargestUnlessTheDriverChoseAnotherSize() {
        assertEquals(ArMapSize.LARGEST, MazdaHudSettings.arMapSize(context))
        assertEquals(MazdaArHudLayout.Box(1285, 60, 735, 560),
            MazdaArHudLayout.box(MazdaArHudLayout.Side.RIGHT, MazdaHudSettings.arMapSize(context)))
        MazdaHudSettings.saveArMapSize(context, ArMapSize.CAR)
        assertEquals(ArMapSize.CAR, MazdaHudSettings.arMapSize(context))
    }
}
