package com.liquidgets

import android.app.KeyguardManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * "Real glass" mode. Widgets can't run shaders, but a live wallpaper can: it draws the user's photo and,
 * at each widget's position, a refracting glass panel with a tilt-driven highlight. The widgets above it go see-through.
 */
class GlassWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = GlassEngine()

    companion object {
        /** Home screen page the launcher is showing, recorded with each widget tap. */
        @Volatile
        var page = 0

        fun isActive(c: Context) = WallpaperManager.getInstance(c).wallpaperInfo?.packageName == c.packageName
    }

    inner class GlassEngine : Engine(), SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {
        private val prefs = GlassSettings.prefs(this@GlassWallpaper)
        private var settings = GlassSettings.load(this@GlassWallpaper)
        private var contentLight = true
        private var rects = WidgetRects.all(this@GlassWallpaper)
        private var width = 0
        private var height = 0
        private var sharp: Bitmap? = null
        private var blurred: Bitmap? = null
        private val shadows = HashMap<String, Bitmap>()
        private val real = if (Build.VERSION.SDK_INT >= 33) RealGlass() else null
        private var pageF = 0f
        private var angle = GlassPainter.LIGHT_ANGLE
        private var targetAngle = angle
        private var appearStart = 0L
        private var appear = 0f
        private var scheduled = false
        private val frame = Choreographer.FrameCallback { scheduled = false; draw() }
        private val sensors get() = getSystemService(SensorManager::class.java)
        private val unlock = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = materialize()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs.registerOnSharedPreferenceChangeListener(this)
            ContextCompat.registerReceiver(this@GlassWallpaper, unlock, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            BatteryWatcher.start(applicationContext)
            if (!isPreview) Widgets.refreshAll(this@GlassWallpaper)
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            unregisterReceiver(unlock)
            sensors.unregisterListener(this)
            Choreographer.getInstance().removeFrameCallback(frame)
            // Bring the baked glass back if the user switched to another wallpaper.
            if (!isPreview) Handler(Looper.getMainLooper()).postDelayed({ Widgets.refreshAll(applicationContext) }, 1500)
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            loadImage()
            materialize()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                materialize()
            } else {
                sensors.unregisterListener(this)
            }
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            pageF = if (xStep > 0f) xOffset / xStep else 0f
            if (!isPreview) page = pageF.roundToInt()
            invalidate()
        }

        override fun onSensorChanged(e: SensorEvent) {
            // Tilting left/right swings the light around the rim, like iOS.
            targetAngle = GlassPainter.LIGHT_ANGLE - (e.values[0] / SensorManager.GRAVITY_EARTH) * 1.2f
            if (abs(targetAngle - angle) > 0.004f) invalidate()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
            settings = GlassSettings.load(this@GlassWallpaper)
            rects = WidgetRects.all(this@GlassWallpaper)
            if (key == GlassSettings.KEY_IMAGE) loadImage()
            contentLight = settings.contentLight(this@GlassWallpaper)
            shadows.clear()
            materialize()
        }

        @RequiresApi(27)
        override fun onComputeColors(): WallpaperColors? = sharp?.let { WallpaperColors.fromBitmap(it) }

        private fun loadImage() {
            if (width == 0 || height == 0) return
            val img = Wallpapers.load(this@GlassWallpaper, width, height)
            sharp = img
            blurred = GlassPainter.blur(img)
            shadows.clear()
            if (Build.VERSION.SDK_INT >= 27) notifyColorsChanged()
            contentLight = settings.contentLight(this@GlassWallpaper)
        }

        /** Glass appears by ramping its lensing up with a little overshoot, instead of fading in. */
        private fun materialize() {
            appearStart = SystemClock.uptimeMillis()
            invalidate()
        }

        private fun invalidate() {
            if (scheduled || !isVisible) return
            scheduled = true
            Choreographer.getInstance().postFrameCallback(frame)
        }

        private fun draw() {
            val sharp = sharp ?: return
            val blurred = blurred ?: return
            val t = ((SystemClock.uptimeMillis() - appearStart) / 550f).coerceIn(0f, 1f)
            appear = easeOutBack(t)
            angle += (targetAngle - angle) * 0.25f
            val holder = surfaceHolder
            val cv = (if (Build.VERSION.SDK_INT >= 33) holder.lockHardwareCanvas() else holder.lockCanvas()) ?: return
            try {
                cv.drawBitmap(sharp, 0f, 0f, null)
                val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
                if (settings.realGlass && !locked && !isPreview) {
                    val d = resources.displayMetrics.density
                    for (r in rects) {
                        val rect = RectF(r.rect).apply { offset((r.page - pageF) * width, 0f) }
                        if (rect.right < 0 || rect.left > width) continue
                        val shadow = shadows.getOrPut("${rect.width()}x${rect.height()}") { GlassPainter.shadowBitmap(rect.width(), rect.height(), settings, d) }
                        val m = GlassPainter.shadowMargin(d)
                        cv.drawBitmap(shadow, rect.left - m, rect.top - m, null)
                        if (real != null) real.draw(cv, rect, settings, contentLight, d, angle, appear, sharp, blurred)
                        else GlassPainter.drawFrost(cv, rect, settings, contentLight, d, angle, blurred)
                    }
                }
            } finally {
                holder.unlockCanvasAndPost(cv)
            }
            if (t < 1f || abs(targetAngle - angle) > 0.004f) invalidate()
        }

        private fun easeOutBack(t: Float): Float {
            val c1 = 1.70158f
            return 1f + (c1 + 1f) * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
        }
    }
}
