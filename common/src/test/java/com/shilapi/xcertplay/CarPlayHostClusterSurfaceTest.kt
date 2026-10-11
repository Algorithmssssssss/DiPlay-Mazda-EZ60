package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which surface stream 111 gets when the CarPlay screen's surface comes back (after Settings, for example). EZ-60
 * report 19: with the HUD map on, stream 111 was handed the main surface, its decoder connected first, and the main
 * decoder failed to configure until the screen was left again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CarPlayHostClusterSurfaceTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var sink: AndroidMediaSink
    private val textures = mutableListOf<SurfaceTexture>()

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        AirPlayPersistence.saveClusterMapEnabled(activity, false)
        sink = mock(AndroidMediaSink::class.java)
        setField("sink", sink)
    }

    @After fun tearDown() {
        setField("sink", null)
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        textures.forEach { it.release() }
    }

    @Test fun aStreamForTheHudKeepsItsOwnSurfaceWhenTheMainSurfaceReturns() {
        val main = surface()
        val hud = surface()
        setField("clusterStreamOnDisplay", true)
        setField("clusterSurface", hud)

        attachSurface(main)

        verify(sink).setSurface(MAIN, main)
        verify(sink).setSurface(ALT, hud)
        verify(sink, never()).setSurface(ALT, main)
    }

    @Test fun aStreamForTheHudWithoutItsWindowGetsNoSurface() {
        val main = surface()
        setField("clusterStreamOnDisplay", true)

        attachSurface(main)

        verify(sink).setSurface(MAIN, main)
        verify(sink, never()).setSurface(ALT, main)
    }

    @Test fun withoutAClusterOutputStream111StillFollowsTheMainSurface() {
        val main = surface()

        attachSurface(main)

        verify(sink).setSurface(MAIN, main)
        verify(sink).setSurface(ALT, main)
    }

    private companion object {
        const val MAIN = 110 // CarPlayHostActivity.SCREEN_TYPE_MAIN
        const val ALT = 111 // stream 111: the cluster or HUD map
    }

    private fun surface(): Surface = Surface(SurfaceTexture(0).also { textures += it })

    private fun attachSurface(surface: Surface) {
        CarPlayHostActivity::class.java.getDeclaredMethod("attachSurface", Surface::class.java)
            .apply { isAccessible = true }.invoke(activity, surface)
    }

    private fun getField(name: String): Any? =
        CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun setField(name: String, value: Any?) {
        CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
