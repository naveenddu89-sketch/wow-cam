# wow cam

A native, portrait-only Android camera built for high-bitrate vertical video with
studio-style voice capture. Everything runs on the device: no accounts, no network
permission, no uploads, no analytics.

```
WOW CAM - ULTRA HD
1080P • 9:16 • 30 MBPS
```

## Install (public)

| What | Link |
|---|---|
| **Download page** | https://naveenddu89-sketch.github.io/wow-cam/ |
| **Direct APK** (v1.0.0 release asset) | https://github.com/naveenddu89-sketch/wow-cam/releases/latest/download/wow-cam.apk |
| **Live interactive demo** | https://naveenddu89-sketch.github.io/wow-cam/demo/ |

Downloads are debug-signed, installable directly. Android 7.0+. Recordings: `Movies/WowCam`.

## What it does

| | |
|---|---|
| Video | CameraX 1.3.1, FHD (1920x1080) at 30 FPS, optional 60 FPS when the camera reports support |
| Bitrate | 30 Mbps H.264 (CameraX `setTargetVideoEncodingBitRate`) |
| Frame rate | `setTargetFrameRate(Range(n, n))` plus a Camera2 `CONTROL_AE_TARGET_FPS_RANGE` lock |
| Aspect | 9:16 portrait; rotation hint is preserved by the muxer |
| Flip | Dedicated front/back switcher; lens choice is remembered, torch and zoom reset safely |
| Torch | Back-camera torch toggle via `CameraControl.enableTorch`, re-applied after rebinds |
| Zoom | Smooth 1x-5x pinch gesture plus a precise on-screen slider, driven by `CameraControl` |
| Looks | Normal, Vivid, Mono and Warm color grades rendered by a GPU `SurfaceProcessor` into **both** Preview and VideoCapture, so the look is baked into the MP4 |
| Timer | Off / 3 s / 10 s countdown with a large on-screen number; tap anywhere to cancel |
| Audio | Mono AAC-LC 192 kbps / 48 kHz from `VOICE_RECOGNITION`, falling back to `VOICE_COMMUNICATION` then `MIC` |
| DSP | `NoiseSuppressor` + `AcousticEchoCanceler` attached programmatically to the audio session |
| Output | `Movies/WowCam/WOWCAM_yyyyMMdd_HHmmss_SSS_<fps>FPS.mp4` |
| Share | Tap the "SAVED • TAP TO SHARE" line to send the original file to another app |

## Requirements

- Android 7.0 (API 24) or newer
- A device with a camera and microphone
- JDK 17
- Android SDK Platform 34 and Build-Tools 34.0.0
- Gradle 8.11.1 (the wrapper downloads it automatically)

## Build the APK

1. Install JDK 17 and point `JAVA_HOME` at it.
2. Install the Android SDK (Android Studio, or the command-line tools) and make sure
   `platforms;android-34` and `build-tools;34.0.0` are installed:

   ```bash
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```

3. Create `local.properties` in the project root with the SDK location:

   ```properties
   # Windows
   sdk.dir=C\:\\Android\\sdk

   # macOS / Linux
   sdk.dir=/Users/you/Library/Android/sdk
   ```

   (Android Studio creates this file for you.)

4. Build the debug APK:

   ```bash
   # Windows
   gradlew.bat assembleDebug

   # macOS / Linux
   ./gradlew assembleDebug
   ```

5. The APK is written to:

   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

   The project has been verified with Gradle 8.11.1, AGP 8.9.3 and JDK 17:
   `assembleDebug`, `assembleRelease` and `lintDebug` all complete successfully.
   Release output is written to `app/build/outputs/apk/release/` and is unsigned
   unless a keystore is configured (see below).

6. Install it on a connected device:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   Or copy the APK to the phone and open it (allow "install unknown apps" for your
   file manager). The application id is exactly `com.wowcam.app` in debug and release,
   so the two build types replace each other rather than installing side by side.

### Release build

The release build enables R8 and resource shrinking. It is unsigned unless you provide
a keystore.

```bash
keytool -genkeypair -v -keystore wowcam-release.jks -alias wowcam \
  -keyalg RSA -keysize 2048 -validity 10000
```

Copy `keystore.properties.example` to `keystore.properties` and fill it in:

```properties
storeFile=wowcam-release.jks
storePassword=your-store-password
keyAlias=wowcam
keyPassword=your-key-password
```

Then:

```bash
gradlew.bat assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Keep `wowcam-release.jks` and `keystore.properties` out of version control; both are
already listed in `.gitignore`.

## Testing

### UI responsiveness (headed Playwright)

`webtest/wowcam-mirror.html` is a 1:1 HTML mirror of `activity_main.xml` (same dp sizes,
insets and stacked constraint bands). The headed Chromium suite checks four phone sizes
and exercises the pro controls:

```powershell
cd webtest
npm install
npx playwright install chromium
npx playwright test --headed
```

It asserts that every control stays inside the viewport, has a touch target of at least
32 dp, never overlaps another control, that the top row and bottom stack occupy separate
bands, that the record ring fits its 30%-wide hit area, and that no label is clipped.
Screenshots land in `webtest/tests/screenshots/` and the HTML report in
`webtest/playwright-report/`.

### Real device (adb)

With a phone connected and USB debugging enabled:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\device-smoke-test.ps1
```

The script installs the debug APK, grants camera/mic, launches the activity, then taps
flip, torch, filter, zoom, timer and record through `uiautomator`, saving screenshots,
logcat and a MediaStore query under `artifacts/device-<timestamp>/`. It fails on a
`FATAL EXCEPTION` and warns if no `Movies/WowCam` entry appears.

## Using the app

1. Grant camera and microphone access. Microphone denial is tolerated: the app then
   records video only. If you denied permanently, tap the audio line to open system
   settings.
2. Tap the top-right chip to switch between 30 and 60 FPS. 60 FPS is only offered when
   the camera advertises a range that contains 60.
3. Tap the flip icon to switch between the back and front cameras. The app only flips
   while idle, remembers the last lens, resets zoom, and drops the torch first.
4. Tap the lightning icon to toggle the back-camera torch. It is disabled on the front
   camera and while the camera is busy, and it turns off when the app leaves the foreground.
5. Tap **FILTER** to cycle Normal → Vivid → Mono → Warm. The grade is applied live to the
   viewfinder and to the encoded video, and it can be changed while recording.
6. Pinch the preview or drag the **ZOOM** slider for a 1x-5x zoom. Both work while
   recording.
7. Tap **TIMER** to select Off / 3 s / 10 s, then tap the record button. A large countdown
   appears; tap anywhere (or the record button) to cancel it before it reaches zero.
8. Tap the red button once to record and again to stop. Recording also stops if the app
   goes to the background.
9. The clip is finalized in the background ("PROCESSING…"). When it is ready, tap
   "SAVED • TAP TO SHARE" to send the MP4 to Instagram, Drive, or any other app.

## Architecture

```
app/src/main/java/com/wowcam/app/
├── MainActivity.kt                       UI, permissions, CameraX binding, zoom,
│                                         filters, countdown, recording state machine
├── effect/
│   ├── ColorFilters.kt                   Color matrices for Normal/Vivid/Mono/Warm
│   ├── ColorMatrixEffect.kt              CameraEffect targeting Preview + VideoCapture
│   └── ColorMatrixSurfaceProcessor.kt    Dedicated EGL/GLES thread, OES input, per-output draw
├── audio/StudioAudioRecorder.kt          AudioRecord + AAC encoder + NS/AEC + .m4a muxer
└── media/
    ├── VideoDestination.kt               MediaStore pending item / legacy Movies path
    └── Mp4Muxer.kt                       lossless video + audio remux, A/V offset trim
```

### How the live looks reach the MP4

CameraX's `OverlayEffect` can only draw a bitmap on top of frames, which cannot produce a
true monochrome or color-matrix grade. wow cam instead implements a `SurfaceProcessor`
with a small OpenGL ES 2.0 pipeline:

1. CameraX writes OES frames into a `SurfaceTexture` owned by `ColorMatrixSurfaceProcessor`.
2. CameraX 1.3.1 delivers one `SurfaceOutput` per affected target (Preview and
   `VideoCapture`); the processor draws every frame once per output.
3. A fragment shader applies the selected 3x3 color matrix and offset. The video output is
   rendered with `EGL_RECORDABLE_ANDROID`, and every swap gets
   `eglPresentationTimeANDROID(timestamp)` so the encoder timestamps stay correct.
4. CameraX's per-frame `updateTransformMatrix` handles crop, rotation and mirroring for
   each target, so the framing is identical with and without a look.

Changing a filter only swaps one immutable matrix reference, so there is no camera rebind
and no recording interruption. All GL work runs on one dedicated `HandlerThread`; the
effect and its EGL context are released in `onDestroy`.

### Why audio is recorded separately

CameraX's `withAudioEnabled()` owns an internal `AudioRecord` and never exposes its
audio session id, so `NoiseSuppressor` and `AcousticEchoCanceler` cannot be attached to
it. wow cam therefore records the video track with CameraX and captures audio with its
own `AudioRecord` session. At stop time the two files are remuxed losslessly by
`Mp4Muxer`; neither stream is re-encoded, so quality is unchanged and the process only
costs a short burst of disk I/O.

Start-up order is: CameraX emits `VideoRecordEvent.Start` → the activity records
`videoStartNs` and starts the audio engine. The muxer shifts audio by
`(audioStartNs - videoStartNs) / 1000` microseconds and trims any audio that would land
before the first video frame. The video's own first presentation timestamp is used as
the time base, so the two tracks stay aligned without a shared clock.

### Saving

- Android 10+: a pending `MediaStore` item is created in `Movies/WowCam`. The muxer
  writes straight into the item's file descriptor when the provider allows it; if not,
  it writes to app cache and copies the bytes into the item before clearing
  `IS_PENDING`.
- Android 9 and below: the file is written to the public `Movies/WowCam` directory
  (`WRITE_EXTERNAL_STORAGE`, max SDK 28) and indexed with `MediaScannerConnection`.

### Memory

- PCM is streamed through one 4 KB buffer; nothing accumulates per second of audio.
- The muxer uses two reusable direct buffers sized from the track metadata (1–32 MB
  cap), not the clip length.
- Video is written directly to the final destination whenever possible.
- The color effect holds one OES texture plus one EGL window surface per target; it
  reuses its matrices, viewport and query arrays every frame and allocates nothing in
  the render loop.
- The effect's EGL context, surfaces, shader program and GL thread are destroyed in
  `onDestroy` (after the camera is unbound).
- The app does not request `largeHeap`.

## Limitations and notes

- **Instagram re-encodes every upload.** A 30 Mbps master minimizes generation loss,
  but the final quality on Instagram is decided by Instagram's transcoder, not by this
  app. Upload the original MP4 from `Movies/WowCam`, not a screen recording or a
  re-shared copy.
- **Live filters need GPU effect support.** They are implemented with CameraX
  `SurfaceProcessor` + OpenGL ES 2.0. On a device whose driver rejects the effect
  pipeline, the app automatically rebinds without the effect and keeps recording (the UI
  reports that live filters are unavailable). Normal mode needs no processing, but it
  still uses the effect pipeline when available.
- Rendering two outputs per frame costs more GPU/battery than a plain preview, especially
  at 60 FPS. On very cheap GPUs, prefer 30 FPS.
- Actual bitrate, level, and frame rate are enforced by the device's hardware encoder.
  Some devices cap H.264 at a lower bitrate or ignore an exact frame-rate request; the
  app falls back gracefully to 30 FPS and to the camera's supported quality.
- Maximum zoom is whatever the camera reports (the slider maps 1x–5x into the camera's
  supported range; some devices expose less than 5x and the slider simply saturates).
- 60 FPS is offered only when the camera reports support. On many devices it is
  unavailable at FHD and the app stays at 30 FPS.
- `NoiseSuppressor`/`AcousticEchoCanceler` availability is device-specific. The UI shows
  the effects that were actually enabled (`NS + AEC`, `NS`, `AEC`, or `DEVICE DSP`).
- If the microphone cannot be opened, the clip is saved without audio and the UI says
  `VOICE ISO • OFF (VIDEO ONLY)`.
- The app is intentionally portrait-only; the recorded canvas is a fixed 9:16 frame.
- Very short clips (under 0.8 s) are discarded instead of producing a broken file.

## Privacy

- No `INTERNET` permission.
- No SDKs, analytics, ads, or cloud processing.
- Recordings stay in `Movies/WowCam` until you move or delete them.
