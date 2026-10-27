package com.liquidgets

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.location.Geocoder
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Invisible hop between a widget tap and its target: records where the widget is on screen, then opens the target. */
class TapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val bounds = intent.sourceBounds
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID && bounds != null && WidgetRects.record(this, id, bounds)) {
            Widgets.refreshAll(this)
        }
        @Suppress("DEPRECATION")
        val pi = intent.getParcelableExtra<PendingIntent>(TARGET_PI)
        @Suppress("DEPRECATION")
        val target = intent.getParcelableExtra<Intent>(TARGET)
        runCatching {
            when {
                pi != null -> {
                    val options = ActivityOptions.makeBasic()
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(this, 0, null, null, null, null, options.toBundle())
                }
                target != null -> startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.onFailure { startActivity(Intent(this, MainActivity::class.java)) }
        finish()
    }

    companion object {
        const val TARGET = "target"
        const val TARGET_PI = "target_pi"
    }
}

object Media {
    const val PREV = "com.liquidgets.PREV"
    const val PLAY = "com.liquidgets.PLAY"
    const val NEXT = "com.liquidgets.NEXT"

    fun allowed(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    /** The playing session, or the most recent one. */
    fun controller(c: Context): MediaController? {
        if (!allowed(c)) return null
        return runCatching {
            val list = c.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(c, MediaWatcher::class.java))
            list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        }.getOrNull()
    }

    fun action(c: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(c, 0, Intent(c, MusicReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    fun appName(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    /** Album art, center-cropped and rounded, since RemoteViews can't clip. */
    fun roundArt(c: Context, art: Bitmap): Bitmap {
        val px = c.dp(56f).roundToInt()
        val src = GlassPainter.centerCrop(art.copy(Bitmap.Config.ARGB_8888, false), px, px)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawPath(GlassPainter.squircle(RectF(0f, 0f, px.toFloat(), px.toFloat()), c.dp(12f)), p)
        return out
    }
}

class MusicReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val mc = Media.controller(c) ?: return
        val t = mc.transportControls
        when (intent.action) {
            Media.PREV -> t.skipToPrevious()
            Media.NEXT -> t.skipToNext()
            Media.PLAY -> if (mc.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
        }
        // The listener service normally refreshes us; this covers the moments it isn't bound.
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            Widgets.refresh(c, MusicWidget::class.java)
            pending.finish()
        }, 500)
    }
}

/** Notification access is what lets us see other apps' media sessions; we never read notifications themselves. */
class MediaWatcher : NotificationListenerService() {
    private var controller: MediaController? = null
    private var lastKey = ""
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
        override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
        override fun onSessionDestroyed() = watch()
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { watch() }

    override fun onListenerConnected() {
        getSystemService(MediaSessionManager::class.java).addOnActiveSessionsChangedListener(sessions, ComponentName(this, MediaWatcher::class.java))
        watch()
        BatteryWatcher.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessions)
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun watch() {
        val next = Media.controller(this)
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback)
        }
        changed()
    }

    private fun changed() {
        val mc = controller
        val key = "${mc?.packageName}|${mc?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}|${mc?.playbackState?.state}"
        if (key == lastKey) return
        lastKey = key
        Widgets.refresh(this, MusicWidget::class.java)
    }
}

/** Live battery updates while our process is alive (wallpaper or music access); the 30 min widget update covers the rest. */
object BatteryWatcher {
    private var started = false
    private var last = ""

    fun start(c: Context) {
        if (started) return
        started = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val key = "${i.getIntExtra("level", -1)}/${i.getIntExtra("status", -1)}"
                if (key == last) return
                last = key
                Widgets.refresh(ctx, BatteryWidget::class.java)
            }
        }
        ContextCompat.registerReceiver(c, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

/** Open-Meteo: free, no API key. */
object Weather {
    fun prefs(c: Context) = c.getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun stale(c: Context) = System.currentTimeMillis() - prefs(c).getLong("time", 0) > 20 * 60 * 1000

    fun fetch(c: Context): Boolean {
        val p = prefs(c)
        if (!p.contains("lat")) return false
        val fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.getFloat("lat", 0f)}&longitude=${p.getFloat("lon", 0f)}" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1" +
            if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        return runCatching {
            val con = URL(url).openConnection() as HttpURLConnection
            con.connectTimeout = 8000
            con.readTimeout = 8000
            val json = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            val cur = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            p.edit()
                .putInt("temp", cur.getDouble("temperature_2m").roundToInt())
                .putInt("code", cur.getInt("weather_code"))
                .putBoolean("day", cur.getInt("is_day") == 1)
                .putInt("hi", daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt())
                .putInt("lo", daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt())
                .putLong("time", System.currentTimeMillis())
                .apply()
            true
        }.getOrDefault(false)
    }

    fun describe(code: Int) = when (code) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Cloudy"
        45, 48 -> "Foggy"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow Showers"
        in 95..99 -> "Thunderstorms"
        else -> "--"
    }

    /** Takes the phone's last known location (no background location needed), names the city, refreshes the widgets. */
    fun updateLocation(c: Context) {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = c.getSystemService(LocationManager::class.java)
        val loc = lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (loc != null) {
            save(c, loc.latitude, loc.longitude)
        } else if (Build.VERSION.SDK_INT >= 30) {
            val provider = if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
            runCatching { lm.getCurrentLocation(provider, null, c.mainExecutor) { it?.let { l -> save(c, l.latitude, l.longitude) } } }
        }
    }

    private fun save(c: Context, lat: Double, lon: Double) = thread {
        @Suppress("DEPRECATION")
        val city = runCatching { Geocoder(c).getFromLocation(lat, lon, 1)?.firstOrNull()?.locality }.getOrNull()
        prefs(c).edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).putString("city", city).putLong("time", 0).apply()
        fetch(c)
        Handler(Looper.getMainLooper()).post { Widgets.refresh(c, WeatherWidget::class.java) }
    }
}
