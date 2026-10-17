package com.shilapi.xcertplay

import android.os.IBinder
import android.os.Parcel

/**
 * The EZ-60's feature configuration, the per-trim switches the car's own apps read
 * (com.tinnove.config.TinnoveConfig: service "tinnove_config", IConfigInterface.getValue). Read-only: only
 * [GET_VALUE] is called; nothing is written or registered.
 *
 * The car's navigation (WT_TinnoveNavigation, ChanganJ90KAbilityTree) decides its HUD from [HUD_TYPE]: 3 is the
 * 3D AR-HUD, whose picture reaches the windshield over a video link the head unit has to switch
 * (IDouInteractiveManager.setVideoStatus, IDoubleVideoCallback.onRequestLVDS); any other value, or none, is the
 * 2D HUD that shows the "MeterDisplay#0" window. DiPlay follows the same rule ([hud]).
 */
internal object MazdaCarFeatures {
    const val HUD_TYPE = "car_feature_hud_type"

    /** What DiPlay reports in the probe and session log, to tell trims apart. */
    val REPORTED = listOf(
        "car_feature_model",
        "car_feature_major_version_flag_bit",
        HUD_TYPE,
        "car_feature_hud_display",
        "car_feature_hud_assist_driving",
        "car_feature_screen_size",
        "car_feature_passenger_screen",
        "car_feature_ethernet",
        "car_feature_audio_architecture",
        "car_feature_audio_area",
        "car_feature_speaker_number",
        "car_feature_navigation_type",
        "car_feature_ambient_light_music_rhythm",
        "car_feature_ambient_lamp_function",
        "car_feature_interior_camera",
        "car_feature_gimbal_camera",
        "car_feature_panoramic_camera_support",
        "car_feature_bluetooth_key",
        "car_feature_wireless_charging",
    )

    enum class Hud {
        /** The 2D windshield HUD: it projects part of "MeterDisplay#0" while an app holds navigation focus. */
        STANDARD,
        /** The 3D AR-HUD: its picture comes over a switched video link that the car's own navigation drives. */
        AUGMENTED_REALITY,
    }

    private const val SERVICE = "tinnove_config"
    private const val INTERFACE = "com.tinnove.config.aidl.IConfigInterface"
    private const val GET_VALUE = 3 // IConfigInterface.getValue(String key): String
    private const val AR_HUD_TYPE = "3"

    // A trim does not change while the car runs; a value once read is kept. Null (not read yet) is asked again.
    @Volatile private var hudType: String? = null

    /** The car's HUD as its own navigation sees it; [Hud.STANDARD] when the setting cannot be read, as there. */
    fun hud(read: (String) -> String? = ::value): Hud {
        val type = hudType ?: read(HUD_TYPE)?.also { hudType = it }
        return hudFor(type)
    }

    internal fun hudFor(type: String?): Hud =
        if (type?.trim() == AR_HUD_TYPE) Hud.AUGMENTED_REALITY else Hud.STANDARD

    /**
     * One line for the session log. "passenger" is written as the car's own "copilot": the log drops any line that
     * says "pass" (DiagnosticRedactor), so the whole line was lost.
     */
    fun summary(read: (String) -> String? = ::value): String = REPORTED.joinToString(" ") { key ->
        "${key.removePrefix("car_feature_").replace("passenger", "copilot")}=${read(key) ?: "unset"}"
    }

    /** One feature's value, or null when the service or the key is missing. Not on a hot path. */
    fun value(key: String): String? {
        val binder = service() ?: return null
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(INTERFACE)
            request.writeString(key)
            if (!binder.transact(GET_VALUE, request, reply, 0)) return null
            reply.readException()
            reply.readString()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    private fun service(): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, SERVICE) as IBinder?
    }.getOrNull()?.takeIf { it.isBinderAlive }
}
