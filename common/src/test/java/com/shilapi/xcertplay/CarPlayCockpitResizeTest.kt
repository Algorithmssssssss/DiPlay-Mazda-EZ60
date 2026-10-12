package com.shilapi.xcertplay

import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayCockpitResizeTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var video: TextureView
    private lateinit var controller: CarPlayController
    private lateinit var commands: PausedExecutorService
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.resources.displayMetrics.apply { widthPixels = 2560; heightPixels = 1440 }
        controller = mock(CarPlayController::class.java)
        `when`(controller.updateMainViewArea(anyInt())).thenReturn(true)
        setField("controller", controller)
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        commands = PausedExecutorService()
        setField("airPlayCommandExecutor", commands)
        video = TextureView(activity).apply { layout(0, 0, 1728, 1080) }
        setField("videoView", video)
        setField("sessionDisplay", CarPlaySessionDisplay(2496, 1080, Surface.ROTATION_0, true, true, 2496, 1080,
            cockpitViewAreas = listOf(AirPlayInsets(), AirPlayInsets(right = 768)), viewAreaIndex = 1))
        updateVideo()
    }

    @After fun tearDown() {
        (getField("shuttingDown") as AtomicBoolean).set(true)
        (getField("mainHandler") as Handler).removeCallbacksAndMessages(null)
        commands.shutdownNow()
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        controller.close()
        CarPlayBackgroundSession.clear()
    }

    @Test fun narrowBlackStripIsOutsideTheWindowAndTouchesStillMatchTheVideo() {
        val points = floatArrayOf(0f, 540f, 1720f * 1728 / 2496, 540f, 1728f * 1728 / 2496, 540f)
        video.getTransform(Matrix()).mapPoints(points)
        assertEquals(0f, points[0], 0.001f)
        assertEquals(1728f, points[2], 0.001f)
        assertTrue(points[4] > 1728f)
        var received: List<AirPlayContact> = emptyList()
        doAnswer { invocation -> received = invocation.getArgument(0); true }
            .`when`(controller).sendTouch(anyList())
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 864f, 540f, 0)
        try {
            activity.javaClass.getDeclaredMethod("onHostTouch", android.view.View::class.java, MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(activity, video, event)
        } finally { event.recycle() }
        assertEquals(860.0 / 1728, received.single().x, 0.0001)
        assertEquals(0.5, received.single().y, 0.0001)
    }

    @Test fun wideWindowRestoresWithoutAnAcknowledgementOrAnotherVideoFrame() {
        switchTo(2496, 0)
        val before = floatArrayOf(0f, 0f)
        video.getTransform(Matrix()).mapPoints(before)
        assertTrue("The temporary narrow crop enlarges the canvas", before[1] < 0f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
        assertWideCanvas()
        assertEquals(0L, getField("cockpitTransitionUntil"))
    }

    // EZ-60 report 22: with smooth video, closing the vehicle panel drew CarPlay over the whole half for about 1 s.
    @Test fun aSurfaceViewNeverOutgrowsTheWidenedPanel() {
        setField("fallbackVideoView", android.view.SurfaceView(activity))
        updateVideo()
        switchTo(2496, 0)
        assertEquals(CarPlaySurfaceBounds(0, 0, 2496, 1080), getField("fallbackVideoBounds"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
        assertEquals(CarPlaySurfaceBounds(0, 0, 2496, 1080), getField("fallbackVideoBounds"))
    }

    @Test fun aSurfaceViewStillCropsToTheNarrowRegionWhenThePanelNarrows() {
        setField("fallbackVideoView", android.view.SurfaceView(activity))
        switchTo(2496, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501))
        switchTo(1728, 1)
        val bounds = getField("fallbackVideoBounds") as CarPlaySurfaceBounds
        assertEquals(0, bounds.left)
        assertTrue("The narrow region fills the 1728 px panel", bounds.width >= 2496 && bounds.top <= 0)
        assertTrue("No more than the 8 px strip spills over", bounds.height <= 1090)
    }

    @Test fun acknowledgedWideWindowRestoresAfterAnimationWithoutAnotherFrame() {
        switchTo(2496, 0)
        val listener = activity.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 0) as AirPlaySessionListener
        listener.onMainViewAreaAcknowledged(mock(AirPlaySession::class.java), 0, true)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(301))
        assertWideCanvas()
    }

    @Test fun oldTimeoutCannotEndANewerNarrowTransition() {
        switchTo(2496, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        switchTo(1728, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        assertTrue((getField("cockpitTransitionUntil") as Long) > android.os.SystemClock.elapsedRealtime())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(0L, getField("cockpitTransitionUntil"))
        val points = floatArrayOf(1720f * 1728 / 2496, 540f)
        video.getTransform(Matrix()).mapPoints(points)
        assertEquals(1728f, points[0], 0.001f)
    }

    private fun switchTo(width: Int, index: Int) {
        video.layout(0, 0, width, 1080)
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, 1080)
        setField("activeDisplaySize", size)
        activity.javaClass.getDeclaredMethod("switchCockpitViewArea", sizeClass, CarPlaySessionDisplay::class.java,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(activity, size, getField("sessionDisplay"), index)
        commands.runAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertWideCanvas() {
        val points = floatArrayOf(0f, 0f, 2496f, 1080f)
        video.getTransform(Matrix()).mapPoints(points)
        assertArrayEquals(floatArrayOf(0f, 0f, 2496f, 1080f), points, 0.001f)
        assertSame(controller, getField("controller"))
    }

    private fun updateVideo() {
        activity.javaClass.getDeclaredMethod("updateVideoLayout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, video.width, video.height)
    }

    private fun getField(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
