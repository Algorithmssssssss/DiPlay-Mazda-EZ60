package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Outline
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.core.graphics.drawable.toDrawable

/** The EZ-60 cockpit panel ([MazdaCockpitGeometry]) on the half of the screen this window is on. */
internal class MzdCarAppLayout(context: Context, private val carPlayProjection: Boolean = false) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var vehiclePanel = readVehiclePanel()
    private var side = MazdaCockpitGeometry.Side.DRIVER
    private var targetBounds: Rect? = null
    private val frameBounds = Rect()
    private var embedded = false
    private var animator: ValueAnimator? = null
    private var observing = false
    private val refresh = Runnable {
        vehiclePanel = readVehiclePanel()
        requestLayout()
    }
    // The two-finger swipe moves the window to the other half without resizing it, so no measure follows.
    private val sideWatcher = ViewTreeObserver.OnGlobalLayoutListener {
        if (embedded && readSide(width) != side) requestLayout()
    }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 16)
        }
    }
    private val panelOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            // CarPlay video corners measure 80 physical pixels at a height of 1080.
            val radius = if (carPlayProjection) view.height * (80f / 1080f)
                else 8 * resources.displayMetrics.density
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    fun setPanelContent(view: View) {
        removeAllViews()
        addView(view, LayoutParams(-1, -1))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        vehiclePanel = readVehiclePanel()
        try {
            for (key in listOf(MazdaPlatform.KEY_SCREEN_SHOW, MazdaPlatform.KEY_VPA_LEFT, MazdaPlatform.KEY_VPA_RIGHT)) {
                context.contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
            }
            observing = true
        } catch (_: SecurityException) {
            context.contentResolver.unregisterContentObserver(observer)
        }
        viewTreeObserver.addOnGlobalLayoutListener(sideWatcher)
        requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(sideWatcher)
        handler.removeCallbacks(refresh)
        if (observing) context.contentResolver.unregisterContentObserver(observer)
        observing = false
        animator?.cancel()
        targetBounds?.let(frameBounds::set)
        super.onDetachedFromWindow()
    }

    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val cockpit = isCockpitSize(width, height)
        val nextSide = if (cockpit) readSide(width) else MazdaCockpitGeometry.Side.DRIVER
        val moved = nextSide != side
        side = nextSide
        val bounds = if (cockpit) {
            val panel = MazdaCockpitGeometry.panel(side, vehiclePanel[side] == true)
            Rect(panel.left, panel.top, panel.right, panel.bottom)
        } else Rect(0, 0, width, height)
        if (bounds != targetBounds) {
            val previous = Rect(frameBounds)
            // The car's panel slides in on the same half; a move to the other half lands at once.
            val animate = targetBounds != null && embedded && cockpit && !moved
            animator?.cancel()
            targetBounds = bounds
            embedded = cockpit
            if (animate) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 300
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        val fraction = it.animatedValue as Float
                        fun position(start: Int, end: Int) = (start + (end - start) * fraction).roundToInt()
                        frameBounds.set(position(previous.left, bounds.left), position(previous.top, bounds.top),
                            position(previous.right, bounds.right), position(previous.bottom, bounds.bottom))
                        requestLayout()
                    }
                    start()
                }
            } else frameBounds.set(bounds)
        }
        getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(bounds.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bounds.height(), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val bounds = targetBounds ?: return
        getChildAt(0)?.apply {
            // Measure the destination once and move/scale the parent, as TidePlayer does.
            layout(frameBounds.left, frameBounds.top, frameBounds.left + bounds.width(), frameBounds.top + bounds.height())
            pivotX = 0f; pivotY = 0f
            scaleX = if (bounds.width() > 0) frameBounds.width().toFloat() / bounds.width() else 1f
            scaleY = if (bounds.height() > 0) frameBounds.height().toFloat() / bounds.height() else 1f
            outlineProvider = panelOutline
            clipToOutline = embedded
        }
    }

    /** Whether the car's panel or voice assistant narrows each half. */
    private fun readVehiclePanel(): Map<MazdaCockpitGeometry.Side, Boolean> = try {
        val resolver = context.contentResolver
        val screenShow = Settings.Global.getInt(resolver, MazdaPlatform.KEY_SCREEN_SHOW, 0)
        val vpaLeft = Settings.Global.getInt(resolver, MazdaPlatform.KEY_VPA_LEFT, 0)
        val vpaRight = Settings.Global.getInt(resolver, MazdaPlatform.KEY_VPA_RIGHT, 0)
        MazdaCockpitGeometry.Side.entries.associateWith { MazdaCockpitGeometry.narrow(it, screenShow, vpaLeft, vpaRight) }
    } catch (_: SecurityException) { emptyMap() }

    @Suppress("DEPRECATION")
    private fun readSide(windowWidth: Int): MazdaCockpitGeometry.Side {
        val screen = display ?: return MazdaCockpitGeometry.Side.DRIVER
        val real = Point().also(screen::getRealSize)
        val location = IntArray(2).also(::getLocationOnScreen)
        return MazdaCockpitGeometry.side(location[0], windowWidth, real.x)
    }

    companion object {
        fun applyWindowBackground(activity: Activity): Boolean {
            val cockpit = isCockpitWindow(activity)
            val color = if (cockpit) Color.TRANSPARENT else Color.rgb(12, 17, 27)
            activity.window.apply {
                // Inflate the decor before overriding the theme's system bar contrast.
                decorView
                setBackgroundDrawable(color.toDrawable())
                statusBarColor = color
                navigationBarColor = color
                if (Build.VERSION.SDK_INT >= 29) {
                    isStatusBarContrastEnforced = false
                    isNavigationBarContrastEnforced = !cockpit
                }
            }
            return cockpit
        }

        fun isCockpitWindow(activity: Activity): Boolean {
            val bounds = if (Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds
                else Rect(0, 0, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
            return isCockpitSize(bounds.width(), bounds.height())
        }

        private fun isCockpitSize(width: Int, height: Int): Boolean {
            if (width < 2560 || abs(height - 1440) > 72) return false
            val ratio = width.toFloat() / height
            return ratio in 1.70f..1.85f || ratio >= 3.40f
        }
    }
}
