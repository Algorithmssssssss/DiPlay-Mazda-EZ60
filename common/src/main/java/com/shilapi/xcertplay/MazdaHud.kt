package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.text.TextUtils
import android.text.TextPaint
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * The Mazda EZ-60's windshield head-up display: an 800x480 presentation display ("MeterDisplay#0") of which
 * only the [VISIBLE_LEFT]..[VISIBLE_BOTTOM] rectangle reaches the windshield. The car's own navigation apps
 * draw into that rectangle from an ordinary window on the display, and DiPlay shows CarPlay's next turn
 * there the same way, using only that public display. Black is not projected, so the rest stays dark.
 */
internal class MazdaHud(private val context: Context) {
    private var presentation: Presentation? = null
    private var card: MazdaHudCardView? = null

    /** Shows [guidance], or clears the HUD when there is none. Main thread. */
    fun update(guidance: ClusterTurnGuidance?) {
        if (guidance == null) {
            close()
            return
        }
        (card ?: open())?.guidance = guidance
    }

    private fun open(): MazdaHudCardView? {
        val display = findDisplay(context) ?: return null
        val shown = Presentation(context, display)
        val view = MazdaHudCardView(shown.context)
        shown.setContentView(FrameLayout(shown.context).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(VISIBLE_RIGHT - VISIBLE_LEFT, VISIBLE_BOTTOM - VISIBLE_TOP).apply {
                leftMargin = VISIBLE_LEFT
                topMargin = VISIBLE_TOP
            })
        })
        return runCatching { shown.show() }.fold(
            onSuccess = {
                presentation = shown
                card = view
                view
            },
            onFailure = { null },
        )
    }

    fun close() {
        runCatching { presentation?.dismiss() }
        presentation = null
        card = null
    }

    companion object {
        const val WIDTH = 800
        const val HEIGHT = 480
        // The projected part of the surface, as the car's own navigation app draws it.
        const val VISIBLE_LEFT = 573
        const val VISIBLE_TOP = 167
        const val VISIBLE_RIGHT = 800
        const val VISIBLE_BOTTOM = 355

        @Suppress("DEPRECATION")
        fun findDisplay(context: Context): Display? = context.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { display -> Point().also(display::getRealSize).let { it.x == WIDTH && it.y == HEIGHT } }
    }
}

/** The HUD's on/off choice; on by default on the EZ-60, the only car that shows it. */
internal object MazdaHudSettings {
    private const val PREFS = "diplay_mazda_hud"
    private const val KEY_ENABLED = "turn_by_turn"

    fun enabled(context: Context): Boolean = MazdaPlatform.isEz60 &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

/** Arrow, distance and road for the HUD's small projected area: bright on black, which is not projected. */
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
        val w = width.toFloat()
        val h = height.toFloat()
        val side = minOf(w, h * 0.52f)
        val res = ClusterTurnCardView.glyphRes(next.icon)
        if (res != glyphRes) {
            glyph = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(accent) }
            glyphRes = res
        }
        val left = (w - side) / 2f
        glyph?.setBounds(left.toInt(), 0, (left + side).toInt(), side.toInt())
        glyph?.draw(canvas)
        distancePaint.textSize = h * 0.21f
        val distanceY = side + distancePaint.textSize
        canvas.drawText(ClusterTurnCardView.distanceLabel(context, next.distanceMeters), w / 2f, distanceY, distancePaint)
        roadPaint.textSize = h * 0.12f
        val road = TextUtils.ellipsize(ClusterTurnCardView.roadLabel(context, next), roadPaint, w - 8f,
            TextUtils.TruncateAt.END).toString()
        canvas.drawText(road, w / 2f, minOf(h - 4f, distanceY + roadPaint.textSize * 1.3f), roadPaint)
    }
}
