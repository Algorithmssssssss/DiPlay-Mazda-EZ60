package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
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
 * DiPlay's window on the EZ-60 HUD's 800x480 display ("MeterDisplay#0"). The HUD projects only the
 * [MazdaHud.VISIBLE_LEFT]..[MazdaHud.VISIBLE_BOTTOM] part of it, and only while DiPlay holds navigation focus
 * ([MazdaHud]); black is not projected. In that part: CarPlay's instrument-cluster map while the iPhone streams
 * it, with a small turn chip in its corner, or a full turn card when there is no map.
 */
internal class MazdaHudScreen(
    context: Context,
    display: Display,
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {
    var outputSurface: Surface? = null
        private set
    private var card: MazdaHudCardView? = null
    private var mapActive = false

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
            compact = mapActive
        }
        card = turnCard
        val area = FrameLayout(context).apply {
            addView(map, FrameLayout.LayoutParams(-1, -1))
            addView(turnCard, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(area, FrameLayout.LayoutParams(AREA_WIDTH, AREA_HEIGHT).apply {
                leftMargin = MazdaHud.VISIBLE_LEFT
                topMargin = MazdaHud.VISIBLE_TOP
            })
        })
    }

    /** The cluster map stream started or stopped: the turn card fills the area only without it. */
    fun setMapActive(active: Boolean) {
        mapActive = active
        card?.compact = active
    }

    internal companion object {
        const val AREA_WIDTH = MazdaHud.VISIBLE_RIGHT - MazdaHud.VISIBLE_LEFT
        const val AREA_HEIGHT = MazdaHud.VISIBLE_BOTTOM - MazdaHud.VISIBLE_TOP

        @Suppress("DEPRECATION")
        fun findDisplay(context: Context): Display? = context.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { display ->
                Point().also(display::getRealSize).let { it.x == MazdaHud.WIDTH && it.y == MazdaHud.HEIGHT }
            }
    }
}

/**
 * Arrow, distance and road for the HUD area, bright on black (black is not projected). [compact] draws only a
 * small arrow-and-distance chip in the top-left corner, over CarPlay's map.
 */
internal class MazdaHudCardView(context: Context) : View(context) {
    var guidance: ClusterTurnGuidance? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var compact = false
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
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }
    private val chip = RectF()
    private var glyph: Drawable? = null
    private var glyphRes = 0

    override fun onDraw(canvas: Canvas) {
        val next = guidance ?: return
        if (next.icon == 0) return
        if (compact) drawChip(canvas, next) else drawCard(canvas, next)
    }

    private fun drawCard(canvas: Canvas, next: ClusterTurnGuidance) {
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

    private fun drawChip(canvas: Canvas, next: ClusterTurnGuidance) {
        val side = height * 0.30f
        distancePaint.textSize = height * 0.11f
        chip.set(4f, 4f, 4f + side * 1.5f, 4f + side + distancePaint.textSize * 1.4f)
        canvas.drawRoundRect(chip, 10f, 10f, chipPaint)
        drawGlyph(canvas, next, chip.centerX() - side / 2f, chip.top + 2f, side)
        canvas.drawText(ClusterTurnCardView.distanceLabel(context, next.distanceMeters), chip.centerX(),
            chip.bottom - distancePaint.textSize * 0.35f, distancePaint)
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
