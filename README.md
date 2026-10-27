# PowerApps Mobile Handheld Shell

A Tauri v2 Android shell that gives one Microsoft Power App its own device: a warehouse scanner, a
forklift-mounted tablet, a low-cost handset or the phone a field tech carries. `config.json` is
compiled into the APK, so a device gets one installable file with no loose configuration. Hardware
scan engine broadcasts are typed straight into the focused Power Apps control, with no reload and no
query-string round trip. GitHub Actions builds the APK from a copied template and publishes it as a
numbered release.

[Template page](https://spuds0588.github.io/PowerApps-Mobile-Handheld-Shell/) &middot;
[Latest build](https://github.com/Spuds0588/PowerApps-Mobile-Handheld-Shell/actions/workflows/release.yml)

Android 7 and newer, 64-bit and 32-bit ARM. APKs are signed with a public development key, so they
are for internal sideloading, not for any app store.

## Try the demo first

[Sideload the demo APK](https://github.com/Spuds0588/PowerApps-Mobile-Handheld-Shell/releases/tag/demo-latest)
to watch the shell work before you configure anything. It is built from `config.demo.json` and opens
[demo.html](https://spuds0588.github.io/PowerApps-Mobile-Handheld-Shell/demo.html), a stand-in pick list
with a diagnostics panel: focus a field, pull the trigger, and the page reports the value it received,
the field it landed in, and the `input` / `change` / `barcodeScanned` trail behind it. With no scanner
to hand, the page's **Simulate a scan** button performs the same DOM write the shell performs. A
collapsed **Device inputs** panel exercises the camera, microphone, file picker and location the same
way, which is how you confirm a device before handing it to an operator.

The demo release is rebuilt in place with `gh workflow run release.yml -f config=demo`. A demo run
never creates a version tag, and nothing is published unless it runs on `main`.

## Use this template

1. Click **Use this template** and create your repository.
2. Edit `config.json`: `appName`, `powerAppUrl`, `scanner`, `native`, `allowedDomains`, and the two kiosk
   flags. The [config builder](https://spuds0588.github.io/PowerApps-Mobile-Handheld-Shell/wizard.html)
   writes the file for you if you would rather not edit JSON.
3. Commit. The push starts **Build and Release Handheld APK**.
   A fresh copy may not run on its initial commit. If no run appears, open
   **Actions → Build and Release Handheld APK → Run workflow**.
4. When the run is green, download the APK from the newest release on the **Releases** page and
   sideload it:

   ```bash
   adb install -r app-universal-release.apk
   ```

   The first launch shows Android's one-time **Viewing full screen** hint over the kiosk. Tap
   **Got it**; it does not come back, and the system bars stay hidden from then on.

5. Point the device's scan profile at the app. On Zebra, open **DataWedge**, choose the profile,
   enable **Intent output**, set delivery to **Broadcast intent**, set the intent action to the same
   string as `scanner.intentAction` (`com.powerapps.handheld.SCAN` by default), and turn
   **Keystroke output** off. Typing is not affected; only the scan engine's key simulation is, which
   is what causes held keys and stray characters.

Each green build on `main` publishes the next patch version: `v1.0.0`, `v1.0.1`, `v1.0.2`, and so on,
with the APK attached and a table of the `config.json` it was built from. The version is stamped into
the APK at build time, and the workflow fails if the APK's version and the release tag disagree. The
run also uploads the APK as the `PowerApps-Handheld-Release-APK` artifact, but artifacts expire and
releases do not, so use the release. A push that only changes Markdown or the front page cuts no
release.

The template repository itself keeps only the demo release: its `config.json` is still the
placeholder, so the workflow skips numbered releases there. A copy has a different repository name,
so its builds release normally, starting at `v1.0.0`.

## Agent handoff

The [template page](https://spuds0588.github.io/PowerApps-Mobile-Handheld-Shell/#agent-title) carries
a copyable prompt for a coding agent. It tells the agent to read [agents.md](agents.md), ask for the
Power App link, the tenant domains and the device make, edit `config.json`, and walk you through the
commit that starts the build.

## Configure

`config.json` is the only file to edit.

| Key | Purpose |
| --- | --- |
| `appName` | Launcher label, task switcher name and splash text. |
| `powerAppUrl` | The Power Apps `/play/` link that opens on launch. |
| `keepScreenOn` | Prevents the display from sleeping during a shift. |
| `interceptBackButton` | Swallows the hardware and gesture Back button. |
| `native.camera` | Adds the `CAMERA` permission. Default `false`. |
| `native.microphone` | Adds `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS`. Default `false`. |
| `native.location` | Adds coarse and fine location. Default `false`. |
| `native.cameraScanner` | Adds the floating **Scan** button: the APK reads barcodes with the camera, through the same delivery path as a trigger pull. Declares `CAMERA` without opening the camera to the Power App. Default `false`. |
| `scanner.intentAction` | Broadcast action the OEM scan engine sends. Must match the device profile. |
| `scanner.intentExtraKey` | String extra holding the barcode. Defaults to Zebra's key. |
| `allowedDomains` | Domains allowed to load; subdomains included. Everything else is blocked. |

Invalid JSON, a missing key or a malformed `powerAppUrl` fails the build: `src-tauri/build.rs`
validates the file with the same schema the app uses, and `app/build.gradle.kts` reads it for the
Android label, the scanner intent filter and the device permissions. The schema is
[`src-tauri/src/config.rs`](src-tauri/src/config.rs).

### Device inputs

The camera, microphone and location are opt-in and off by default. Turning one on does two things:

- `app/build.gradle.kts` merges the matching permission overlay from
  `src-tauri/gen/android/app/src/features/`, so the APK only carries permissions its config uses. The
  camera, microphone and GPS features are marked not required, so a handheld without them still installs.
- `src-tauri/src/lib.rs` becomes the runtime gate. A permission the config does not enable is denied
  before Android is asked, and logged as `[NATIVE][DENIED] <kind> is not enabled in config.json`.

Android still shows its own prompt the first time an enabled app asks, and a denied prompt is not
repeated. To reset one, open **Settings → Apps → <app> → Permissions**, or reinstall the APK. The demo
profile enables all of them so the demo page can exercise them; the shipped `config.json` enables none.

### Camera scanner

A device with no scan engine (a cheap handset, a phone, a tablet) can still scan. `native.cameraScanner`
adds a floating **Scan** button; tapping it opens a camera preview, and the first barcode it reads closes
the preview and lands in the page. Torch and tap-to-focus report what the camera actually does: on a
camera with no flash unit the torch button reads **No flash** and stays disabled, and a tap on the
preview logs CameraX's answer rather than assuming it worked. Both are logged.

- It delivers through `BarcodeReceiver.dispatch`, the call the DataWedge broadcast makes, so a camera
  scan fires `input`, `change` and `barcodeScanned` exactly like a trigger pull. Power Apps cannot tell
  them apart, and nothing needs configuring in the app.
- Formats are Code 128, Code 39, EAN-13, UPC-A, ITF, QR, Data Matrix, PDF 417 and Aztec: ZXing core, no
  Play Services and no model download, so it works offline.
- The preview is an overlay inside the running app, never a second screen, so the page keeps its state
  and the focused field survives. Back closes the preview before the kiosk shield sees it, and the same
  overlay is reopened for every scan, so a shift's worth of scans costs one camera session each.
- With the soft keyboard open the **Scan** button sits behind it, so close the keyboard before scanning
  with the camera. A hardware trigger still lands in the focused field with the keyboard up.
- The ZXing and CameraX libraries are in every APK, even with the flag off; the button, the camera and
  the permission are not. See `history.md` for the measured size.
- The Power Apps **Barcode reader** control is still not reachable: Microsoft runs that one natively and
  never asks the page. This scanner is ours, and it writes into the control the operator last touched.

Attachments and photo capture need no permission: file uploads use the system picker, and the capture
flow uses the device's camera app. Both are available whatever the three flags say. Two manifest pieces
have to stay for capture to work: the `IMAGE_CAPTURE` intent under `<queries>`, without which Android 11+
hides the camera app from the shell and the input silently opens the picker instead, and
`external-files-path` in `res/xml/file_paths.xml`, without which the JPEG the shell writes cannot be
shared back to the page.

Add every domain your app or its sign-in flow needs to `allowedDomains`. The shipped list is
Microsoft's documented Power Apps set: `*.powerapps.com` (the play and content hosts),
`login.microsoftonline.com`, `login.microsoft.com`, `login.windows.net`,
`secure.aadcdn.microsoftonline-p.com`, plus the sign-in CDNs (`aadcdn.msauth.net`,
`aadcdn.msftauth.net`), the federated STS host and the seamless SSO host. A tenant can still need more:
Microsoft's Power Apps requirements table also names the CDN (`*.azureedge.net`, `*.azurefd.net`),
profile (`graph.microsoft.com`) and connector (`*.azure-apim.net`) hosts, and a personal Microsoft
account signs in through `login.live.com`. Navigation outside the list is blocked and logged as
`[SECURITY][BLOCKED]`; nothing else breaks, so a missing domain shows up as a stuck page in logcat
rather than a crash. Source:
[Power Apps system requirements and limits](https://learn.microsoft.com/en-us/power-apps/limits-and-config).

## How scanning works

1. The OEM scan engine broadcasts an intent with the configured action.
2. `BarcodeReceiver.kt` reads the configured extra key, then the common OEM fallbacks.
3. The payload crosses into Rust over JNI (`Java_com_powerapps_handheld_BarcodeReceiver_nativeOnScan`).
4. Rust evaluates a small script in the running webview: native value setter, then `input` and
   `change` events on `document.activeElement`, plus `window.__LAST_BARCODE__` and a
   `barcodeScanned` window event. If the page lost focus, the last field the operator touched is used
   instead of the body.

No navigation, no reload, no query parameter, so an in-progress form survives a hundred scans.

## Where the APK lands

- Published: the newest numbered release on the [Releases](https://github.com/Spuds0588/PowerApps-Mobile-Handheld-Shell/releases) page, as `app-universal-release.apk`.
- Demo: [demo-latest](https://github.com/Spuds0588/PowerApps-Mobile-Handheld-Shell/releases/tag/demo-latest), as `PowerApps-Handheld-Demo.apk`, built from `config.demo.json`.
- In the run: `src-tauri/gen/android/app/build/outputs/apk/universal/release/app-universal-release.apk`.
- Downloaded from the run: `app-universal-release.apk` inside the `PowerApps-Handheld-Release-APK`
  artifact, which expires after 90 days.
- The workflow verifies the APK before publishing: signature, both ARM libraries, the version matching
  the tag, the merged manifest's receiver action, and the compiled-in `config.json`.

## Verify and maintain

```bash
cargo fmt --manifest-path src-tauri/Cargo.toml --check
cargo check --manifest-path src-tauri/Cargo.toml --target aarch64-linux-android --lib
python3 -m json.tool config.json
```

Build rules, CI discipline and cleanup: [agents.md](agents.md).
Decisions, PRD deviations and measured build/CI findings: [history.md](history.md).
Remaining work: [todo.md](todo.md).

## What is tested, and what is not

Everything here is exercised on emulators, never on a rugged handheld — none was available while this
was built. `history.md` records each measurement on the released demo APK: the kiosk behaviour on an API
36 `handheld-phone` AVD, the scan path driven with `adb shell am broadcast`, the device permissions and
their denials, file upload, photo capture, the location readout and the scanner overlay.

Treat these as designed rather than proven until someone runs them on a device: a real Zebra, Honeywell
or Datalogic scan engine with its DataWedge or Enterprise Scan configuration, a real trigger pull without
the `am` background flag, a value landing in a live Power Apps control, a real Entra ID tenant sign-in, a
printed barcode through the camera scanner, the torch and tap-to-focus against real hardware, and the
microphone signal. `todo.md` marks each one.

If you have the hardware, please open an
[issue](https://github.com/Spuds0588/PowerApps-Mobile-Handheld-Shell/issues) with the device, its
Android and scan-engine versions, what you ran and the logcat lines. A failure report is worth as much
as a success, and it is the only way these checks close.

## License

MIT
