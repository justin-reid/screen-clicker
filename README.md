# screen-clicker

An Android app that watches the screen for a picture you choose and taps for you — an
auto clicker / screen macro that works on any app, without root.

Configure it by drawing on your screen: mark the region to watch, mark the thing to look
for, mark where to tap. When the image shows up, Screen Clicker taps it (or taps wherever
you pointed it), after an optional delay — so it behaves like a very fast, very
persistent human.

## What it does

- Detect an image you picked, anywhere inside a region you drew, with an adjustable
  similarity threshold (works across brightness/dark-mode shifts — normalized
  correlation, not raw pixel difference)
- Tap on the detected image itself, or on a different region you drew — always at a
  random point inside the bounds, never the same pixel twice
- Optional delay between detection and tap, with random fuzz so the timing is never
  machine-perfect
- One configuration ("script") can hold many independent rules: image A → tap X,
  image B → tap Y; rules can be per-app (only run while a chosen app is foreground)
- Click cadence per rule: once per appearance (default — waits for the image to
  disappear before clicking again) or repeat-while-visible on an interval
- Configure by drawing: the on-screen editor draws rectangles over the app you're
  automating, captures the template from the live screen, and shows a live match
  check before you save
- "Show detections" highlights what it just recognized while the script runs
- A reaction-time test in settings measures how fast you'd have tapped yourself, and
  offers that as the default delay
- Two capture engines: accessibility screenshots (no extra prompts, ~1 scan/second)
  and a fast MediaProjection engine (many scans per second) — pick Auto, or force one

## What it needs

An Android 11+ device. The app uses Android's accessibility service to both watch the
screen and perform the taps — this is the standard no-root approach, but it does mean
granting accessibility access to the app, and Android will show the usual scary warning
about it. Nothing leaves the device: detection runs entirely on the phone.

For fast reaction (~100–300 ms), the app asks for screen-capture permission
(MediaProjection) — one consent prompt per capture session.

## Getting the app

Every push builds an installable APK. Open [**Actions**][actions], click the most recent
run, download the artifact under **Artifacts** — it's named `screen-clicker-` followed by
the short commit hash of that build. It downloads as a `.zip`; extract the
`screen-clicker-debug.apk` inside before installing. You'll need to be signed in to
GitHub to download artifacts.

## Building it yourself

You'll need JDK 21 and the Android SDK (`compileSdk` 36):

```bash
gradle assembleDebug
```

Android Studio does both for you when it opens the project.

---

Working on the code? [AGENTS.md](AGENTS.md) covers how it works internally, and which
parts are load-bearing and easy to break by accident.

[actions]: https://github.com/justin-reid/screen-clicker/actions
