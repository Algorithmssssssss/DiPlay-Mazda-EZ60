package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DiPlay reads the car's HUD as the car's own navigation does (ChanganJ90KAbilityTree, HudJob). */
class MazdaCarFeaturesTest {
    @Test fun onlyHudTypeThreeIsTheArHud() {
        assertEquals(MazdaCarFeatures.Hud.AUGMENTED_REALITY, MazdaCarFeatures.hudFor("3"))
        assertEquals(MazdaCarFeatures.Hud.AUGMENTED_REALITY, MazdaCarFeatures.hudFor(" 3 "))
        for (other in listOf("0", "1", "2", "4", "abc", "", null)) {
            assertEquals(other.toString(), MazdaCarFeatures.Hud.STANDARD, MazdaCarFeatures.hudFor(other))
        }
    }

    @Test fun anUnreadableSettingIsTheStandardHudAndIsAskedAgain() {
        val reads = mutableListOf<String>()
        resetCache()
        assertEquals(MazdaCarFeatures.Hud.STANDARD, MazdaCarFeatures.hud { reads += it; null })
        assertEquals(MazdaCarFeatures.Hud.AUGMENTED_REALITY, MazdaCarFeatures.hud { reads += it; "3" })
        // A value once read is kept: a trim does not change while the car runs.
        assertEquals(MazdaCarFeatures.Hud.AUGMENTED_REALITY, MazdaCarFeatures.hud { reads += it; "0" })
        assertEquals(listOf(MazdaCarFeatures.HUD_TYPE, MazdaCarFeatures.HUD_TYPE), reads)
        resetCache()
    }

    @Test fun theSummaryNamesEveryReportedFeature() {
        val summary = MazdaCarFeatures.summary { key -> if (key == MazdaCarFeatures.HUD_TYPE) "3" else null }
        assertTrue(summary, summary.contains("hud_type=3"))
        assertTrue(summary, summary.contains("ethernet=unset"))
        assertEquals(MazdaCarFeatures.REPORTED.size, summary.split(' ').size)
    }

    // Option 3 reports had no "Car features:" line: the session log drops any line that says "pass" (passenger_screen).
    @Test fun theSessionLogKeepsTheWholeLine() {
        val line = "Car features: " + MazdaCarFeatures.summary { "31" }
        assertEquals(line, DiagnosticRedactor.redact(line))
        assertTrue(line, line.contains("copilot_screen=31"))
    }

    private fun resetCache() {
        MazdaCarFeatures::class.java.getDeclaredField("hudType").apply { isAccessible = true }.set(null, null)
    }
}
