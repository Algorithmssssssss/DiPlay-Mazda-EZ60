package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug builds: a drawn stand-in for CarPlay's cluster map, dark and light, through [MazdaHudMapFilter] at several
 * looks, on black as on the HUD. The emulator has no CarPlay; this shows what each look keeps.
 * `adb shell am start -n <package>/com.shilapi.xcertplay.MazdaHudMapLookPreviewActivity`
 */
class MazdaHudMapLookPreviewActivity : Activity() {
    private val looks = listOf(
        Triple("As the iPhone draws it", MazdaHudSettings.MapStyle.WHOLE, 0),
        Triple("Whole map, 70 %", MazdaHudSettings.MapStyle.WHOLE, 70),
        Triple("Whole map, 100 %", MazdaHudSettings.MapStyle.WHOLE, 100),
        Triple("Lines only, 100 %", MazdaHudSettings.MapStyle.LINES, 100),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val grid = GridLayout(this).apply {
            columnCount = looks.size
            setBackgroundColor(Color.BLACK)
            setPadding(PAD, PAD, PAD, PAD)
        }
        for (dark in listOf(true, false)) for ((title, style, seeThrough) in looks) {
            grid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(PAD, PAD, PAD, PAD)
                addView(TextView(context).apply {
                    text = "$title (${if (dark) "dark" else "light"} map)"
                    setTextColor(Color.LTGRAY)
                    gravity = Gravity.CENTER
                })
                addView(mapView(dark, style, seeThrough), LinearLayout.LayoutParams(WIDTH, HEIGHT))
            })
        }
        setContentView(grid)
    }

    private fun mapView(dark: Boolean, style: MazdaHudSettings.MapStyle, seeThrough: Int): TextureView {
        val view = TextureView(this)
        val filter = MazdaHudMapFilter(view) { Log.i(TAG, it) }
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(width, height)
                val surface = Surface(texture)
                surface.lockCanvas(null).let { canvas ->
                    canvas.drawBitmap(sampleMap(width, height, dark), 0f, 0f, null)
                    surface.unlockCanvasAndPost(canvas)
                }
                surface.release()
                filter.set(style, seeThrough)
                view.postDelayed({ filter.sampleBackground() }, 300)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                filter.release()
                return true
            }
        }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> filter.apply() }
        return view
    }

    /**
     * Roads, a park, water, buildings, labels, the route and the position arrow. The dark map uses Google Maps' night
     * colours as an EZ-60 read them (land #1c3857, water #012054, 2026-10-21), with faint side streets, and both maps
     * get the small noise of the car's compressed map stream.
     */
    private fun sampleMap(width: Int, height: Int, dark: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(if (dark) Color.rgb(28, 56, 87) else Color.rgb(242, 239, 233))
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = if (dark) Color.rgb(30, 68, 78) else Color.rgb(196, 230, 184)
        canvas.drawRect(w * 0.05f, h * 0.08f, w * 0.38f, h * 0.36f, fill)
        fill.color = if (dark) Color.rgb(1, 32, 84) else Color.rgb(160, 205, 240)
        canvas.drawPath(Path().apply {
            moveTo(w * 0.62f, h)
            cubicTo(w * 0.7f, h * 0.75f, w * 0.85f, h * 0.7f, w, h * 0.62f)
            lineTo(w, h)
            close()
        }, fill)
        fill.color = if (dark) Color.rgb(40, 68, 99) else Color.rgb(225, 221, 214)
        for (i in 0..3) canvas.drawRect(w * (0.08f + i * 0.07f), h * 0.6f, w * (0.13f + i * 0.07f), h * 0.68f, fill)
        val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val mainRoad = if (dark) Color.rgb(150, 165, 185) else Color.WHITE
        val sideStreet = if (dark) Color.rgb(50, 80, 112) else Color.WHITE
        val casing = if (dark) Color.rgb(24, 48, 76) else Color.rgb(200, 196, 190)
        fun street(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, colour: Int) {
            road.color = casing
            road.strokeWidth = width + 2f
            canvas.drawLine(x0 * w, y0 * h, x1 * w, y1 * h, road)
            road.color = colour
            road.strokeWidth = width
            canvas.drawLine(x0 * w, y0 * h, x1 * w, y1 * h, road)
        }
        street(0f, 0.5f, 1f, 0.45f, 9f, mainRoad)
        street(0.45f, 0f, 0.5f, 1f, 9f, mainRoad)
        for (i in 1..4) street(0f, i * 0.2f - 0.08f, 1f, i * 0.2f - 0.05f, 3f, sideStreet)
        for (i in 1..4) street(i * 0.22f, 0f, i * 0.22f - 0.04f, 1f, 3f, sideStreet)
        road.color = if (dark) Color.rgb(0, 190, 220) else Color.rgb(26, 115, 232)
        road.strokeWidth = 10f
        canvas.drawPath(Path().apply {
            moveTo(w * 0.47f, h)
            lineTo(w * 0.475f, h * 0.48f)
            lineTo(w, h * 0.45f)
        }, road)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = h * 0.05f
            color = if (dark) Color.rgb(170, 170, 174) else Color.rgb(90, 90, 96)
        }
        canvas.drawText("Main Street", w * 0.06f, h * 0.43f, text)
        canvas.drawText("Park", w * 0.15f, h * 0.24f, text)
        fill.color = Color.WHITE
        canvas.drawCircle(w * 0.473f, h * 0.82f, 9f, fill)
        fill.color = Color.rgb(38, 120, 245)
        canvas.drawCircle(w * 0.473f, h * 0.82f, 6f, fill)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val noise = java.util.Random(7)
        for (i in pixels.indices) {
            val n = noise.nextInt(9) - 4
            val p = pixels[i]
            pixels[i] = Color.rgb(((p shr 16 and 0xff) + n).coerceIn(0, 255), ((p shr 8 and 0xff) + n).coerceIn(0, 255),
                ((p and 0xff) + n).coerceIn(0, 255))
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private companion object {
        const val TAG = "DiPlayHud"
        const val WIDTH = 390
        const val HEIGHT = 350
        const val PAD = 8
    }
}
