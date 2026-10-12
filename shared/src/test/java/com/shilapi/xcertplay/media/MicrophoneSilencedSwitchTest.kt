package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

/** EZ-60 report 22: Android silenced a call's MIC capture; the call then moves to the next source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MicrophoneSilencedSwitchTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val recorders = CopyOnWriteArrayList<AudioRecord>()
    private var uplink: MicrophoneUplink? = null

    @Before fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowAudioRecord.setSourceProvider { record ->
            if (record !in recorders) recorders += record
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return 0
                }
            }
        }
    }

    @After fun tearDown() {
        uplink?.close()
        ShadowAudioRecord.clearSource()
    }

    @Test fun aSilencedCallCaptureMovesToTheNextSourceAndStaysOnOneThatIsHeard() {
        val sources = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
        uplink = MicrophoneUplink(config(), diagnostics::add, callSources = sources,
            isSilenced = { it.audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION })
        assertTrue(uplink!!.start())

        val deadline = System.currentTimeMillis() + 5_000
        while (diagnostics.none { it.startsWith("Microphone: silenced") } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        Thread.sleep(1_500) // a heard source is kept
        assertEquals(1, diagnostics.count { it.startsWith("Microphone: silenced") })
        assertTrue(diagnostics.any {
            it.startsWith("Microphone: silenced by Android with source=VOICE_RECOGNITION; now source=VOICE_COMMUNICATION")
        })
        assertEquals(AudioRecord.STATE_UNINITIALIZED, recorders.first().state)
        uplink!!.close()
        assertTrue(diagnostics.any { it.startsWith("Microphone: stats type=telephony source=VOICE_COMMUNICATION") &&
            it.endsWith("ended=true") })
    }

    @Test fun withoutCallSourcesASilencedCaptureIsOnlyReported() {
        uplink = MicrophoneUplink(config(), diagnostics::add, isSilenced = { true })
        assertTrue(uplink!!.start())
        Thread.sleep(1_500)
        uplink!!.close()
        assertTrue(diagnostics.none { it.startsWith("Microphone: silenced") })
        assertEquals(1, recorders.size)
    }

    private fun config() = MicrophoneConfig(audioType = "telephony", sampleRate = 16_000, channels = 1,
        payloadType = 100, frameMillis = 20, host = InetAddress.getLoopbackAddress(), port = 9, key = ByteArray(32))
}
