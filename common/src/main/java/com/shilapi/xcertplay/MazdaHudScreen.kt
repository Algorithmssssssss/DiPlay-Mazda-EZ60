package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * DiPlay's window on the EZ-60 HUD display ("MeterDisplay#0"). On the 2D HUD (800x480) the HUD projects only the
 * [MazdaHud.VISIBLE_LEFT]..[MazdaHud.VISIBLE_BOTTOM] part of it, and only while DiPlay holds navigation focus
 * ([MazdaHud]); black is not projected. On the AR-HUD the window exists only while DiPlay holds the video link
 * ([MazdaArHud]) and fills the car's own map box ([arArea]). In the area: CarPlay's instrument-cluster map while
 * the iPhone streams it, or a turn card when there is no map. The 2D HUD draws the arrow and distance itself.
 */
internal class MazdaHudScreen(
    context: Context,
    display: Display,
    private val area: Area = STANDARD_AREA,
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {
    var outputSurface: Surface? = null
        private set
    private var card: MazdaHudCardView? = null
    private var areaView: View? = null
    private var mapActive = false

    /** Where the area sits on the display, in its pixels; the background outside it is not projected. */
    data class Area(val left: Int, val top: Int, val width: Int, val height: Int, val background: Int)

    var guidance: ClusterTurnGuidance? = null
        set(value) {
            field = value
            card?.guidance = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val map = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    outputSurface = Surface(texture).also(onSurface)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    onSurface(null)
                    outputSurface?.release()
                    outputSurface = null
                    return true
                }
            }
        }
        val turnCard = MazdaHudCardView(context).apply {
            guidance = this@MazdaHudScreen.guidance
            visibility = if (mapActive) View.INVISIBLE else View.VISIBLE
        }
        card = turnCard
        val content = FrameLayout(context).apply {
            addView(map, FrameLayout.LayoutParams(-1, -1))
            addView(turnCard, FrameLayout.LayoutParams(-1, -1))
        }
        areaView = content
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(area.background))
        setContentView(FrameLayout(context).apply {
            setBackgroundColor(area.background)
            addView(content, FrameLayout.LayoutParams(area.width, area.height).apply {
                leftMargin = area.left
                topMargin = area.top
            })
        })
    }

    /** The AR-HUD's driver changed the map's side: move the area, keeping the map stream's surface. */
    fun moveTo(left: Int) {
        val view = areaView ?: return
        view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = left }
    }

    /** The cluster map stream started or stopped: the turn card shows only without it. */
    fun setMapActive(active: Boolean) {
        mapActive = active
        card?.visibility = if (active) View.INVISIBLE else View.VISIBLE
    }

    internal companion object {
        const val AREA_WIDTH = MazdaHud.VISIBLE_RIGHT - MazdaHud.VISIBLE_LEFT
        const val AREA_HEIGHT = MazdaHud.VISIBLE_BOTTOM - MazdaHud.VISIBLE_TOP
        val STANDARD_AREA = Area(MazdaHud.VISIBLE_LEFT, MazdaHud.VISIBLE_TOP, AREA_WIDTH, AREA_HEIGHT, Color.BLACK)

        /** The car's own AR-HUD map box; transparent around it, as the car's navigation draws it. */
        fun arArea(side: MazdaArHudLayout.Side) = Area(side.left, MazdaArHudLayout.MAP_TOP,
            MazdaArHudLayout.MAP_WIDTH, MazdaArHudLayout.MAP_HEIGHT, Color.TRANSPARENT)

        @Suppress("DEPRECATION")
        fun findDisplay(context: Context): Display? = context.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { display ->
                Point().also(display::getRealSize).let { it.x == MazdaHud.WIDTH && it.y == MazdaHud.HEIGHT }
            }

        /** The AR-HUD's display: the car's navigation finds it by name; the map box must fit on it. */
        @Suppress("DEPRECATION")
        fun findArDisplay(context: Context): Display? = context.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { display ->
                display.name == MazdaArHudLayout.DISPLAY_NAME && Point().also(display::getRealSize).let {
                    it.x >= MazdaArHudLayout.MAP_LEFT_ON_RIGHT + MazdaArHudLayout.MAP_WIDTH &&
                        it.y >= MazdaArHudLayout.MAP_TOP + MazdaArHudLayout.MAP_HEIGHT
                }
            }
    }
}

/** Arrow, distance and road for the HUD area, bright on black (black is not projected). */
internal class MazdaHudCardView(context: Context) : View(context) {
    var guidance: ClusterTurnGuidance? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val accent = Color.rgb(64, 200, 255)
    private val distancePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val roadPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 220, 220)
        textAlign = Paint.Align.CENTER
    }
    private var glyph: Drawable? = null
    private var glyphRes = 0

    override fun onDraw(canvas: Canvas) {
        val next = guidance ?: return
        if (next.icon == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val side = minOf(w, h * 0.52f)
        drawGlyph(canvas, next, (w - side) / 2f, 0f, side)
        distancePaint.textSize = h * 0.21f
        val distanceY = side + distancePaint.textSize
        canvas.drawText(ClusterTurnCardView.distanceLabel(context, next.distanceMeters), w / 2f, distanceY, distancePaint)
        roadPaint.textSize = h * 0.12f
        val road = TextUtils.ellipsize(ClusterTurnCardView.roadLabel(context, next), roadPaint, w - 8f,
            TextUtils.TruncateAt.END).toString()
        canvas.drawText(road, w / 2f, minOf(h - 4f, distanceY + roadPaint.textSize * 1.3f), roadPaint)
    }

    private fun drawGlyph(canvas: Canvas, next: ClusterTurnGuidance, left: Float, top: Float, side: Float) {
        val res = ClusterTurnCardView.glyphRes(next.icon)
        if (res != glyphRes) {
            glyph = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(accent) }
            glyphRes = res
        }
        glyph?.setBounds(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt())
        glyph?.draw(canvas)
    }
}
