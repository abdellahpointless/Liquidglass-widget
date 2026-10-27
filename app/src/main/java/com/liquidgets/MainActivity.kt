@file:OptIn(ExperimentalMaterial3Api::class)

package com.liquidgets

import android.Manifest
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LiquidgetsApp() }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the wallpaper picker or a permission screen can change what every widget should show.
        Widgets.refreshAll(this)
        Weather.updateLocation(this)
    }
}

private val IOS_TINTS = listOf(0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F, 0xFFFF453A, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158, 0xFF64D2FF, 0xFF8E8E93)

@Composable
private fun LiquidgetsApp() {
    val c = LocalContext.current
    var s by remember { mutableStateOf(GlassSettings.load(c)) }
    var photoVersion by remember { mutableIntStateOf(0) }
    val metrics = c.resources.displayMetrics
    val photo = remember(photoVersion) { Wallpapers.load(c, metrics.widthPixels, metrics.heightPixels) }
    fun commit(n: GlassSettings) {
        s = n
        n.save(c)
        Widgets.refreshAll(c)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            Wallpapers.save(c, uri)
            photoVersion++
        }
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Weather.updateLocation(c)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF0A84FF), background = Color.Black, surface = Color(0xFF1C1C1E))) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).systemBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Liquidgets", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                GlassPreview(s, photo)

                Section("Style") {
                    Choice(listOf("Clear", "Tinted"), s.style.ordinal) { commit(s.copy(style = GlassStyle.entries[it])) }
                    if (s.style == GlassStyle.TINTED) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            IOS_TINTS.forEach { tint ->
                                val selected = s.tint == tint.toInt()
                                Spacer(
                                    Modifier.size(26.dp).clip(CircleShape).background(Color(tint))
                                        .border(if (selected) 3.dp else 0.dp, Color.White, CircleShape)
                                        .clickable { commit(s.copy(tint = tint.toInt())) },
                                )
                            }
                        }
                    }
                }
                Section("Appearance") {
                    Choice(listOf("Auto", "Light", "Dark"), s.appearance.ordinal) { commit(s.copy(appearance = Appearance.entries[it])) }
                }
                Section("Glass thickness") {
                    Slider(s.thickness, { s = s.copy(thickness = it) }, onValueChangeFinished = { commit(s) })
                }
                Section("Corner radius") {
                    Slider(s.cornerDp, { s = s.copy(cornerDp = it) }, valueRange = 8f..40f, onValueChangeFinished = { commit(s) })
                }
                Section("Real glass") {
                    Hint(
                        "Widgets can't bend light by themselves, so Liquidgets can draw real refracting glass from a live wallpaper instead. " +
                            "Pick a photo, set the Liquidgets wallpaper, then tap each widget once so the glass learns where it sits. Tap again after moving a widget.",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use real glass", color = Color.White, modifier = Modifier.weight(1f))
                        Switch(s.realGlass, { commit(s.copy(realGlass = it)) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose photo") }
                        FilledTonalButton({ setWallpaper(c) }) { Text("Set wallpaper") }
                    }
                    if (Build.VERSION.SDK_INT < 33) Hint("Refraction needs Android 13 or newer; this phone gets frosted glass instead.")
                }
                Section("Widgets") {
                    listOf("Clock" to ClockWidget::class.java, "Calendar" to CalendarWidget::class.java, "Weather" to WeatherWidget::class.java,
                        "Battery" to BatteryWidget::class.java, "Music" to MusicWidget::class.java).forEach { (name, cls) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = Color.White, modifier = Modifier.weight(1f))
                            Button({ AppWidgetManager.getInstance(c).requestPinAppWidget(ComponentName(c, cls), null, null) }) { Text("Add") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("Weather location") }
                        FilledTonalButton({ c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Music access") }
                    }
                    Hint("Music access only lets Liquidgets see what's playing; it never reads your notifications.")
                }
            }
        }
    }
}

private fun setWallpaper(c: Context) {
    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(c, GlassWallpaper::class.java))
    runCatching { c.startActivity(intent) }.onFailure { c.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF1C1C1E)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        content()
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = Color(0xFF98989F), fontSize = 13.sp)

@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(selected == i, { onSelect(i) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
        }
    }
}

/** A mini home screen: the chosen photo with widgets drawn exactly as the live wallpaper or the widgets would draw them. */
@Composable
private fun GlassPreview(s: GlassSettings, photo: Bitmap) {
    val c = LocalContext.current
    val d = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(0.85f).clip(RoundedCornerShape(28.dp))) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val scene = remember(photo, w, h) { GlassPainter.centerCrop(photo, w, h) }
        val blurred = remember(scene) { GlassPainter.blur(scene) }
        val real = remember { if (Build.VERSION.SDK_INT >= 33) RealGlass() else null }
        val light = remember(s) { s.contentLight(c) }
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val cv = canvas.nativeCanvas
                cv.drawBitmap(scene, 0f, 0f, null)
                val pad = 18 * d
                val cell = (w - 3 * pad) / 2
                val panels = listOf(
                    RectF(pad, pad, pad + cell, pad + cell) to "9:41",
                    RectF(2 * pad + cell, pad, w - pad, pad + cell) to "27",
                    RectF(pad, 2 * pad + cell, w - pad, (2 * pad + 2 * cell).coerceAtMost(h - pad)) to "Liquidgets",
                )
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (light) android.graphics.Color.WHITE else 0xFF1C1C1E.toInt()
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                for ((r, label) in panels) {
                    if (s.realGlass && real != null) {
                        val m = GlassPainter.shadowMargin(d)
                        cv.drawBitmap(GlassPainter.shadowBitmap(r.width(), r.height(), s, d), r.left - m, r.top - m, null)
                        real.draw(cv, r, s, light, d, GlassPainter.LIGHT_ANGLE, 1f, scene, blurred)
                    } else {
                        cv.drawBitmap(GlassPainter.widgetBitmap(c, r.width() / d, r.height() / d, s, light), null, r, null)
                    }
                    text.textSize = (if (label.length > 5) 28 else 44) * d
                    cv.drawText(label, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
                }
            }
        }
    }
}
