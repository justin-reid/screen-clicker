# Working on screen-clicker

Context for anyone — human or agent — changing this code. The [README](README.md) covers
what the app is and how to use it; this covers why it is built the way it is, and what
breaks if you change the wrong thing.

## The constraint everything follows from

An ordinary Android app can neither see another app's pixels nor tap the screen. Both
capabilities are privileged, and a sideloaded APK holds neither. One component unlocks
both at once: an **AccessibilityService**. It can capture the screen with
`takeScreenshot()` (Android 11+) and inject touches with `dispatchGesture()`. Every part
of the architecture is downstream of that choice.

Note the deliberate contrast with floating-dpad: accessibility was rejected there because
it cannot inject *key events*. It injects *motion events* just fine, which is exactly
what a clicker needs. Same conclusion does not carry over.

### The screenshot rate limit is the central constraint

`AccessibilityService.takeScreenshot()` is throttled by the system (roughly one frame per
second; faster calls fail with `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`). That sets the
reaction-time floor of the accessibility backend. The design answer:

- `capture/ScreenCapturer` is the seam. The runner never knows which backend it has.
- `AccessibilityCapture` — always available, no prompts, ≈1 fps.
- `MediaProjectionCapture` — the fast path (10+ fps possible), but it needs a consent
  dialog per capture session and a foreground service with type `mediaProjection` running
  *before* `getMediaProjection()` on Android 14+.

Adding a second backend later must not require touching the runner. That is the whole
point of the interface.

## Layout

```
app/
  accessibility/  ClickerAccessibilityService — capture + taps + foreground-app detection
  capture/        ScreenCapturer interface, AccessibilityCapture, MediaProjectionCapture
  vision/         TemplateMatcher — pure Kotlin on IntArray pixels, no android.* imports
  engine/         ScriptRunner — scan loop, rule evaluation, delay+jitter, cadence
  model/          Script/Rule/Settings — kotlinx.serialization JSON + PNG templates
  overlay/        ConfigOverlayService (editor), DetectionOverlay (highlights)
  settings/       Compose UI — scripts, rule editor, calibration, global settings
  tile/           Quick Settings tile
```

## Load-bearing details

- **`vision/` must stay pure Kotlin.** It operates on `IntArray` ARGB pixels with
  width/height, never `android.graphics.Bitmap`. This is what lets its unit tests run on
  the JVM in CI without an emulator. Bitmap→IntArray conversion happens at the capture
  boundary. Do not "conveniently" add a Bitmap parameter.
- **Overlay windows are plain Views, not Compose** (same reasoning as floating-dpad:
  Compose inside a `WindowManager` window needs lifecycle-owner shims and adds
  recomposition cost). Compose is for the in-app screens only.
- **Coordinates are physical pixels.** Screenshots arrive in native display pixels and
  `dispatchGesture` expects the same space; never mix in dp. Config data stores px rects
  captured at config time. If the display size changes (rotation, resolution setting),
  scripts captured for the old geometry are stale — re-configure rather than scale.
- **Tap points are randomized inside the click bounds with an inset** (≈10% of the
  smaller dimension) so a tap can never land exactly on a rect edge, and so consecutive
  taps almost never repeat. The randomness is per-tap, not per-detection.
- **Delay + jitter:** effective delay = `delayMs + U(-jitterMs, +jitterMs)`, clamped ≥ 0.
  The calibration feature writes `delayMs` (average human reaction), jitter stays
  separate and small.

## Data

- Scripts/rules are JSON (kotlinx.serialization) under app files dir; template crops are
  PNGs beside them, referenced by file name. No Room. Export/import later means copying
  one directory.
- Global settings (default delay, scan cadence, highlight toggle) in SharedPreferences —
  synchronous reads from the runner matter here, same argument as floating-dpad.

## Toolchain

- **CI is the compiler.** There is a lightweight local toolchain on Justin-PC
  (`C:\Users\justi\tools`: JDK 21, Android cmdline-tools, Gradle 8.14.3; source
  `tools\android-env.bat` first) for unit tests and sanity builds, but the distributable
  APK is always the GitHub Actions artifact. Push and read the run log; don't claim a
  build works without one.
- The Gradle wrapper jar is not committed — CI pins Gradle via `gradle/actions/setup-gradle`.
- Builds are signed with a fixed debug keystore restored from the `DEBUG_KEYSTORE_B64`
  repository secret into `debug.keystore` at the repo root (gitignored), and
  `app/build.gradle.kts` points the debug `signingConfig` at that file **explicitly**.
  This matters: without it, Android refuses updates over an installed copy and reports
  only "App not installed". Print and compare the certificate fingerprint each build.
  It should stay:

  ```
  084f281aea337ea94e75e0cf703f78ed3d5f6abebe8b404409a1feb7b2a496d0
  ```

- AGP 8.13.2 defaults to **build-tools 35.0.0** even with compileSdk 36; it tries to
  auto-install it on first use and dies on the SDK license prompt. On Justin-PC the SDK
  was populated by downloading packages directly from dl.google.com (see
  `C:\Users\justi\tools\install-sdk-direct.ps1`), which sidesteps license handling
  entirely: build-tools 35.0.0, 36.0.0, 36.1.0, platform-36, platform-tools.

## Status

| # | Milestone | State |
|---|---|---|
| M0 | Scaffold + CI producing a debug APK artifact | done, verified green 2026-09-27 |
| M1 | Accessibility service shell (screenshot, tap test) | pending |
| M2 | Template matcher + JVM unit tests | pending |
| M3 | Data model + persistence + script editor | pending |
| M4 | Config overlay (rects, template capture, live preview) | pending |
| M5 | Runner (multi-rule, delay+jitter, random tap, QS tile) | pending |
| M6 | Detection highlights overlay | pending |
| M7 | Reaction-time calibration | pending |
| M8 | MediaProjection fast capture backend | pending |
| M9 | Script→app binding, polish, docs | pending |
