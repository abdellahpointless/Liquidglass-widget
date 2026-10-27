# Liquidgets

Android home screen widgets in the style of iOS 26 Liquid Glass.

## Widgets

Clock (analog when small, digital when wide), Calendar, Weather (Open-Meteo, no API key), Battery, and Music controls.

## Two kinds of glass

- **Frosted (default):** each widget draws a baked glass panel: an iOS-style continuous-corner shape, tint, sheen, edge glow and a specular rim. It works on any launcher and any Android 8+ phone.
- **Real glass:** Android widgets can't run shaders, so the Liquidgets live wallpaper draws refracting glass (lens bevel, RGB dispersion, vibrancy, tilt-driven highlight, a "materialize" animation) under each widget, and the widgets go see-through. Turn it on in the app, choose a photo, set the wallpaper, then tap each widget once so the wallpaper learns where it sits. Tap a widget again after you move it. Refraction needs Android 13+.

## Install a test build

Every push builds a debug APK in GitHub Actions (**Actions → Build APK → liquidgets-debug**). Test builds share one signing key, so a new APK installs over the old one.

## Credits

The lens and highlight shader math is ported from [AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView) (MIT, QmDeve) and [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) (MIT, Kyant). The continuous corner shape follows figma-squircle's smoothing.
