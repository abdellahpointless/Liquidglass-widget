package com.liquidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Every Liquidgets widget: a glass background image under ordinary RemoteViews content. */
abstract class GlassWidget : AppWidgetProvider() {

    /** Builds the content for one widget size. [fg] is the white or dark content colour. */
    abstract fun content(c: Context, id: Int, size: SizeF, fg: Int): RemoteViews

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = render(c, m, ids)

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, options: Bundle) = render(c, m, intArrayOf(id))

    override fun onDeleted(c: Context, ids: IntArray) = ids.forEach { WidgetRects.remove(c, it) }

    fun render(c: Context, m: AppWidgetManager, ids: IntArray) {
        val s = GlassSettings.load(c)
        val light = s.contentLight(c)
        val fg = if (light) Color.WHITE else 0xFF1C1C1E.toInt()
        val wallpaperGlass = s.realGlass && GlassWallpaper.isActive(c)
        for (id in ids) {
            // Once the live wallpaper knows where this widget is, it draws the real glass and the widget goes see-through.
            val bake = !(wallpaperGlass && WidgetRects.has(c, id))
            fun build(size: SizeF) = content(c, id, size, fg).apply {
                if (bake) setImageViewBitmap(R.id.glass, GlassPainter.widgetBitmap(c, size.width, size.height, s, light))
                else setImageViewResource(R.id.glass, android.R.color.transparent)
            }
            val sizes = Widgets.sizes(c, m, id)
            val views = if (Build.VERSION.SDK_INT >= 31 && sizes.size > 1) RemoteViews(sizes.associateWith { build(it) }) else build(sizes.first())
            m.updateAppWidget(id, views)
        }
    }
}

object Widgets {
    val ALL = listOf(ClockWidget::class.java, CalendarWidget::class.java, WeatherWidget::class.java, BatteryWidget::class.java, MusicWidget::class.java)

    fun refresh(c: Context, cls: Class<out GlassWidget>) {
        val m = AppWidgetManager.getInstance(c)
        val ids = m.getAppWidgetIds(ComponentName(c, cls))
        if (ids.isNotEmpty()) cls.getDeclaredConstructor().newInstance().render(c, m, ids)
    }

    fun refreshAll(c: Context) = ALL.forEach { refresh(c, it) }

    fun sizes(c: Context, m: AppWidgetManager, id: Int): List<SizeF> {
        val o = m.getAppWidgetOptions(id)
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val list = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!list.isNullOrEmpty()) return list
        }
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (w > 0 && h > 0) return listOf(SizeF(w.toFloat(), h.toFloat()))
        val d = c.resources.displayMetrics.density
        val info = m.getAppWidgetInfo(id)
        return listOf(if (info != null) SizeF(info.minWidth / d, info.minHeight / d) else SizeF(160f, 160f))
    }

    private val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    /** Taps go through [TapActivity] so we learn the widget's on-screen bounds; the PendingIntent must be mutable to receive them. */
    fun tap(c: Context, id: Int, target: Intent): PendingIntent = PendingIntent.getActivity(
        c, id,
        Intent(c, TapActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(TapActivity.TARGET, target),
        PendingIntent.FLAG_UPDATE_CURRENT or mutable,
    )

    fun tap(c: Context, id: Int, target: PendingIntent): PendingIntent = PendingIntent.getActivity(
        c, id,
        Intent(c, TapActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(TapActivity.TARGET_PI, target),
        PendingIntent.FLAG_UPDATE_CURRENT or mutable,
    )

    fun openApp(c: Context) = Intent(c, MainActivity::class.java)

    fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha shl 24)
}

class ClockWidget : GlassWidget() {
    override fun content(c: Context, id: Int, size: SizeF, fg: Int): RemoteViews {
        val digital = size.width >= 220f
        return RemoteViews(c.packageName, if (digital) R.layout.widget_clock_digital else R.layout.widget_clock).apply {
            if (digital) {
                setTextColor(R.id.time, fg)
                setTextColor(R.id.date, Widgets.withAlpha(fg, 180))
            } else if (Build.VERSION.SDK_INT >= 31) {
                val tint = ColorStateList.valueOf(fg)
                setColorStateList(R.id.clock, "setDialTintList", tint)
                setColorStateList(R.id.clock, "setHourHandTintList", tint)
                setColorStateList(R.id.clock, "setMinuteHandTintList", tint)
            }
            setOnClickPendingIntent(android.R.id.background, Widgets.tap(c, id, Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        }
    }
}

class CalendarWidget : GlassWidget() {
    override fun content(c: Context, id: Int, size: SizeF, fg: Int) = RemoteViews(c.packageName, R.layout.widget_calendar).apply {
        setTextColor(R.id.weekday, Widgets.withAlpha(fg, 180))
        setTextColor(R.id.day, fg)
        setTextColor(R.id.month, Widgets.withAlpha(fg, 180))
        val today = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").let {
            ContentUris.appendId(it, System.currentTimeMillis()).build()
        }
        setOnClickPendingIntent(android.R.id.background, Widgets.tap(c, id, Intent(Intent.ACTION_VIEW, today)))
    }
}

class WeatherWidget : GlassWidget() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        render(c, m, ids)
        if (!Weather.stale(c)) return
        val pending = goAsync()
        thread {
            try {
                if (Weather.fetch(c)) render(c, m, ids)
            } finally {
                pending.finish()
            }
        }
    }

    override fun content(c: Context, id: Int, size: SizeF, fg: Int) = RemoteViews(c.packageName, R.layout.widget_weather).apply {
        listOf(R.id.city, R.id.temp, R.id.condition, R.id.range).forEach { setTextColor(it, fg) }
        setInt(R.id.arrow, "setColorFilter", fg)
        val w = Weather.prefs(c)
        if (!w.contains("temp")) {
            setTextViewText(R.id.city, "Weather")
            setTextViewText(R.id.temp, "--°")
            setTextViewText(R.id.condition, "Open Liquidgets")
            setTextViewText(R.id.range, "to set your location")
            setViewVisibility(R.id.arrow, View.GONE)
        } else {
            val code = w.getInt("code", 0)
            setTextViewText(R.id.city, w.getString("city", null) ?: "My Location")
            setTextViewText(R.id.temp, "${w.getInt("temp", 0)}°")
            setTextViewText(R.id.condition, Weather.describe(code))
            setTextViewText(R.id.range, "H:${w.getInt("hi", 0)}°  L:${w.getInt("lo", 0)}°")
            val px = (22 * c.resources.displayMetrics.density).roundToInt()
            setImageViewBitmap(R.id.icon, WeatherIcon.bitmap(code, w.getBoolean("day", true), px))
        }
        setOnClickPendingIntent(android.R.id.background, Widgets.tap(c, id, Widgets.openApp(c)))
    }
}

class BatteryWidget : GlassWidget() {
    override fun content(c: Context, id: Int, size: SizeF, fg: Int) = RemoteViews(c.packageName, R.layout.widget_battery).apply {
        val bm = c.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val charging = bm.isCharging
        val saver = c.getSystemService(PowerManager::class.java).isPowerSaveMode
        val ring = when {
            saver -> 0xFFFFD60A.toInt()
            level <= 20 && !charging -> 0xFFFF453A.toInt()
            else -> 0xFF30D158.toInt()
        }
        val px = (84 * c.resources.displayMetrics.density).roundToInt()
        setImageViewBitmap(R.id.ring, batteryRing(px, level, ring, fg, charging))
        setTextViewText(R.id.percent, "$level%")
        setTextColor(R.id.percent, fg)
        setOnClickPendingIntent(android.R.id.background, Widgets.tap(c, id, Intent(Intent.ACTION_POWER_USAGE_SUMMARY)))
    }

    /** iOS Batteries style: a thick ring with a phone glyph inside and a bolt on top while charging. */
    private fun batteryRing(px: Int, level: Int, ring: Int, fg: Int, charging: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val stroke = px * 0.09f
        val r = RectF(stroke / 2, stroke / 2, px - stroke / 2, px - stroke / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
        p.color = Widgets.withAlpha(fg, 50)
        cv.drawArc(r, 0f, 360f, false, p)
        p.color = ring
        cv.drawArc(r, -90f, 360f * level / 100f, false, p)
        val u = px / 84f
        p.color = fg
        p.strokeWidth = 2.2f * u
        cv.drawRoundRect(RectF(px / 2f - 9 * u, px / 2f - 16 * u, px / 2f + 9 * u, px / 2f + 16 * u), 4 * u, 4 * u, p)
        if (charging) {
            p.style = Paint.Style.FILL
            val cx = px / 2f
            cv.drawPath(Path().apply {
                moveTo(cx + 1.5f * u, -1f * u); lineTo(cx - 4 * u, 6 * u); lineTo(cx - 0.5f * u, 6 * u)
                lineTo(cx - 1.5f * u, 12 * u); lineTo(cx + 4 * u, 5 * u); lineTo(cx + 0.5f * u, 5 * u); close()
            }, p)
        }
        return bmp
    }
}

class MusicWidget : GlassWidget() {
    override fun content(c: Context, id: Int, size: SizeF, fg: Int) = RemoteViews(c.packageName, R.layout.widget_music).apply {
        setTextColor(R.id.title, fg)
        setTextColor(R.id.artist, Widgets.withAlpha(fg, 180))
        listOf(R.id.prev, R.id.play, R.id.next).forEach { setInt(it, "setColorFilter", fg) }
        if (!Media.allowed(c)) {
            setTextViewText(R.id.title, "Music")
            setTextViewText(R.id.artist, "Tap to allow access")
            setViewVisibility(R.id.controls, View.GONE)
            setOnClickPendingIntent(android.R.id.background, Widgets.tap(c, id, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
            return@apply
        }
        val mc = Media.controller(c)
        val md = mc?.metadata
        setTextViewText(R.id.title, md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Not Playing")
        setTextViewText(R.id.artist, md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: mc?.let { Media.appName(c, it.packageName) } ?: "")
        val art = md?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        if (art != null) setImageViewBitmap(R.id.art, Media.roundArt(c, art)) else setImageViewResource(R.id.art, R.drawable.art_placeholder)
        val playing = mc?.playbackState?.state == PlaybackState.STATE_PLAYING
        setImageViewResource(R.id.play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        setOnClickPendingIntent(R.id.prev, Media.action(c, Media.PREV))
        setOnClickPendingIntent(R.id.play, Media.action(c, Media.PLAY))
        setOnClickPendingIntent(R.id.next, Media.action(c, Media.NEXT))
        val open = mc?.sessionActivity
        setOnClickPendingIntent(android.R.id.background, if (open != null) Widgets.tap(c, id, open) else Widgets.tap(c, id, Widgets.openApp(c)))
    }
}

/** Multicolour weather glyphs drawn in a 24-unit box, close to the iOS weather symbols. */
object WeatherIcon {
    fun bitmap(code: Int, day: Boolean, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(px / 24f, px / 24f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun sun(cx: Float, cy: Float, r: Float) {
            p.color = 0xFFFFD60A.toInt()
            cv.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 8) {
                val a = i * PI / 4
                cv.drawLine(cx + cos(a).toFloat() * (r + 1.8f), cy + sin(a).toFloat() * (r + 1.8f), cx + cos(a).toFloat() * (r + 3.4f), cy + sin(a).toFloat() * (r + 3.4f), p)
            }
            p.style = Paint.Style.FILL
        }
        fun moon(cx: Float, cy: Float, r: Float) {
            p.color = 0xFFE5E5EA.toInt()
            val m = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
            m.op(Path().apply { addCircle(cx + r * 0.6f, cy - r * 0.5f, r * 0.85f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
            cv.drawPath(m, p)
        }
        fun cloud(dy: Float = 0f) {
            p.color = Color.WHITE
            cv.drawCircle(9f, 13f + dy, 4.5f, p)
            cv.drawCircle(14f, 11f + dy, 5.5f, p)
            cv.drawCircle(17.5f, 14f + dy, 3.5f, p)
            cv.drawRoundRect(RectF(4.5f, 12f + dy, 21f, 18.5f + dy), 3.2f, 3.2f, p)
        }
        fun streaks(color: Int, dots: Boolean) {
            p.color = color
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.strokeCap = Paint.Cap.ROUND
            for (x in listOf(8f, 12f, 16f)) {
                if (dots) cv.drawPoint(x, 19.5f, p.apply { strokeWidth = 2.2f }) else cv.drawLine(x, 18f, x - 1f, 21f, p)
            }
            p.style = Paint.Style.FILL
        }
        when (code) {
            0, 1 -> if (day) sun(12f, 12f, 5f) else moon(12f, 12f, 7f)
            2 -> { if (day) sun(8f, 8f, 3.6f) else moon(8f, 8f, 5f); cloud(2f) }
            45, 48 -> {
                cloud(-3f)
                p.color = 0xFFAEAEB2.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.strokeCap = Paint.Cap.ROUND
                cv.drawLine(5f, 19f, 19f, 19f, p); cv.drawLine(7f, 22f, 17f, 22f, p)
            }
            in 51..67, in 80..82 -> { cloud(-3f); streaks(0xFF64D2FF.toInt(), false) }
            in 71..77, 85, 86 -> { cloud(-3f); streaks(Color.WHITE, true) }
            in 95..99 -> {
                cloud(-3f)
                p.color = 0xFFFFD60A.toInt()
                cv.drawPath(Path().apply {
                    moveTo(12.5f, 15f); lineTo(9.5f, 19.5f); lineTo(12f, 19.5f); lineTo(10.5f, 23.5f)
                    lineTo(15f, 18f); lineTo(12.5f, 18f); lineTo(14f, 15f); close()
                }, p)
            }
            else -> cloud()
        }
        return bmp
    }
}

internal fun Context.dp(v: Float) = v * resources.displayMetrics.density
