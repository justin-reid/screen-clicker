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
- `AccessibilityCapture` — always available, no prompts, ≈1 fps (system-throttled; we
  wait out the window ourselves inside the service's capture mutex).
- `MediaProjectionCapture` — the fast path (frame-rate capture), one consent prompt per
  capture session; `CaptureProjectionService` must be foregrounded with type
  `mediaProjection` BEFORE `getMediaProjection()` on Android 14+. A static screen emits
  no frame callbacks at all, so its capture() returns the newest cached frame — the
  correct answer when nothing changed.
- **The capture consent dialog must ask for the whole display.** Both entry points (the
  app's button and `CaptureConsentActivity`) go through `consentIntentForWholeScreen`,
  which passes `MediaProjectionConfig.createConfigForDefaultDisplay()` on API 34+. Android
  14 added "share a single app" and made it the dialog's *default* selection; the
  no-argument `createScreenCaptureIntent()` is the "user choice" variant that produces that
  default. A single-app projection is one app's window letterboxed inside a display-sized
  frame, so every region and every tap — all of them display coordinates — lands somewhere
  else, and the run finds nothing with no error to explain it. `CaptureProjectionService`
  also warns once when `onCapturedContentResize` reports content smaller than the mirror:
  that callback is the only place a single-app projection is visible at all, since the
  frames themselves are display-sized either way.
- `Capturers.pick(settings)` resolves the backend at script start: auto (fast when
  granted) / accessibility / fast. Backend changes take effect on the next run.

Adding a second backend later must not require touching the runner. That is the whole
point of the interface.

## Layout

```
app/
  accessibility/  ClickerAccessibilityService — capture + taps + foreground-app detection
  capture/        ScreenCapturer seam, AccessibilityCapture, MediaProjectionCapture,
                  CaptureProjectionService (fast backend), Capturers (backend factory)
  vision/         TemplateMatcher — pure Kotlin on IntArray pixels, no android.* imports
  engine/         ScriptRunner — scan loop, rule evaluation, delay+jitter, cadence
  model/          Script/Rule/Settings — kotlinx.serialization JSON + PNG templates
  overlay/        ConfigOverlayService (editor), RunOverlayView (match boxes + tap markers)
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
- **The app draws edge-to-edge, and MainActivity consumes the system bars once for every
  screen.** targetSdk 36 means Android enforces edge-to-edge, so y=0 is under the status
  bar — until round 23 the screens' header buttons (Save/Done/Settings) sat behind it and
  were reported as "too close to the top of the screen, hard to click". The fix is one
  `windowInsetsPadding(WindowInsets.systemBars)` Box around all screens in MainActivity;
  screens must not add their own bar padding (that doubles it), and new screens get the
  padding for free. Deliberately `systemBars` rather than `safeDrawing`: IME insets are
  left alone so keyboard behaviour is unchanged.
- **A running script must always have a stop control, and the floating panel is it.**
  Three rules hold that up, and all three are needed: every script start calls
  `ControlPanelService.ensureRunning` (from `startScript`, so a new way to start a script
  inherits it); while `runningScriptId != null` the panel's close control is not drawn
  *and* `onClose` / `setFloatingPanel(false)` refuse with a toast, because dismissing the
  panel mid-run leaves a script that can only be stopped by opening the app; and what
  comes back is the collapsed 40dp bubble — the panel's always-there state, carrying the
  running indicator — never an expanded panel landing on top of the app being automated.
  The header's two controls are minimize (a bar) and close (a cross), both drawn on a
  canvas rather than typed as text: `GlyphButton` exists so the pair shares a stroke, a
  length and an optical centre, which no font glyph does.
- **The panel is compact before it is complete.** Its three switches (highlight matches,
  tap markers, the runner log) sit behind one `Options` disclosure row in
  `ControlPanelView`: as permanent rows they cost the panel a large share of its height,
  and they are controls for the overlays rather than anything a running script needs in
  front of it. Collapsed by default, and `ControlPanelService.optionsExpanded` is
  deliberately *not* persisted — like `expandedScriptId` it is where the user left the
  panel open, not a setting, so a restarted panel always comes back compact. The folded
  rows are not added to the view at all rather than hidden, and the flag is part of the
  structural key so the fold actually rebuilds the list.
- **Overlay windows use FLAG_LAYOUT_NO_LIMITS + FLAG_LAYOUT_IN_SCREEN** so view
  coordinates equal screenshot pixels (status bar included). Never switch to default
  window geometry — every stored rect would be off by the status bar height.
- **Template capture hides the config overlay during the shot.** takeScreenshot
  captures everything on screen including our own rectangles; the overlay sets itself
  INVISIBLE for the capture and restores right after (with a settle delay so the
  compositor drops the hidden frame).
- **The MediaProjection backend has no rotation handling.** The VirtualDisplay is
  created at the current maximum-window-metrics size; after rotation the frames
  letterbox and matching breaks. Re-grant capture after rotating, or recreate the
  virtual display on config changes as a proper fix.
- **Coordinates are physical pixels.** Screenshots arrive in native display pixels and
  `dispatchGesture` expects the same space; never mix in dp. Config data stores px rects
  captured at config time. If the display size changes (rotation, resolution setting),
  scripts captured for the old geometry are stale — re-configure rather than scale.
- **A defined click area presses once, or once per find.** `Rule.regionClickCount`
  (`SINGLE`, the default) collapses a frame's occurrences to a single tap on the area, so
  the rule behaves as one switch: three matches in one scan are one press, and
  repeat-while-visible presses once per interval rather than once per match.
  `PER_OCCURRENCE` taps the area for every match (N finds ⇒ N presses) — for an area that
  is a button filling one of the found slots per press. Ignored for `ON_IMAGE`, where each
  find taps itself. Detection and highlights are unaffected: every occurrence is still
  found, drawn, and counted, whatever the tap count.
- **Tap points are randomized inside the click bounds with an inset** (≈10% of the
  smaller dimension) so a tap can never land exactly on a rect edge, and so consecutive
  taps almost never repeat. The randomness is per-tap, not per-detection.
- **Delay + jitter is one reaction per scan, not one per tap.** Effective delay =
  `delayMs + U(-jitterMs, +jitterMs)`, clamped ≥ 0, and it is paid *once* whenever a scan
  decided to click something — the slowest firing rule's value — after which every queued tap
  goes out back to back. A frame full of matches is one thing seen, so charging the delay per
  occurrence made a run's reaction time scale with how much it found: a twenty-cell grid spent
  twenty delays (five or six seconds) before the loop could so much as look at the screen
  again, which is what the "other clickers detect in milliseconds" comparison was really
  about. The calibration feature writes `delayMs` (average human reaction), jitter stays
  separate and small. Per-tap spacing is deliberately not a thing — if it is ever wanted it
  belongs beside `intervalMs`, not on the reaction.
- **Both overlay switches are enforced per layer, not just on the window.** The window stays
  up while *either* switch has something to draw, so "this layer is off" cannot be expressed as
  window visibility. It has to be `RunOverlayLayers.of(detections, taps, muted)` — which is
  handed to the view, read per layer, and returns false for a layer that is switched off *or*
  muted. Round 18 added the tap switch and left the rectangle loop drawing unconditionally:
  with taps on and "Highlight matches" off the window is legitimately visible, so the boxes
  stayed on screen and the switch looked broken (round 20). A switch that is off must be
  turned off *in the draw*, not in the window.
- **A rectangle authored in the editor is in *that session's* display space, so its alignment
  must be saved with it.** The editor's rectangles reach the engine as model coordinates plus
  a stored alignment; saving a rectangle drawn in one display state with an alignment measured
  in another stores one display's shape in another's coordinates. That is a click area which
  sits below where it was drawn, with every tap following it there — and on a foldable the two
  states genuinely differ (the WM's idea of the screen and the screenshot size already disagree
  there; see `adoptMeasuredScreenSize`). Alignment is therefore persisted when a template was
  cropped *or* any selector was moved this session; a rectangle an earlier session drew must
  not silently adopt a new offset either. The runner prints the stored click area, the tapAlign
  it is stored with, and the two combined, once per run — those three numbers are what to read
  when the amber box is not where it was drawn.
- **The run overlay has two independent switches, and each is a visibility flip, not a
  window.** Its window is opened for the whole run whatever the settings say; `showDetections`
  (match boxes) and `showTaps` (tap markers) only drive what is drawn and the layer's
  `visibility`. That is what makes either of them toggleable mid-run — the floating panel and
  the settings screen both do. Adding or removing the window mid-run would leave the runner's
  captured `overlayGen` stale and paint nothing. `detectionsWanted`/`tapsWanted` (the user's
  choices) and `detectionsMuted` (our own windows standing the layer down for a template
  capture, the installer, or the consent dialog) are separate on purpose: un-muting must never
  switch anything back on for someone who turned it off. The layer stays visible while *either*
  switch has something to draw, so turning the boxes off does not take the markers with them.
- **The tap markers exist to make the random tap point checkable.** One coordinate in the
  status line cannot show a distribution, so every injected tap leaves a marker for a couple of
  seconds with the area it was drawn from outlined beneath it: a scatter is the randomness
  working, a marker that never moves is the bug. `overlay/TapTrace` holds the bounded,
  self-expiring record (pure Kotlin, unit-tested; the view only paints it). Refused gestures
  are marked too, drawn hollow and grey — a run that injects nothing must not look like a run
  that is clicking, which is exactly how the missing `canPerformGestures` hid for four rounds.
  The feature is off by default and independent of the match boxes; watching the taps with the
  boxes off is the normal way to use it.
- **The periodic status line accounts for the tap burst separately** (`scan … capture … gray …
  match … taps Nms`). `scan` used to mean capture + gray + match and was reported as the whole
  cost of an iteration, which hid a tap burst several times longer than the scan itself.
- **The scan's cost is search-region area plus one full-resolution refine per near-threshold
  peak — and on device an implementation gap far larger than either.** Measured locally at the
  geometry the phone reports (200x180 template, 1075x1717 region, ~32 buttons on screen;
  `vision/SearchCostBreakdownTest`, numbers printed on every run): ~100ms for the coarse pass
  over that region with an *empty* screen, ~+2ms for every peak admitted above the coarse
  floor, ~165ms for the whole StarFarm field, and ~6ms for the same screen searched over a
  320x300 region. So region area is a real term the user can change — but the device reports
  `match 5161ms` for that same work, ~30x slower per operation, which neither region nor
  template choice explains. Two candidates, cheapest first: (1) every APK published so far is
  a **debuggable** build, and debuggable apps run without ART's optimizing compiler, which is
  exactly what hurts a tight integer loop — hence the second CI artifact,
  `screen-clicker-<sha>-release`, shipped to measure that rather than assume it; (2) the worst
  case is the frame where nothing matches confidently, because the high-pass rescue re-refines
  the entire peak set (measured: 229ms for the decoys-only screen against 165ms for the same
  screen with the target present). Do **not** buy speed by raising the coarse floor or capping
  the peak set without re-checking the round-7 fade case and the round-13 multi-occurrence
  case. And do not explain the gap with "the other app searches a smaller region" — that was
  offered once, on no evidence, and Justin's same-purpose/same-screen usage rules it out.

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
| M1 | Accessibility service shell (screenshot, tap test) | code done; on-device check pending |
| M2 | Template matcher + JVM unit tests | done (12 tests green in CI) |
| M3 | Data model + persistence + script editor | done |
| M4 | Config overlay (rects, template capture, live preview) | code done; on-device check pending |
| M5 | Runner (multi-rule, delay+jitter, random tap, QS tile) | code done; on-device check pending |
| M6 | Detection highlights overlay | code done; on-device check pending |
| M7 | Reaction-time calibration | code done; on-device check pending |
| M8 | MediaProjection fast capture backend | code done; on-device check pending |
| M9 | Script→app binding (in runner), polish, docs | done |
| M10 | "Show taps" markers (random tap point visible) | code done; on-device check pending |

All milestones are built and unit-tested. The remaining verification is physical:
sideload, grant permissions, and run a script on a real screen — the build machine
cannot do that part.
