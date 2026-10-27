package com.liquidgets

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import java.io.File
import kotlin.math.max

enum class GlassStyle { CLEAR, TINTED }

/** DARK = dark glass with white content, LIGHT = bright glass with dark content. */
enum class Appearance { AUTO, LIGHT, DARK }

data class GlassSettings(
    val style: GlassStyle = GlassStyle.CLEAR,
    val tint: Int = 0xFF0A84FF.toInt(),
    val thickness: Float = 0.5f,
    val cornerDp: Float = 24f,
    val appearance: Appearance = Appearance.AUTO,
    val realGlass: Boolean = false,
) {
    /** True when widget text and icons should be white. */
    fun contentLight(c: Context): Boolean = when {
        style == GlassStyle.TINTED || appearance == Appearance.DARK -> true
        appearance == Appearance.LIGHT -> false
        Build.VERSION.SDK_INT >= 27 -> runCatching {
            val primary = WallpaperManager.getInstance(c)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
            primary == null || Color.luminance(primary) < 0.6f
        }.getOrDefault(true)
        else -> true
    }

    fun save(c: Context) = prefs(c).edit()
        .putString("style", style.name)
        .putInt("tint", tint)
        .putFloat("thickness", thickness)
        .putFloat("corner", cornerDp)
        .putString("appearance", appearance.name)
        .putBoolean("real", realGlass)
        .apply()

    companion object {
        const val KEY_IMAGE = "image"

        fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("glass", Context.MODE_PRIVATE)

        fun load(c: Context): GlassSettings = prefs(c).run {
            GlassSettings(
                style = GlassStyle.entries.firstOrNull { it.name == getString("style", null) } ?: GlassStyle.CLEAR,
                tint = getInt("tint", 0xFF0A84FF.toInt()),
                thickness = getFloat("thickness", 0.5f),
                cornerDp = getFloat("corner", 24f),
                appearance = Appearance.entries.firstOrNull { it.name == getString("appearance", null) } ?: Appearance.AUTO,
                realGlass = getBoolean("real", false),
            )
        }
    }
}

/** Where each widget sits on screen, learned from the launcher's tap bounds, so the wallpaper can draw glass under it. */
object WidgetRects {
    data class Entry(val rect: RectF, val page: Int)

    private fun key(id: Int) = "rect_$id"

    /** Returns true when the position changed. */
    fun record(c: Context, id: Int, r: Rect): Boolean {
        val value = "${r.left},${r.top},${r.right},${r.bottom},${GlassWallpaper.page}"
        val p = GlassSettings.prefs(c)
        if (p.getString(key(id), null) == value) return false
        p.edit().putString(key(id), value).apply()
        return true
    }

    fun remove(c: Context, id: Int) = GlassSettings.prefs(c).edit().remove(key(id)).apply()

    fun has(c: Context, id: Int) = GlassSettings.prefs(c).contains(key(id))

    fun all(c: Context): List<Entry> = GlassSettings.prefs(c).all.mapNotNull { (k, v) ->
        if (!k.startsWith("rect_") || v !is String) return@mapNotNull null
        val n = v.split(',').mapNotNull { it.toIntOrNull() }
        if (n.size != 5) null else Entry(RectF(n[0].toFloat(), n[1].toFloat(), n[2].toFloat(), n[3].toFloat()), n[4])
    }
}

/** The photo behind the real-glass wallpaper. */
object Wallpapers {
    private fun file(c: Context) = File(c.filesDir, "wallpaper.jpg")

    fun save(c: Context, uri: Uri) {
        c.contentResolver.openInputStream(uri)?.use { input -> file(c).outputStream().use { input.copyTo(it) } }
        GlassSettings.prefs(c).edit().putLong(GlassSettings.KEY_IMAGE, System.currentTimeMillis()).apply()
    }

    /** The photo (or the default art) center-cropped to exactly [w] x [h]. */
    fun load(c: Context, w: Int, h: Int): Bitmap {
        val f = file(c)
        val src = if (f.exists()) runCatching { decode(f, w, h) }.getOrNull() else null
        return GlassPainter.centerCrop(src ?: defaultArt(w, h), w, h)
    }

    private fun decode(f: File, w: Int, h: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = max(w.toFloat() / info.size.width, h.toFloat() / info.size.height).coerceAtMost(1f)
                d.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Colourful blobs on deep navy, in the spirit of the iOS 26 default wallpapers. */
    private fun defaultArt(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(0xFF0B1026.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        listOf(
            Triple(0.15f, 0.2f, 0xFF3A7BFF), Triple(0.9f, 0.35f, 0xFFB44CFF),
            Triple(0.25f, 0.75f, 0xFFFF7A45), Triple(0.85f, 0.9f, 0xFF19C3B1),
        ).forEach { (x, y, color) ->
            p.shader = RadialGradient(x * w, y * h, 0.75f * w, color.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        return bmp
    }
}
