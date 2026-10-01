# LiteScope — Android playback-audio inspector

LiteScope shows you **what your phone is playing**, not what its microphone hears. It taps the
system playback stream with `AudioPlaybackCapture` and renders four classic analysis instruments as
floating windows on top of any app:

| Tool | What it shows |
|---|---|
| **Spectrum** | Real-time FFT with peak hold, pink tilt, a continuous linear↔log axis, pinch zoom and draggable measurement cursors |
| **Waterfall** | Scrolling spectrogram whose history length you choose, with age labels in its own gutter |
| **Wave** | Oscilloscope with trigger, time-base zoom, gain, tap-to-freeze and three stereo layouts (overlay / split / summed) |
| **Vector** | Lissajous / goniometer stereo image with phosphor persistence |
| **Meters** | Peak / RMS / hold, dB scale, a latching red clip rectangle per channel, per-channel value pills and a dedicated loudness block. Split L/R, combined L+R or both |

Every tool can be toggled independently from the **notification** (or from the app), each is a
**resizable, draggable, magnetically snapping** overlay window, and everything can be previewed
inside the app without the overlay permission.

---

## Feature checklist

| # | Requirement | Where it lives |
|---|---|---|
| 1 | Inspects audio **coming out of the phone**, not the microphone | `AudioCaptureEngine` uses `AudioPlaybackCaptureConfiguration` + `MediaProjection` (microphone is never opened) |
| 2 | Real-time spectrum waterfall, wave scope and vector scope via "display over other apps" | `ScopeHub` + `SpectrumView`, `WaterfallView`, `WaveformView`, `VectorScopeView` rendered into `TYPE_APPLICATION_OVERLAY` windows |
| 3 | Each tool enabled individually in the notification centre | `NotificationFactory` builds a `RemoteViews` panel with one chip per tool; `ScopeService` toggles overlays |
| 4 | Windows are resizable and can be snapped together | `ToolWindow` (8 resize handles + title-bar drag), `WindowSnapper` (edge/centre magnet, dock matching) |
| 5 | Configurable sample rate for the spectrum scope | `Prefs.captureRate` (requested capture rate) + `Prefs.analysisDivisor` (integer decimation for the FFT, with a windowed-sinc anti-alias filter) |
| 6 | Spectrum zoom to a frequency range by pinch gesture | `FreqScopeView` gesture layer + `FreqAxis`: the window is stored in a blended position space, so pinch = zoom, drag = pan and double tap = reset work identically on linear, log or any blend in between |
| 7 | Configurable waterfall history length | `Prefs.waterfallHistorySec` (2–300 s) with a pixel budget that adapts the row rate instead of dropping history |
| 8 | Wave scope zoom in / out | Pinch changes the time base (0.02–200 ms/div), drag pans, vertical drag changes gain |
| 9 | Wave scope combined / individual stereo views | `Prefs.waveStereoMode` — **overlay** both channels in one lane, **split** them into lanes with independent gain, or **sum** them into a single L+R trace |
| 11 | Measurement cursors | Tap the spectrum to add one, drag its handle, tap it to remove, long press to lock. Locked cursors are pinned (they cannot be dragged) and always visible; unlocked ones fade after 5 s. Each label shows the frequency *and* the level at that point, and the delta readout follows the cursor you last touched, so it keeps working with any number of cursors |
| 10 | Extra functions | Trigger (auto/normal/free, level, edge, source), AC/DC coupling, Vpp/RMS/frequency readout, peak-hold trace, waterfall freeze/clear/colormaps, correlation & balance meters, WAV recording, built-in test-signal generator, Quick Settings tile, window lock (click-through), opacity, accent/background themes, editable settings fields, compact window chrome, link spectrum↔waterfall zoom+pan+scale+cursors |

---

## Building

Requirements: JDK 17+ (21 recommended), Android SDK with platform 35 / build-tools 35.

```bash
# point Gradle at your SDK
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 14 DSP unit tests (FFT vs. naive DFT, decimator, axis math)
./gradlew :app:lintDebug              # 0 errors
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK needs no signing setup; for a release build add your own keystore.

### What is verified

```
:app:assembleDebug        BUILD SUCCESSFUL   -> app/build/outputs/apk/debug/app-debug.apk (~7 MB)
:app:testDebugUnitTest    50 tests, 0 failures
:app:lintDebug            0 errors
```

The unit tests are not smoke tests — they check behaviour:

* `FftTest` — the radix-2 FFT is compared against a naive DFT sample by sample, and a full-scale
  sine must land entirely in one bin at exactly `N/2`.
* `DecimatorTest` — a 20 kHz tone must be suppressed by >26 dB when decimating to 12 kHz analysis
  rate (alias rejection), while a 100 Hz tone keeps unity gain.
* `AudioBusTest` — ring buffer cursors, wrap-around, expired-history clamping, mono duplication,
  the min/max envelope (including the fewer-frames-than-columns case) and the blocking read.
* `WindowSnapperTest` — edge/centre snapping, the threshold, docking with height/width matching and
  the disabled cases.
* `FreqAxisTest` — log/linear round trips, pinch-zoom focus retention, pan clamping to the band.
* `ScopeViewRenderTest` (Robolectric, native graphics) — every scope view is laid out and rasterised
  with real Skia; the test asserts that the grid, traces, phosphor, waterfall bitmap and readouts
  actually put ink on the canvas.
* `ServiceArtifactTest` (Robolectric) — the notification panel is inflated for real (so a stale view
  id or an unsupported `RemoteViews` call fails the test), the per-tool toggle states, content
  descriptions and the timer format are checked, the settings tree is built, `MainActivity` is
  actually launched (a context-in-initialiser bug is caught this way), and
  `ToolWindow`/`OverlayManager` create, lock, reset and remove the floating windows.

### Device smoke test

The build was also exercised on a Sony XQ-DQ72 (Android 14) over ADB: MediaProjection consent →
foreground service with `foregroundServiceType=mediaProjection` → the notification panel with the
per-tool icons → floating windows over the launcher, driven by the built-in 1 kHz test signal. The
readouts were checked for correctness (peak −6.0 dBFS, RMS −9.0 dBFS and 3.0 dB crest for a 0.5
full-scale sine; 1002 Hz measured for a 1000 Hz tone; correlation +1.00 for a mono signal), together
with cursor add/drag/lock/auto-hide, the waterfall's gutter labels and the meter panel layout.

---

## First run

1. **Microphone permission** — required by the platform even though the microphone is never used
   (`AudioPlaybackCapture` is gated behind `RECORD_AUDIO`).
2. **Display over other apps** — needed for the floating scope windows.
3. **Notifications** — needed so the per-tool toggles stay reachable from the shade.
4. Tap **Start capture** and accept the system screen-capture dialog. This dialog is the
   `MediaProjection` consent that unlocks playback capture; nothing is recorded or streamed.
5. Enable the tools you want. They appear as floating windows immediately, and as chips in the
   notification.

Nothing to play? Open **Capture → Self test → Test signal generator** and select the 1 kHz tone,
dual tone, sweep, pink or white noise. The generator feeds a synthetic signal straight into the
analysis chain so you can verify and calibrate the scopes (the 1 kHz tone is exactly full scale at
level 1.00).

---

## Gestures

| Tool | Gesture | Action |
|---|---|---|
| Spectrum | pinch / drag / double tap | zoom frequency range / pan / reset zoom (peak trace and cursors are kept) |
| Spectrum | tap | add a measurement cursor; tap a cursor to remove it |
| Spectrum | drag a cursor | move it (its handle sits at the top of the line) |
| Spectrum | long press a cursor / long press elsewhere | lock-unlock it (locked cursors never fade) / clear the peak-hold trace |
| Waterfall | pinch / drag / double tap | zoom / pan (also drives the spectrum when *linked*) |
| Waterfall | tap / long press | freeze / clear history |
| Wave | pinch / drag horizontally / drag vertically / double tap | time base / pan / gain / reset |
| Wave | tap | freeze the trace (tap again to resume) |
| Wave | long press | cycle overlay → split → summed stereo |
| Vector | pinch / double tap | gain / reset + clear phosphor |
| Any window | drag title bar | move (snaps to screen edges, centre and other windows) |
| Any window | drag an edge or corner | resize |
| Any window | title-bar icons | lock (click-through) / link spectrum↔waterfall / reset view / settings / close — collapsed into a single pop-out button when the window is narrow |

Locked windows ignore touch completely; unlock them in **Overlay windows → Touch lock** or with
**Reset layout** on the notification.

---

## How it works

```
MediaProjection consent (Activity)
        │
        ▼
ScopeService (foreground, type=mediaProjection)
        │  AudioPlaybackCaptureConfiguration(USAGE_MEDIA|GAME|UNKNOWN)
        ▼
AudioCaptureEngine ──► AudioBus (lock-free-ish stereo ring, 6 s)
        │                     │
        │                     ├──────────────► WaveformView  (min/max envelope, 1 pass/frame)
        │                     ├──────────────► VectorScopeView (raw L/R trace)
        │                     └──► SpectrumAnalyzer thread
        │                              │  down-mix → FIR decimation → windowed FFT → dBFS
        │                              ├──► SpectrumView   (AtomicReference<SpectrumFrame>)
        │                              └──► WaterfallBuffer (circular row bitmap)
        │
        ├──► WavRecorder  (queued PCM16 → Music/LiteScope, MediaStore)
        └──► OverlayManager ──► ToolWindow × N (TYPE_APPLICATION_OVERLAY)
```

Design notes worth knowing:

* **One analysis thread, many views.** FFT/level work happens once in `SpectrumAnalyzer`; every view
  just reads the newest published frame or the ring buffer, so a tool shown twice never doubles CPU.
* **dBFS is real.** Magnitudes are normalised by `N/2 × coherent gain`, so 0 dBFS is a full-scale
  sine for every window function.
* **Analysis sample rate is decimated, not faked.** Selecting 16 kHz analysis rate runs the capture
  through a 63-tap windowed-sinc FIR and drops samples, so the spectrum axis is honest and no
  aliasing folds back into the display.
* **Waterfall memory is bounded.** The row bitmap is capped at a 4 MP budget; if the requested
  history needs more rows than that, the row rate drops instead of the history length shrinking.
* **Orientation without copies.** The waterfall bitmap is written so that increasing `y` is
  increasing time; rendering draws one wrapped block and mirrors it vertically for
  "newest on top" instead of copying rows.

### Design notes

* **Meters.** The value text can be placed four ways (Under bars / On bars / Between / Outside) so
  the meters suit both a slim window and a wide one. Each channel has a latching red clip rectangle
  on top of its bar (at the right end of the bar in the horizontal layout). The numbers live in
  rounded value pills - in the vertical layout they sit side by side *between* the meters, in the
  horizontal layout each pill sits at the head of its own bar - and the channel letters are the only
  text on the bars. A dedicated loudness block (peak / RMS / crest / balance plus the correlation
  bar) is drawn at the bottom for the vertical layout and at the left for the horizontal one; it is
  labelled `LEVELS dBFS` and holds, from top to bottom: **PEAK** (highest sample magnitude of either
  channel), **RMS** (loudest channel's RMS), **CREST** (peak minus RMS, i.e. how transient the signal
  is), **BAL** (right/left balance in dB) and **CORR** (inter-channel correlation, +1 mono, 0
  uncorrelated, -1 out of phase). Three channel layouts are available (split L/R, combined L+R, both)
  and the whole thing degrades gracefully: a 74 dp-wide window still shows slim bars, clip rectangles
  and levels.
* **Waterfall.** The history bitmap stores linear frequency bins, but it is *drawn* column by column
  through the live axis mapping, so on a logarithmic (or blended) axis the spectrogram lines up with
  the frequency labels instead of being stretched linearly.
* **Notification.** Copied from the sketch in `ideas/`: the system header supplies the app icon and
  name, and the custom content is a rounded black card holding the five circular tool toggles
  (accent-filled when on), then a row with the record circle, the monospace `m:ss.d` timer and the
  bare reset / settings / power glyphs, and finally the capture status centred under a miniature
  five-bar scope mark. The collapsed shade shows the status line only.
* **Windows.** The title bar collapses to a single "more" button below 250 dp; that button pops the
  actions out into their own small window, so even a 74 dp-wide scope keeps every control. The gear
  opens a **floating quick-settings panel for that tool only**, styled with the scope palette (a
  close button is all it needs) instead of sending you to the in-app settings page.
* **Cursors.** A cursor drag owns its whole gesture, so panning can never steal the marker while
  the axis is zoomed in. Each cursor (and the peak readout) can print the equivalent musical note,
  with the A4 reference configurable from 400-480 Hz (440 Hz by default).
* **Waterfall timing.** The analyser cannot always deliver the requested rows per second, so the hub
  measures the achieved rate and the waterfall uses it: the displayed history stays the number of
  seconds you asked for instead of silently stretching.
* **Theme.** Eight accents x eight background colours, applied to the scopes, the settings page and
  the notification. The accent is applied as a Material theme overlay, so the Android 14 style
  switches, buttons and ripples follow it natively. A choice of backdrop (plain / dot matrix /
  vignette / scanlines) and an optional trace glow sit on top of that.
* **Transparency.** Window opacity fades the scope *background* only: traces, cursors and readouts
  keep full contrast.

### Limitations (platform, not bugs)

* Apps can opt out of playback capture (`ALLOW_CAPTURE_BY_NONE`), and DRM-protected audio is never
  capturable — those apps will look silent.
* Phone calls and most voice-communication streams are not capturable by design.
* Capture rate is negotiated with the device: LiteScope requests your choice and falls back to the
  device's native output rate (the effective rate is always displayed).
* A MediaProjection consent is required after every reboot or capture restart, by the platform.

---

## Project layout

```
app/src/main/java/com/litescope/
├── audio/      AudioCaptureEngine, SpectrumAnalyzer, LevelMeter, WavRecorder, SignalGenerator
├── core/       Prefs, AudioBus, ScopeHub, FreqAxis, WaterfallBuffer, Tool
├── dsp/        Fft, Windows, Decimator, Colormaps, Notes, Frames
├── overlay/    OverlayManager, ToolWindow, WindowSnapper
├── service/    ScopeService, NotificationFactory, ScopeTileService
├── ui/         MainActivity, SettingsScreen
└── view/       ScopeView base, Spectrum, Waterfall, Waveform, Vector, Meter
app/src/test/java/com/litescope/dsp/   DSP unit tests
```
