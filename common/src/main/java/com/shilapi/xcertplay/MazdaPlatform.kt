package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Whether this head unit is the Mazda EZ-60 cockpit (Changan Mazda J90K, CAMZD OS), with the evidence.
 * The car's own apps read [KEY_SCREEN_SHOW], [KEY_VPA_LEFT] and [KEY_VPA_RIGHT] to place their panel. Cockpit layout
 * still follows the window size alone until the EZ-60 probe confirms the build values on the car.
 */
internal object MazdaPlatform {
    const val KEY_SCREEN_SHOW = "key_screen_show"
    const val KEY_VPA_LEFT = "key_vpa_cui_show_left"
    const val KEY_VPA_RIGHT = "key_vpa_cui_show_right"
    private val buildPattern = Regex("(?i)mazda|changan|camzd|j90k|wtcl")

    /** The EZ-60 head unit by its build model (WUTONG/TINNOVE J90K, measured by the probe), for defaults. */
    val isEz60: Boolean by lazy { Build.MODEL.equals("J90K", ignoreCase = true) }

    data class Detection(val buildMatch: String?, val oemKeysPresent: Boolean) {
        val likely: Boolean get() = buildMatch != null || oemKeysPresent
    }

    fun detect(context: Context): Detection = detect(
        listOf(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.PRODUCT, Build.DEVICE, Build.DISPLAY, Build.FINGERPRINT),
        oemKeysPresent(context),
    )

    internal fun detect(buildValues: List<String?>, oemKeysPresent: Boolean) = Detection(
        buildValues.firstNotNullOfOrNull { value -> value?.let(buildPattern::find)?.value },
        oemKeysPresent,
    )

    private fun oemKeysPresent(context: Context): Boolean = listOf(KEY_SCREEN_SHOW, KEY_VPA_LEFT).any { key ->
        try {
            Settings.Global.getString(context.contentResolver, key) != null
        } catch (_: SecurityException) {
            false
        }
    }
}
