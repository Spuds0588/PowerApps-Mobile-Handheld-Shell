# PowerApps Mobile Handheld Shell

A Tauri v2 Android shell that puts one Microsoft Power App on one rugged handheld. `config.json` is
compiled into the APK, so a device gets one installable file with no loose configuration. Hardware
scan engine broadcasts are typed straight into the focused Power Apps control, with no reload and no
query-string round trip. GitHub Actions builds the APK from a copied template.

[Template page](https://spuds0588.github.io/PowerApps-Mobile-Handheld-Shell/) &middot;
[Latest build](https://github.com/Spuds0588/PowerApps-Mobile-Handheld-Shell/actions/workflows/release.yml)

Android 7 and newer, 64-bit and 32-bit ARM. APKs are signed with a public development key, so they
are for internal sideloading, not for any app store.

## Use this template

1. Click **Use this template** and create your repository.
2. Edit `config.json`: `appName`, `powerAppUrl`, `scanner`, `allowedDomains`, and the two kiosk flags.
3. Commit. The push starts **Build Handheld PowerApps APK**.
   A fresh copy may not run on its initial commit. If no run appears, open
   **Actions → Build Handheld PowerApps APK → Run workflow**.
4. In the latest green run, download **PowerApps-Handheld-Release-APK** and sideload the APK:

   ```bash
   adb install -r app-universal-release.apk
   ```

5. Point the device's scan profile at the app. On Zebra, open **DataWedge**, choose the profile,
   enable **Intent output**, set delivery to **Broadcast intent**, and set the intent action to the
   same string as `scanner.intentAction` (`com.powerapps.handheld.SCAN` by default).

Your APK is a build artifact, not a release. It is tied to your repository and expires, so download
it when you need it.

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
| `scanner.intentAction` | Broadcast action the OEM scan engine sends. Must match the device profile. |
| `scanner.intentExtraKey` | String extra holding the barcode. Defaults to Zebra's key. |
| `allowedDomains` | Domains allowed to load; subdomains included. Everything else is blocked. |

Invalid JSON, a missing key or a malformed `powerAppUrl` fails the build: `src-tauri/build.rs`
validates the file with the same schema the app uses, and `app/build.gradle.kts` reads it for the
Android label and the scanner intent filter. The schema is
[`src-tauri/src/config.rs`](src-tauri/src/config.rs).

Add every domain your app or its sign-in flow needs to `allowedDomains`. Navigation outside the list
is blocked and logged as `[SECURITY][BLOCKED]`; nothing else breaks, so a missing domain shows up as a
stuck page in logcat rather than a crash.

## How scanning works

1. The OEM scan engine broadcasts an intent with the configured action.
2. `BarcodeReceiver.kt` reads the configured extra key, then the common OEM fallbacks.
3. The payload crosses into Rust over JNI (`Java_com_powerapps_handheld_BarcodeReceiver_nativeOnScan`).
4. Rust evaluates a small script in the running webview: native value setter, then `input` and
   `change` events on `document.activeElement`, plus `window.__LAST_BARCODE__` and a
   `barcodeScanned` window event.

No navigation, no reload, no query parameter, so an in-progress form survives a hundred scans.

## Where the APK lands

- In the run: `src-tauri/gen/android/app/build/outputs/apk/universal/release/app-universal-release.apk`.
- Downloaded: `app-universal-release.apk` inside the `PowerApps-Handheld-Release-APK` artifact.
- The workflow verifies the APK before uploading: signature, both ARM libraries, the merged manifest's
  receiver action, and the compiled-in `config.json`.

## Verify and maintain

```bash
cargo fmt --manifest-path src-tauri/Cargo.toml --check
cargo check --manifest-path src-tauri/Cargo.toml --target aarch64-linux-android --lib
python3 -m json.tool config.json
```

Build rules, CI discipline and cleanup: [agents.md](agents.md).
Decisions, PRD deviations and measured build/CI findings: [history.md](history.md).
Remaining work: [todo.md](todo.md).

## License

MIT
