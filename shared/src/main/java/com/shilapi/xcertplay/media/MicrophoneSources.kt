package com.shilapi.xcertplay.media

/** Names for MediaRecorder.AudioSource values, including the system-only ones another app may record with. */
internal object MicrophoneSources {
    fun name(source: Int): String = when (source) {
        0 -> "DEFAULT"
        1 -> "MIC"
        2 -> "VOICE_UPLINK"
        3 -> "VOICE_DOWNLINK"
        4 -> "VOICE_CALL"
        5 -> "CAMCORDER"
        6 -> "VOICE_RECOGNITION"
        7 -> "VOICE_COMMUNICATION"
        8 -> "REMOTE_SUBMIX"
        9 -> "UNPROCESSED"
        10 -> "VOICE_PERFORMANCE"
        1997 -> "ECHO_REFERENCE"
        1998 -> "RADIO_TUNER"
        1999 -> "HOTWORD"
        else -> "SOURCE_$source"
    }
}
