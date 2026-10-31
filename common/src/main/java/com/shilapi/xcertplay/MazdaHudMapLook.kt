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
 * takes that colour away: 0 % is the iPhone's picture, 100 % removes it. On a dark (night) map the background's light
 * is subtracted from every pixel, so faint roads stay whole and darker areas (water) go black; on a light map the
 * colours near the background fade. Lines only ([MazdaHudSettings.MapStyle.LINES]) also fades parks, water and
 * buildings, keeps roads, labels and strong colours (the route, traffic, the position arrow), and draws them at full
 * brightness. On the whole map, road brightness ([MazdaHudSettings.mapRoadBoost]) lifts the same lines toward full
 * brightness and leaves the areas as they are: dimmed roads hardly showed on the glass in daylight (2026-10-31).
 * The background colour is the picture's most common colour ([backgroundColor]).
 */
internal object MazdaHudMapLook {
    /** How far apart two colours may be per channel (0-255) and still count as the same background. */
    const val BACKGROUND_TOLERANCE = 12

    /**
     * The map's background in [pixels] (ARGB, alpha ignored): their most common colour ([mostCommon]). Along a river
     * or a lake the water can outnumber the land for a while (Google Maps at night, 2026-10-21). On a dark map the
     * [previous] background is kept, per channel the brighter of the two, while it still covers [AREA_PERCENT] of the
     * picture: subtracting only the water would light the land up.
     */
    fun backgroundColor(pixels: IntArray, previous: Int? = null): Int {
        val found = mostCommon(pixels)
        if (previous == null || !isDark(found) || !isDark(previous)) return found
        val share = pixels.count { near(previous, it) }
        return if (share * 100 >= pixels.size * AREA_PERCENT) brighterOf(found, previous) else found
    }

    /** Whether a background is a night map's: the filter then subtracts it instead of fading the colours near it. */
    fun isDark(color: Int): Boolean =
        (color shr 16 and 0xff) * 299 + (color shr 8 and 0xff) * 587 + (color and 0xff) * 114 < DARK_LUMA * 1000

    /**
     * The most common colour of [pixels]: the colour with the most pixels within [BACKGROUND_TOLERANCE], averaged over
     * those pixels. Black when there are none.
     */
    fun mostCommon(pixels: IntArray): Int {
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

    private fun brighterOf(a: Int, b: Int): Int = BLACK or (max(a shr 16 and 0xff, b shr 16 and 0xff) shl 16) or
        (max(a shr 8 and 0xff, b shr 8 and 0xff) shl 8) or max(a and 0xff, b and 0xff)

    private fun near(a: Int, b: Int, tolerance: Int = BACKGROUND_TOLERANCE): Boolean =
        abs((a shr 16 and 0xff) - (b shr 16 and 0xff)) <= tolerance &&
            abs((a shr 8 and 0xff) - (b shr 8 and 0xff)) <= tolerance &&
            abs((a and 0xff) - (b and 0xff)) <= tolerance

    private const val BLACK = 0xff000000.toInt()
    /** Luma (0-255) below which a background is a night map's; Google Maps' night land is about 51. */
    private const val DARK_LUMA = 115
    /** How much of the picture an earlier background must still cover to stay part of the background. */
    const val AREA_PERCENT = 15
    private const val CHANGE_TOLERANCE = 3
    private const val MIN_REACH = 3f
    private const val REACH_SHARE = 0.035f

    /**
     * The filter on the GPU, over the map view's picture ("content", opaque video). It works out the light the HUD
     * should project and returns it premultiplied, as transparent as it is dark: the 2D HUD's black window and the
     * AR-HUD's transparent one both leave that unlit.
     */
    const val SHADER = """
        uniform shader content;
        uniform float2 size;
        uniform float3 background;
        uniform float darkMap;
        uniform float seeThrough;
        uniform float lines;
        uniform float roadBoost;
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

        float level(float3 c) {
            return max(c.r, max(c.g, c.b));
        }

        half4 main(float2 p) {
            float4 c = content.eval(p);
            if (c.a < 0.004) return half4(0.0);
            float3 rgb = c.rgb / c.a;
            float3 light;
            if (darkMap > 0.5) {
                // Take the background's glow off every pixel. A threshold cut faint roads into dashes on the car's
                // compressed map stream; subtracting keeps them whole, and darker water goes black. Below 0.05 is
                // compression noise and the faint fill of parks and buildings; the curve above it lifts the side
                // streets the subtraction dimmed.
                float3 above = clamp((rgb - background) / max(float3(1.0) - background, float3(0.05)), 0.0, 1.0);
                light = pow(clamp((above - 0.05) / 0.95, 0.0, 1.0), float3(0.6));
            } else {
                // A light map: fade the colours near its background along a gentle ramp, so noise on features close
                // to it (white roads are only about 0.09 away) dims them evenly instead of speckling them.
                light = rgb * clamp((apart(rgb, background) - 0.025) / 0.1, 0.0, 1.0);
            }
            if (lines > 0.5 || roadBoost > 0.004) {
                float d = reach * 0.7071;
                float thin = max(max(across(rgb, p, float2(reach, 0.0)), across(rgb, p, float2(0.0, reach))),
                    max(across(rgb, p, float2(d, d)), across(rgb, p, float2(d, -d))));
                float chroma = level(rgb) - min(rgb.r, min(rgb.g, rgb.b));
                // Strong colours (the route, traffic, the position arrow) stay at any width.
                float keep = max(smoothstep(0.02, 0.1, thin), smoothstep(0.3, 0.5, chroma));
                // Grey roads and dark labels hardly show on the glass: draw them at full brightness, keeping their
                // colour. Lines only drops everything else; the whole map lifts them by the road brightness.
                float3 bright = light / max(level(light), 0.25);
                light = lines > 0.5 ? bright * keep : mix(light, bright, roadBoost * keep);
            }
            float3 shown = mix(rgb, light, seeThrough);
            float alpha = mix(1.0, level(light), seeThrough);
            return half4(shown * c.a, alpha * c.a);
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
    private var roadBoost = 0
    private var background = 0xff000000.toInt()
    private var logged = background
    private var shader: Any? = null
    private var unavailable = false
    private var sample: Bitmap? = null

    /** The driver's choice; draws again only when it changed. */
    fun set(style: MazdaHudSettings.MapStyle, seeThrough: Int, roadBoost: Int) {
        if (style == this.style && seeThrough == this.seeThrough && roadBoost == this.roadBoost) return
        this.style = style
        this.seeThrough = seeThrough
        this.roadBoost = roadBoost
        log("HUD: map look style=$style seeThrough=$seeThrough% roads=$roadBoost%")
        apply()
    }

    /** Reads the map's background from a small copy of its current picture; draws again when it moved. */
    fun sampleBackground() {
        if (seeThrough == 0 || unavailable || !view.isAvailable) return
        val bitmap = sample ?: Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888).also { sample = it }
        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        runCatching { view.getBitmap(bitmap) }.getOrNull()?.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH,
            SAMPLE_HEIGHT) ?: return
        val found = MazdaHudMapLook.backgroundColor(pixels, previous = background)
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
        shader.setFloatUniform("darkMap", if (MazdaHudMapLook.isDark(background)) 1f else 0f)
        shader.setFloatUniform("seeThrough", seeThrough / 100f)
        shader.setFloatUniform("lines", if (style == MazdaHudSettings.MapStyle.LINES) 1f else 0f)
        shader.setFloatUniform("roadBoost", roadBoost / 100f)
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
