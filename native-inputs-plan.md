# Native inputs plan

Status: approved, not built. Written 2026-10-26 against `tauri 2.12.5`, `wry 0.57.1`,
`AGP 9.3.1`, `Gradle 9.6.1`, and an API 36 (Android 16) emulator.

The shell today is one Power App in one WebView plus a hardware scan bridge. Everything else a canvas
app can do (camera, microphone, attachments, location) depends on WebView behaviour that nobody has
checked. This document records what actually happens, what has to change, and the design for a bundled
camera scanner that delivers into the same place a hardware trigger does.

The claims about WebView behaviour and about the Android build API were verified by reading the pinned
dependencies, not from memory. The commands to re-check them are at the end.

## What is true today

| Capability | Power App side | Wrapper today | Work needed |
| --- | --- | --- | --- |
| Attachments / add file | `<input type="file">` | wry implements the whole chooser: MIME types, multi-select, DocumentsUI (`wry 0.57.1`, `src/android/kotlin/RustWebChromeClient.kt:310-460`). No manifest permission is involved. | Verify on a device. No code change expected. |
| Take a photo from that picker | `<input type="file" accept="image/*" capture>` | wry writes the JPEG to `getExternalFilesDir(Pictures)` and shares it as `<package>.fileprovider` (`RustWebChromeClient.kt:493-509`). Our [file_paths.xml](src-tauri/gen/android/app/src/main/res/xml/file_paths.xml) declares `external-path` and `cache-path`, which do not cover that directory, so `getUriForFile` throws, wry catches it, and the flow silently falls back to a plain file picker. | One line: add `<external-files-path name="capture" path="." />`. |
| Camera control (live preview) | `getUserMedia({video})` | wry asks the app through `onPermissionRequest`; with no handler set it grants the known resources and then requests `CAMERA` at runtime (`RustWebChromeClient.kt:60-115`). Our manifest declares only `INTERNET`, so Android refuses that request without ever prompting. | Declare `CAMERA` when enabled, gate it, add `uses-feature … required="false"`. |
| Microphone / audio | `getUserMedia({audio})` | Same bridge. It asks for `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS`, neither declared. | Declare both, gate. |
| Location | `Location` signal | wry's geolocation prompt requests `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` itself, and only when they are declared (`RustWebChromeClient.kt:250-310`). Geolocation is already enabled on the WebView. | Declare both, gate. |
| Downloads (PDF export, attachment save) | `<a download>`, blob URLs | Nothing happens and nothing is logged. There is no `DownloadListener` anywhere in wry's Android sources, and Tauri exposes no download API. | Deferred, see open decisions. |
| `Launch()` to mail, tel, external sites | `mailto:`, `tel:`, off-allowlist https | [lib.rs](src-tauri/src/lib.rs) `on_navigation` returns false for any host outside `allowedDomains` (`lib.rs:134-144`), so these end as `[SECURITY][BLOCKED]`. | Deferred, see open decisions. |
| Pen, signature, video playback, fullscreen | Canvas and `<video>` | Already works in a WebView; wry even implements fullscreen video. | Nothing. |

## What no wrapper can provide

The native **Barcode reader control** cannot be served by this shell. Microsoft documents it as
powered by the Power Apps mobile app's own scanner, with no browser path
([control docs](https://learn.microsoft.com/en-us/power-apps/maker/canvas-apps/controls/control-barcodereader)),
so it never asks the page and no WebView permission can satisfy it. The obsolete web barcode scanner
control does use the camera stream, and would work once camera access is enabled.

Also out of reach without a native bridge: Notification API, Web Share, Web Bluetooth/USB/Serial, NFC,
and clipboard-read. `PermissionKind` marks several of these unsupported on Android, and the WebView
itself has no implementation behind them.

## v1 decisions

**1. Opt in per feature in `config.json`, off by default.** An optional block, so every config that
exists today still builds:

```json
"native": { "camera": false, "cameraScanner": false, "microphone": false, "location": false }
```

`#[serde(default)]` on the field keeps old files valid, `build.rs` keeps validating the file, and the
default of every flag is `false`. A template copy that never touches the block gets the same
permissions and behaviour it has now.

**2. Permissions are added at build time from that block.** `variant.sources.manifests.
addStaticManifestFile(...)` exists in AGP 9.3.1, so three small overlay manifests merge only when their
flag is on, from the same `shellConfig` parse that already supplies the app label and scan action
(`src-tauri/gen/android/app/build.gradle.kts:21`). `CAMERA` is included when either `camera` or
`cameraScanner` is true.

This is not install-screen polish. Declaring `CAMERA` changes the photo flow: with it declared but not
granted, wry asks for camera permission before launching the camera app; with it absent, the camera app
launches with no prompt at all. A maker who leaves the camera off should keep both the clean flow and
the clean permission list.

If the variant API ever fights the build, the fallback is declaring all permissions in the main
manifest and accepting the extra prompt. That is a deliberate downgrade, not an equivalent.

**3. Gate at runtime as well.** `WebviewWindowBuilder::on_permission_request` reaches wry's Android
bridge in Tauri 2.12.5. Return `Default` when the flag is on, so Android still asks the operator, and
`Deny` when it is off, logging `[NATIVE][DENIED] camera` in the style of the existing logs. A config
that says off stays off even if an MDM already granted the OS permission.

**4. Attachments stay permission-free.** Uploads need no manifest entry. The only change is the
`file_paths.xml` line above. The photo flow hands off to the device's camera app, so a handheld with
no camera app still falls back to the picker, which is the behaviour it has today.

**5. The demo profile turns the v1 features on** and the demo page grows a capabilities card, so the
demo APK doubles as the acceptance harness the way the scan panel already does.

## v2: bundled camera scanner

**Delivered.** `cameraScanner` is in the shipped schema, the overlay and decoder are in
`src-tauri/gen/android/app/src/main/java/com/powerapps/handheld/`, and the emulator run in `history.md`
shows a live camera frame decoded and delivered into the page.

Yes, and it is the cheap kind of yes: the scanner ends up calling the exact function a hardware trigger
already calls.

### Path

The scanner decodes a frame, then calls `BarcodeReceiver.nativeOnScan(value)`, the JNI entry the
DataWedge broadcast already uses. From there it is the existing Rust `inject_scanned_barcode`: write
the value into the focused control, fire `input` and `change`, dispatch `barcodeScanned` on `window`.
A Power App cannot tell a camera scan from a trigger pull, existing `barcodeScanned` listeners keep
working, and there is nothing to configure in Power Apps and no scan profile to set up.

### Decoding

ZXing `core`, Apache-2.0, roughly 0.6 MB, plain Java, no Play Services, works offline, with a
`PlanarYUVLuminanceSource` adapter over CameraX `ImageAnalysis`. Formats: Code 128, Code 39, EAN-13,
UPC-A, ITF, QR, Data Matrix, PDF417 and Aztec, which covers what Zebra and Honeywell engines read by
default.

ML Kit was rejected: it needs Play Services and a model download, both wrong for offline fleets on
devices that may not carry GMS at all.

### Camera

CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`), compatible with minSdk 24, which
gives preview, tap-to-focus and torch without NDK work.

### Where it lives

A view overlay inside `MainActivity`, not a second activity. A separate activity pauses the WebView,
and on a low-RAM handheld Android may kill it, which reloads the Power App and loses whatever the
operator had typed. The overlay keeps the page alive and keeps the DOM focus state. Back closes the
overlay before the kiosk Back suppression sees it.

### Trigger

A floating scan button, 56dp, bottom-right, shown only when `cameraScanner` is true. It is a native
view, not an injected DOM node, so a Power App re-render cannot remove it and it works on any page
including the sign-in screens.

### The focus fix this forces

Android moves the WebView's focus to the body when our overlay appears, so `document.activeElement` is
not reliable at inject time. Add a `focusin` listener in `INIT_SCRIPT` that remembers the last focused
field, and have `INJECTION_SCRIPT` fall back to it. This also fixes the existing hardware path when a
scan arrives after focus was lost, which is why it belongs in v1 rather than with the scanner.

### Costs and limits

- APK growth is estimated at 1.5 to 2.5 MB against today's 11.5 MB. Measure it; above 3 MB, revisit the
  decoder before shipping.
- The library ships in every build even when the flag is off, because AGP 9.3.1 no longer offers a way
  to add Kotlin sources per variant. A runtime flag is the cheap version; a separate Gradle module is
  the expensive one.
- Camera scanning needs `CAMERA`, so `cameraScanner: true` declares it even when `camera: false`. That
  is the right kiosk combination: our reader scans, `getUserMedia` stays closed to the app.
- Decoding on the emulator is awkward: the virtual scene shows furniture, not barcodes, and its YUV
  analysis stream tops out at 640x480. The way that worked was `Toren1BD.posters` in the emulator's
  `resources/` directory: three extra flat posters placed around the scene camera at 1.6 world units,
  one holding a generated QR. The decoder's deterministic unit test is the permanent guard; the poster
  recipe is in `history.md` for the next run.
- Scanning while the soft keyboard is open: measured, and the concern was half wrong. The floating
  button sits behind the IME, so the overlay cannot be opened while typing; a hardware trigger still
  lands in the focused field with the keyboard up. A camera scan after Back fills the remembered field
  and the keyboard stays down, which leaves focus where the operator was. See `history.md`.

## Changes, by layer

v1:

- `src-tauri/src/config.rs`: `NativeConfig`, `#[serde(default)]` on the field, `Default` for the struct.
- `src-tauri/src/lib.rs`: permission handler on the window builder, one `[CONFIG] native …` startup
  line, `[NATIVE][DENIED]` logs, last-focused-field memory in the init and injection scripts.
- `src-tauri/gen/android/app/build.gradle.kts`: read the block, add the overlay manifests.
- `src-tauri/gen/android/app/src/features/{camera,microphone,location}/AndroidManifest.xml`: the
  permissions, plus `uses-feature … required="false"` for camera, microphone and GPS so handhelds
  without them still install.
- `src-tauri/gen/android/app/src/main/res/xml/file_paths.xml`: the `external-files-path` entry.
- [demo.html](demo.html): capabilities card behind clearly labelled buttons.
- [wizard.html](wizard.html): three checkboxes in the device step, written into the generated JSON.
- [index.html](index.html): the "Honest limits" line becomes "No CameraX, no ML Kit. The camera is off
  unless you turn it on." The config reference gains the block.
- [README.md](README.md): the block, the permission consequences, and how to reset a denied prompt.
- [.github/workflows/release.yml](.github/workflows/release.yml): assert the APK's permission list
  matches the config in the existing Verify APK step, so a regression fails the build.
- [agents.md](agents.md): one rule that a new device permission goes through the `native` block and
  this document.

v2:

- `app/build.gradle.kts`: CameraX and ZXing dependencies.
- New Kotlin: `ScanOverlay.kt` (preview, analysis, torch, close), `BarcodeDecoder.kt` (ZXing plus the
  YUV adapter), the button in `MainActivity`, and a `BarcodeReceiver.dispatch` wrapper.
- `config.rs`, wizard and docs: `cameraScanner`.

## Phases

The checklist of record is Phase 9 and Phase 10 in [todo.md](todo.md); this table carries only the
estimate and the exit criteria for each phase.

| Phase | Work | Est. | Done when |
| --- | --- | --- | --- |
| v1.1 | Config schema, overlays, Gradle wiring, `uses-feature`, runtime gate, startup log | 2-3 h | All flags off: `INTERNET` only. All on: the six. Both assemble locally |
| v1.2 | Camera and microphone end to end | 2-3 h | Preview paints and stops; audio track opens; a denied config rejects with no OS prompt |
| v1.3 | Location end to end | 1 h | `adb emu geo fix` coordinates appear in the page |
| v1.4 | Files: capture fix, uploads verified | 1 h | File name and size shown; capture returns a JPEG instead of silently falling back |
| v1.5 | Demo harness, wizard, docs, CI assertion | 2-3 h | Branch runs green with features on and off; permission lists asserted; Pages copy live |
| v1.6 | Focus memory for the existing scan path | 1 h | A scan after tapping away still lands in the last field |
| v2.1 | ZXing decoder and YUV adapter, unit-tested on static frames | 4 h | Code 128, QR and Data Matrix decode from fixtures |
| v2.2 | CameraX preview overlay, torch, close, permission request | 1-2 d | Overlay scans a printed code on hardware; Back closes it; the page stays alive |
| v2.3 | Delivery through `nativeOnScan`, debounce, button, config flag | 4 h | A camera scan lands in the focused field and fires `barcodeScanned`, identical to a trigger pull |
| v2.4 | Size measurement, docs, demo profile, device checklist | 4 h | APK delta recorded; README, plan and todo updated |

v1 is about a day. v2 is about three more.

## Acceptance checks (v1, API 36 emulator)

- Camera on: `aapt dump permissions` lists `CAMERA`; `getUserMedia` video resolves; the preview is
  visible; stopping releases the device.
- Camera off: no `CAMERA` in the APK; `getUserMedia` rejects with `NotAllowedError` even after
  `adb shell pm grant`, `[NATIVE][DENIED]` appears in logcat, and no system dialog is shown.
- Microphone on: `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS` present; the demo's level meter moves.
- Location on: one dialog, then `adb emu geo fix -122.4194 37.7749` shows up in the readout.
- Files: an upload from Downloads works with no permission; capture writes a JPEG through the provider.
- Install compatibility: `aapt dump badging` reports camera, microphone and GPS as not required.
  Confirmed on the released demo APK: `android.hardware.camera`, `.microphone`, `.location`,
  `.location.gps` and `.location.network` all report `uses-feature-not-required`.

The only image on this host is an Android TV build: no DocumentsUI, a camera stub instead of a camera
app, and no GPS provider (`cmd location providers add-test-provider` is refused for the shell uid). The
upload, capture and location-readout checks therefore stay open; everything else in the list was
observed and recorded in `history.md`.

## Open decisions

1. **Downloads.** No Android support exists in wry, so attachment saves and PDF exports do nothing.
   Options are a small Kotlin `DownloadListener` reached through Tauri's `with_webview`, handing the
   URL to the system browser, or documenting the gap. Not scheduled: nobody has asked, and guessing at
   the flow without a real maker use case is how templates grow dead code.
2. **External intents.** `mailto:`, `tel:` and off-allowlist links are blocked today. The natural fix is
   the official opener plugin called from `on_navigation`, but that is a new dependency and an escape
   hatch out of the kiosk. Needs its own decision, not a silent addition.

## Deliberately not doing

- Decoding camera frames in JavaScript inside the page: slower, fights the Power App DOM, and would not
  share the native delivery path.
- Notifications, Web Share, Bluetooth printing, NFC, WebUSB: no WebView API behind them.
- The Power Apps native barcode reader control: not reachable from a WebView at all.
- Media read permissions: the picker and the FileProvider need none, so `READ_MEDIA_*` stays out.

## Re-checking the claims

```bash
# Pinned versions this document was written against
grep -A2 '^name = "wry"' src-tauri/Cargo.lock | head -3
grep -A2 '^name = "tauri"$' src-tauri/Cargo.lock | head -3

# wry behaviour relied on above
grep -n "onShowFileChooser\|onPermissionRequest\|onGeolocationPermissionsShowPrompt" \
  ~/.cargo/registry/src/*/wry-0.57.1/src/android/kotlin/RustWebChromeClient.kt

# No download listener on Android
grep -rn -i "download" ~/.cargo/registry/src/*/wry-0.57.1/src/android/

# The AGP API used for build-time permissions
javap -classpath ~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/gradle-api/9.3.1/*/gradle-api-9.3.1.jar \
  com.android.build.api.variant.ManifestFiles

# What the current builds actually declare
aapt dump permissions app-universal-release.apk
```
