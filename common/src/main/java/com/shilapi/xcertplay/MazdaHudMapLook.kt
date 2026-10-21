package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.TextureView
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How CarPlay's map reads on the EZ-60 HUD. The HUD projects light, so black is see-through, but the iPhone fills the
 * map's background with a flat colour that the glass shows as a lit box. See-through ([MazdaHudSettings.mapSeeThrough])
 * fades that colour: 0 % is the iPhone's picture, 100 % removes it. Lines only ([MazdaHudSettings.MapStyle.LINES]) also
 * fades parks, water and buildings, keeps roads, labels and strong colours (the route, traffic, the position arrow),
 * and draws them at full brightness. The background colour is the picture's most common colour ([backgroundColor]).
 */
internal object MazdaHudMapLook {
    /** How far apart two colours may be per channel (0-255) and still count as the same background. */
    const val BACKGROUND_TOLERANCE = 12

    /**
     * The most common colour of [pixels] (ARGB, alpha ignored): the colour with the most pixels within
     * [BACKGROUND_TOLERANCE], averaged over those pixels. Black when there are none.
     */
    fun backgroundColor(pixels: IntArray): Int {
        if (pixels.isEmpty()) return BLACK
        var best = pixels[0]
        var bestCount = -1
        // Every pixel is a candidate: a stride could step over the background on a regular pattern of roads. The
        // sample is small (a few hundred pixels), so this stays well under a millisecond.
        for (candidate in pixels) {
            val count = pixels.count { near(candidate, it) }
            if (count > bestCount) {
                best = candidate
                bestCount = count
            }
        }
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        for (pixel in pixels) {
            if (!near(best, pixel)) continue
            red += pixel shr 16 and 0xff
            green += pixel shr 8 and 0xff
            blue += pixel and 0xff
            count++
        }
        return BLACK or (red / count shl 16) or (green / count shl 8) or (blue / count)
    }

    /** Whether the background moved enough to draw again; compression noise moves it a step or two. */
    fun changed(old: Int, new: Int, tolerance: Int = CHANGE_TOLERANCE): Boolean = !near(old, new, tolerance)

    /**
     * How far each side a line test looks, in view pixels: a little wider than a main road on the HUD map, so a
     * road's pixels see the background on both sides, while inside a larger area one side still matches.
     */
    fun reach(width: Int, height: Int): Float = max(MIN_REACH, min(width, height) * REACH_SHARE)

    private fun near(a: Int, b: Int, tolerance: Int = BACKGROUND_TOLERANCE): Boolean =
        abs((a shr 16 and 0xff) - (b shr 16 and 0xff)) <= tolerance &&
            abs((a shr 8 and 0xff) - (b shr 8 and 0xff)) <= tolerance &&
            abs((a and 0xff) - (b and 0xff)) <= tolerance

    private const val BLACK = 0xff000000.toInt()
    private const val CHANGE_TOLERANCE = 3
    private const val MIN_REACH = 3f
    private const val REACH_SHARE = 0.035f

    /**
     * The filter on the GPU, over the map view's picture ("content", opaque video). Output is premultiplied: what
     * fades becomes transparent, which the 2D HUD's black window and the AR-HUD's transparent one both leave unlit.
     */
    const val SHADER = """
        uniform shader content;
        uniform float2 size;
        uniform float3 background;
        uniform float seeThrough;
        uniform float lines;
        uniform float reach;

        float3 at(float2 p) {
            return content.eval(clamp(p, float2(0.5), size - float2(0.5))).rgb;
        }

        float apart(float3 a, float3 b) {
            float3 d = abs(a - b);
            return max(d.r, max(d.g, d.b));
        }

        // A line differs from what lies on both sides of it, and those sides are alike. Inside an area one side
        // matches; on an area's soft border the two sides differ (the area, the background).
        float across(float3 c, float2 p, float2 offset) {
            float3 a = at(p + offset);
            float3 b = at(p - offset);
            return min(apart(c, a), apart(c, b)) - 0.5 * apart(a, b);
        }

        half4 main(float2 p) {
            float4 c = content.eval(p);
            if (c.a < 0.004) return half4(0.0);
            float3 rgb = c.rgb / c.a;
            // Colours near the background fade; compression noise stays below the lower edge. A day map's white
            // roads are only about 0.09 from its background.
            float keep = smoothstep(0.03, 0.09, apart(rgb, background));
            if (lines > 0.5) {
                float d = reach * 0.7071;
                float thin = max(max(across(rgb, p, float2(reach, 0.0)), across(rgb, p, float2(0.0, reach))),
                    max(across(rgb, p, float2(d, d)), across(rgb, p, float2(d, -d))));
                float bright = max(rgb.r, max(rgb.g, rgb.b));
                float chroma = bright - min(rgb.r, min(rgb.g, rgb.b));
                // Strong colours (the route, traffic, the position arrow) stay at any width.
                keep *= max(smoothstep(0.04, 0.12, thin), smoothstep(0.3, 0.5, chroma));
                // Grey roads and dark labels hardly show on the glass: draw what is left at full brightness.
                rgb = mix(rgb, rgb / max(bright, 0.25), seeThrough);
            }
            float alpha = mix(1.0, keep, seeThrough) * c.a;
            return half4(rgb * alpha, alpha);
        }
    """
}

/**
 * Applies [MazdaHudMapLook] to the HUD map's [TextureView] (Android 13 and later; earlier, the map stays as it is).
 * Main thread only.
 */
internal class MazdaHudMapFilter(private val view: TextureView, private val log: (String) -> Unit) {
    private var style = MazdaHudSettings.MapStyle.WHOLE
    private var seeThrough = 0
    private var background = 0xff000000.toInt()
    private var logged = background
    private var shader: Any? = null
    private var unavailable = false
    private var sample: Bitmap? = null

    /** The driver's choice; draws again only when it changed. */
    fun set(style: MazdaHudSettings.MapStyle, seeThrough: Int) {
        if (style == this.style && seeThrough == this.seeThrough) return
        this.style = style
        this.seeThrough = seeThrough
        log("HUD: map look style=$style seeThrough=$seeThrough%")
        apply()
    }

    /** Reads the map's background from a small copy of its current picture; draws again when it moved. */
    fun sampleBackground() {
        if (seeThrough == 0 || unavailable || !view.isAvailable) return
        val bitmap = sample ?: Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888).also { sample = it }
        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        runCatching { view.getBitmap(bitmap) }.getOrNull()?.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH,
            SAMPLE_HEIGHT) ?: return
        val found = MazdaHudMapLook.backgroundColor(pixels)
        if (!MazdaHudMapLook.changed(background, found)) return
        // Log a new map colour (day and night, another map), not the small moves while it scrolls.
        if (MazdaHudMapLook.changed(logged, found, LOG_TOLERANCE)) {
            log("HUD: map background #%06x".format(found and 0xffffff))
            logged = found
        }
        background = found
        apply()
    }

    /** Draws the map with the current look; also after the view changed size. */
    fun apply() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || unavailable) return
        if (seeThrough == 0 || view.width == 0 || view.height == 0) {
            view.setRenderEffect(null)
            return
        }
        runCatching { view.setRenderEffect(effect()) }.onFailure {
            // A GPU that cannot build the filter keeps the plain map.
            unavailable = true
            view.setRenderEffect(null)
            log("HUD: map look unavailable ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun release() {
        sample?.recycle()
        sample = null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun effect(): RenderEffect {
        val shader = (shader as RuntimeShader?) ?: RuntimeShader(MazdaHudMapLook.SHADER).also { this.shader = it }
        val width = view.width
        val height = view.height
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("background", (background shr 16 and 0xff) / 255f, (background shr 8 and 0xff) / 255f,
            (background and 0xff) / 255f)
        shader.setFloatUniform("seeThrough", seeThrough / 100f)
        shader.setFloatUniform("lines", if (style == MazdaHudSettings.MapStyle.LINES) 1f else 0f)
        shader.setFloatUniform("reach", MazdaHudMapLook.reach(width, height))
        // The effect keeps the uniforms it was made with: make a new one for every change.
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    private companion object {
        const val SAMPLE_WIDTH = 24
        const val SAMPLE_HEIGHT = 18
        const val LOG_TOLERANCE = 24
    }
}
