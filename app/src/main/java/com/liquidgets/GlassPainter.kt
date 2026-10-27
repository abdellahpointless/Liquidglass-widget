package com.liquidgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws the glass. Two flavours:
 *  - [drawFrost]: a baked look (tint, sheen, edge glow, specular rim) that works on any canvas and any Android version.
 *  - [RealGlass]: the refracting lens shader, API 33+, used by the live wallpaper and the in-app preview.
 */
object GlassPainter {
    /** Light comes from the top-left, like the iOS default. */
    const val LIGHT_ANGLE = (PI * 1.25).toFloat()
    const val BLUR_SCALE = 4

    /** iOS-style continuous ("squircle") rounded rect, after figma-squircle's corner smoothing. */
    fun squircle(r: RectF, radius: Float, smoothing: Float = 0.6f): Path {
        val budget = min(r.width(), r.height()) / 2f
        val rad = radius.coerceIn(0f, budget)
        val path = Path()
        if (rad <= 0f) return path.apply { addRect(r, Path.Direction.CW) }
        val p = min((1 + smoothing) * rad, budget)
        val s = (p / rad - 1f).coerceIn(0f, smoothing)
        val arcMeasure = 90f * (1 - s)
        val arcLen = sin(Math.toRadians(arcMeasure / 2.0)).toFloat() * rad * sqrt(2f)
        val alpha = (90f - arcMeasure) / 2f
        val p3p4 = rad * tan(Math.toRadians(alpha / 2.0)).toFloat()
        val beta = 45f * s
        val c = p3p4 * cos(Math.toRadians(beta.toDouble())).toFloat()
        val d = c * tan(Math.toRadians(beta.toDouble())).toFloat()
        val b = (p - arcLen - c - d) / 3f
        val a = 2 * b
        // Corners clockwise from top-right: corner point, travel direction u, next-edge direction v, arc mid-angle.
        val corners = listOf(
            floatArrayOf(r.right, r.top, 1f, 0f, 0f, 1f, -45f),
            floatArrayOf(r.right, r.bottom, 0f, 1f, -1f, 0f, 45f),
            floatArrayOf(r.left, r.bottom, -1f, 0f, 0f, -1f, 135f),
            floatArrayOf(r.left, r.top, 0f, -1f, 1f, 0f, 225f),
        )
        corners.forEachIndexed { i, k ->
            val (cx, cy, ux, uy, vx, vy) = k
            val mid = k[6]
            fun x(du: Float, dv: Float) = cx + ux * du + vx * dv
            fun y(du: Float, dv: Float) = cy + uy * du + vy * dv
            if (i == 0) path.moveTo(x(-p, 0f), y(-p, 0f)) else path.lineTo(x(-p, 0f), y(-p, 0f))
            path.cubicTo(x(-p + a, 0f), y(-p + a, 0f), x(-p + a + b, 0f), y(-p + a + b, 0f), x(-p + a + b + c, d), y(-p + a + b + c, d))
            val ocx = x(-rad, rad)
            val ocy = y(-rad, rad)
            path.arcTo(RectF(ocx - rad, ocy - rad, ocx + rad, ocy + rad), mid - arcMeasure / 2f, arcMeasure, false)
            path.cubicTo(x(0f, p - a - b), y(0f, p - a - b), x(0f, p - a), y(0f, p - a), x(0f, p), y(0f, p))
        }
        path.close()
        return path
    }

    private operator fun FloatArray.component6() = this[5]

    fun fillColor(s: GlassSettings, contentLight: Boolean, real: Boolean): Int = when {
        s.style == GlassStyle.TINTED -> (s.tint and 0xFFFFFF) or ((if (real) 90 else 125) shl 24)
        contentLight -> Color.argb(if (real) 30 else 46, 0, 0, 0)
        else -> Color.argb(if (real) 70 else 120, 255, 255, 255)
    }

    /** The frosted look. Edge glow needs a software canvas (MaskFilter); the rest works anywhere. */
    fun drawFrost(cv: Canvas, rect: RectF, s: GlassSettings, contentLight: Boolean, density: Float, angle: Float, backdrop: Bitmap? = null) {
        val path = squircle(rect, s.cornerDp * density)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (backdrop != null) {
            p.shader = BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(BLUR_SCALE.toFloat(), BLUR_SCALE.toFloat()) })
            }
            p.isFilterBitmap = true
            cv.drawPath(path, p)
            p.shader = null
        }
        p.color = fillColor(s, contentLight, backdrop != null)
        cv.drawPath(path, p)

        p.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.6f, Color.argb(30, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawPath(path, p)
        p.shader = null

        val t = s.thickness
        cv.save()
        cv.clipPath(path)
        p.style = Paint.Style.STROKE
        p.strokeWidth = (4f + 14f * t) * density
        p.color = Color.argb((18 + 50 * t).roundToInt(), 255, 255, 255)
        p.maskFilter = BlurMaskFilter(p.strokeWidth * 0.6f, BlurMaskFilter.Blur.NORMAL)
        cv.drawPath(path, p)
        p.maskFilter = null
        cv.restore()

        val w = (0.8f + 0.8f * t) * density
        val inner = RectF(rect).apply { inset(w / 2, w / 2) }
        val dx = cos(angle)
        val dy = sin(angle)
        val ext = max(rect.width(), rect.height()) / 2f
        p.strokeWidth = w
        p.shader = LinearGradient(
            rect.centerX() + dx * ext, rect.centerY() + dy * ext, rect.centerX() - dx * ext, rect.centerY() - dy * ext,
            intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(150, 255, 255, 255)),
            floatArrayOf(0f, 0.4f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        cv.drawPath(squircle(inner, s.cornerDp * density - w / 2), p)
    }

    /** A soft drop shadow outside [rect] only, so it never darkens the glass itself. */
    fun drawShadow(cv: Canvas, rect: RectF, s: GlassSettings, density: Float, blur: Float, alpha: Int) {
        val path = squircle(rect, s.cornerDp * density)
        cv.save()
        cv.clipOutPath(path)
        cv.translate(0f, blur / 3f)
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 0, 0, 0)
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        })
        cv.restore()
    }

    /** The whole widget background for the frosted (no wallpaper) mode. */
    fun widgetBitmap(c: Context, wDp: Float, hDp: Float, s: GlassSettings, contentLight: Boolean): Bitmap {
        // Cap the size so a big widget stays well under the RemoteViews bitmap budget.
        val d = min(c.resources.displayMetrics.density, 1100f / max(wDp, hDp))
        val w = (wDp * d).roundToInt().coerceAtLeast(1)
        val h = (hDp * d).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val m = 3f * d
        val rect = RectF(m, m, w - m, h - m)
        drawShadow(cv, rect, s, d, 3f * d, 50)
        drawFrost(cv, rect, s, contentLight, d, LIGHT_ANGLE)
        return bmp
    }

    /** Shadow for the real-glass wallpaper, pre-rendered because hardware canvases ignore blur mask filters. */
    fun shadowBitmap(w: Float, h: Float, s: GlassSettings, density: Float): Bitmap {
        val m = 12f * density
        val bmp = Bitmap.createBitmap((w + 2 * m).roundToInt(), (h + 2 * m).roundToInt(), Bitmap.Config.ARGB_8888)
        drawShadow(Canvas(bmp), RectF(m, m, m + w, m + h), s, density, 8f * density, 70)
        return bmp
    }

    fun shadowMargin(density: Float) = 12f * density

    fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).roundToInt().coerceIn(1, src.width)
        val ch = (h / scale).roundToInt().coerceIn(1, src.height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val x = (src.width - cw) / 2
        val y = (src.height - ch) / 2
        Canvas(out).drawBitmap(src, Rect(x, y, x + cw, y + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Quarter-size, three-pass box blur (close to gaussian). Draw it scaled up by [BLUR_SCALE]. */
    fun blur(src: Bitmap): Bitmap {
        val w = (src.width / BLUR_SCALE).coerceAtLeast(1)
        val h = (src.height / BLUR_SCALE).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        small.getPixels(a, 0, w, 0, 0, w, h)
        repeat(3) {
            boxPass(a, b, w, h, 3, true)
            boxPass(b, a, w, h, 3, false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (l in 0 until lines) {
            val base = if (horizontal) l * w else l
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val px = src[base + k.coerceIn(0, len - 1) * step]
                sr += px shr 16 and 255; sg += px shr 8 and 255; sb += px and 255
            }
            for (i in 0 until len) {
                dst[base + i * step] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[base + (i + r + 1).coerceAtMost(len - 1) * step]
                val rem = src[base + (i - r).coerceAtLeast(0) * step]
                sr += (add shr 16 and 255) - (rem shr 16 and 255)
                sg += (add shr 8 and 255) - (rem shr 8 and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }
}

/**
 * The refracting lens. Shape math ported from AndroidLiquidGlassView (QmDeve, MIT) and Backdrop (Kyant, MIT):
 * a rounded-rect SDF, a circular bevel `1 - sqrt(1 - x^2)` that bends the backdrop near the rim,
 * RGB dispersion, vibrancy, and a specular rim lit from [angle].
 */
@RequiresApi(33)
class RealGlass {
    private val shader = RuntimeShader(AGSL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sharp: Bitmap? = null
    private var blurred: Bitmap? = null

    fun draw(
        cv: Canvas, rect: RectF, s: GlassSettings, contentLight: Boolean, density: Float,
        angle: Float, appear: Float, sharp: Bitmap, blurred: Bitmap,
    ) {
        if (sharp !== this.sharp) {
            this.sharp = sharp
            shader.setInputShader("sharp", BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        }
        if (blurred !== this.blurred) {
            this.blurred = blurred
            shader.setInputShader("blurred", BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(GlassPainter.BLUR_SCALE.toFloat(), GlassPainter.BLUR_SCALE.toFloat()) })
            })
        }
        val t = s.thickness
        shader.setFloatUniform("rect", rect.left, rect.top, rect.right, rect.bottom)
        shader.setFloatUniform("radius", min(s.cornerDp * density, min(rect.width(), rect.height()) / 2f))
        shader.setFloatUniform("rim", (10f + 26f * t) * density)
        shader.setFloatUniform("refraction", (8f + 36f * t) * density * appear)
        shader.setFloatUniform("dispersion", 0.15f + 0.35f * t)
        shader.setFloatUniform("frost", 0.25f + 0.45f * t)
        shader.setColorUniform("tint", GlassPainter.fillColor(s, contentLight, true))
        shader.setFloatUniform("light", cos(angle), sin(angle))
        shader.setFloatUniform("hl", 1.4f * density)
        shader.setFloatUniform("appear", appear.coerceIn(0f, 1.2f))
        paint.shader = shader
        cv.drawRect(rect.left - 2f, rect.top - 2f, rect.right + 2f, rect.bottom + 2f, paint)
    }

    private companion object {
        const val AGSL = """
uniform shader sharp;
uniform shader blurred;
uniform float4 rect;
uniform float radius;
uniform float rim;
uniform float refraction;
uniform float dispersion;
uniform float frost;
layout(color) uniform half4 tint;
uniform float2 light;
uniform float hl;
uniform float appear;

float sdRR(float2 p, float2 hs, float r) {
    float2 q = abs(p) - hs + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half3 backdrop(float2 c) {
    return mix(sharp.eval(c).rgb, blurred.eval(c).rgb, frost);
}

half4 main(float2 coord) {
    float2 hs = (rect.zw - rect.xy) * 0.5;
    float2 p = coord - (rect.xy + hs);
    float sd = sdRR(p, hs, radius);
    float cover = clamp(0.5 - sd, 0.0, 1.0);
    if (cover <= 0.0) return half4(0.0);

    // Normals from a rounder copy of the shape so the bevel bends smoothly.
    float r2 = min(max(radius * 1.5, 30.0), min(hs.x, hs.y));
    float2 e = float2(1.0, 0.0);
    float2 n = float2(sdRR(p + e.xy, hs, r2) - sdRR(p - e.xy, hs, r2),
                      sdRR(p + e.yx, hs, r2) - sdRR(p - e.yx, hs, r2));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0);

    float x = clamp(1.0 + sd / rim, 0.0, 1.0);
    float bend = (1.0 - sqrt(1.0 - x * x)) * refraction;
    float2 rc = coord - n * bend;
    half3 col;
    if (bend > 0.5) {
        float2 dv = n * bend * dispersion;
        col = half3(backdrop(rc + dv).r, backdrop(rc).g, backdrop(rc - dv).b);
    } else {
        col = backdrop(rc);
    }
    half y = dot(col, half3(0.2126, 0.7152, 0.0722));
    col = mix(half3(y), col, 1.35);
    col = mix(col, tint.rgb, tint.a);

    float spec = pow(abs(dot(n, light)), 1.6);
    float edge = 1.0 - smoothstep(0.0, hl, -sd);
    col += half3(edge * (0.25 + 0.75 * spec) * 0.85 * appear);
    col += half3(x * x * spec * 0.10 * appear);
    col = clamp(col, 0.0, 1.0);
    return half4(col * cover, cover);
}
"""
    }
}
