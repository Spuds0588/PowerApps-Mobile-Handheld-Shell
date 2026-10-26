# History

Newest first. Every number here was measured on this machine or in a named GitHub Actions run.

## 2026-10-26 — First green CI run, Pages unblocked

**Status:** run `40336020894` (push, commit `924bc3f`) succeeded. Job wall **5m54s**; the APK build
step alone was **318.7s** on a cold Rust cache. Artifact `PowerApps-Handheld-Release-APK` downloaded
and inspected by hand.

### Step timings (that run)

| Step | Wall |
| --- | --- |
| Set up job / Node / JDK / SDK+NDK install | 4.2s / 4.9s / 0.1s / 2.9s |
| Set up Rust | 10.8s |
| Cache Rust build | 0.4s (miss) |
| Install Tauri CLI | 1.0s |
| **Build release APK** | **318.7s** |
| Verify APK | 0.9s |
| Upload APK | 1.7s |

### The downloaded artifact

- `app-universal-release.apk`, **11,527,470 bytes**, SHA-256 `9b019688aa93fce2a92086459fa40265c4b8acc641a9f42119d7cbea73ff063d`.
- Signed with the committed dev key — same certificate SHA-256 (`4ae9b5f4…980f`) as the local build.
- `com.powerapps.handheld`, versionName 1.0.0, targetSdk 37, label `PowerApps Handheld Shell`,
  `native-code: 'arm64-v8a' 'armeabi-v7a'`.
- `lib/arm64-v8a/libpowerapps_mobile_handheld_shell.so` (5,964,288 B) and
  `lib/armeabi-v7a/libpowerapps_mobile_handheld_shell.so` (3,926,684 B).
- Manifest carries `com.powerapps.handheld.BarcodeReceiver`, `exported=true`.
- The stripped `.so` still contains `"powerAppUrl": "https://apps.powerapps.com/play/e/default-tenant/a/app-id?tenantId=your-tenant-id"`
  and `"appName": "PowerApps Handheld Shell"`, so the committed config really is compiled in.
- The workflow's own `Verify APK` step printed `Verifies` and
  `Embedded config found in libshell.so: PowerApps Handheld Shell`, so the guard is live rather than
  decorative.

### Failure 1 — `android-actions/setup-android@v3` kills the job in 21s

```
[command]/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager tools
Warning: Failed to find package 'tools'
Error: The process '.../sdkmanager' failed with exit code 1
```

The action still runs the long-removed `tools` package, so it fails before any of our steps.
`ubuntu-latest` already ships a complete SDK (`/usr/local/lib/android/sdk`, cmdline-tools 16.0), so the
action was dropped entirely. The workflow now installs its pinned platform, build tools and NDK with
`sdkmanager` from that SDK, adds `cmdline-tools/*/bin` to PATH if `sdkmanager` is not already there,
accepts licences, and asserts both `$NDK_HOME` and `platforms/android-37.0` exist. Removing a
third-party setup action removed a whole class of failure.

### Failure 2 — every Pages deployment had failed since the initial commit

```
Liquid syntax error (line 180): Variable '{{ try {{ const val = '{barcode}' was not properly terminated
```

GitHub Pages builds this branch with Jekyll, and Jekyll was trying to render
`PRD-PowerApps Mobile Handheld Shell.md`, whose Rust sample contains `{{ ... }}`. The site 404'd.
Fixed with a root `_config.yml` that excludes that one file. `agents.md`, `history.md` and `todo.md`
still render as pages, and the root `index.html` is served at the site root (verified: HTTP 200, new
`<title>`, `agents.html` / `history.html` / `todo.html` all 200).

### Other findings

1. **A cancelled run can block the Rust cache save.**
   `Failed to save: Unable to reserve cache with key v1-build-apk-Linux-x64-f273623e-95e1c76a, another
   job may be creating this cache.` The previous run for the same lockfile was cancelled by
   `concurrency` mid-build and its reservation was still held. Harmless (the next run rebuilds cold)
   and self-healing, but it is why there was no cache to restore here.
2. **Action majors matter now.** `actions/checkout@v4`, `setup-node@v4`, `setup-java@v4` and
   `upload-artifact@v4` run on Node 20, which the runner forces onto Node 24 with a deprecation
   warning; `setup-java@v4` is itself deprecated. Bumped to `checkout@v7`, `setup-node@v7`,
   `setup-java@v6`, `upload-artifact@v7` — all four ran clean in the green job.
3. **Pushing a docs commit cancels the in-flight build** because of `concurrency: cancel-in-progress`.
   Expected, and worth knowing before a long build is thrown away mid-flight.
4. **`platforms;android-37.0` is correct and `platforms;android-37` is not.** Confirmed again in CI:
   the pinned install succeeded and the assertion on `platforms/android-37.0` passed.

## 2026-10-26 — Emulator playtest: the whole scan path, on a real Android image

**Status:** a debug x86_64 APK booted on an Android 16 (API 36) emulator and every requirement that
can be exercised without rugged hardware was observed. Config was temporarily pointed at a local
page, then restored byte-for-byte; the committed `config.json` matches the production file.

### Setup

- AVD `handheld-test` built from the only installed system image (`android-36;android-tv;x86_64`).
  `avdmanager create avd` could not be driven non-interactively on this host (it ignores piped stdin
  and then throws `offset 0, count -1, length 256` on EOF), so the AVD was written by hand from a
  minimal `config.ini`. It booted with KVM, `-no-window`, `swiftshader_indirect`.
- Host test page served on `127.0.0.1:8766` and exposed with `adb reverse tcp:8766 tcp:8766`.
  **The emulator's `10.0.2.2` host alias did not route** (`ERR_CONNECTION_REFUSED`); `adb reverse` did.
- Temporary config: `appName: Handheld Playtest`, `powerAppUrl: http://127.0.0.1:8766/test.html?block=1`,
  `allowedDomains: ["127.0.0.1"]`. A debug build was used because release sets
  `usesCleartextTraffic=false` and the test page is plain HTTP.
- The page reported each received DOM event back to the host over `fetch`, so the proof is a server
  log line, not a screenshot alone.

### Observed

| Check | Result |
| --- | --- |
| Install and launch | `adb install` succeeded; `MainActivity` became `topResumedActivity`. |
| Embedded config | `[INIT] Starting PowerApps Mobile Handheld Shell: 'Handheld Playtest'`, plus the kiosk and scanner lines. |
| Webview boot | `[LAYOUT][READY]`, then `[NAVIGATION][ALLOWED] http://127.0.0.1:8766/test.html?block=1`; the page's own `loaded=1` beacon arrived. |
| Scanner broadcast | `am broadcast` with the configured action and extra reached `BarcodeReceiver`. |
| JNI bridge | `[SCANNER][RECEIVED]` in both the Kotlin tag and Rust, then `[SCANNER][SUCCESS]`. |
| DOM injection | Page logged `[SCANNER][INJECTED]`; host saw `/record?input=`, `/record?change=` and `/record?event=` for the same value. |
| Zero reload | `/test.html` was fetched once. Later scans produced only `/record` calls. |
| Allowlist | The page's timed `location.href = http://example.com/` produced `[SECURITY][BLOCKED] Unauthorized navigation: http://example.com/` and did not navigate. |
| Back shield | `input keyevent KEYCODE_BACK` logged `[SECURITY][BLOCKED] Hardware Back suppressed.` and the activity stayed `topResumedActivity`. |
| Keep screen on | `dumpsys window` reports `fl=KEEP_SCREEN_ON` on the activity window. |
| Edge-to-edge | Screenshot fills 1280x720 with no status bar, navigation bar or browser chrome. |

### Findings

1. **A shell-sent implicit broadcast needs `--receiver-include-background`.** A plain
   `adb shell am broadcast -a <action>` was enqueued with **0 receivers** even with the app resumed and
   `stopped=false`; `--receiver-include-background` delivered it immediately. This is `am`'s default
   background restriction, not a fault in the app: the manifest receiver and its config-derived action
   are correct, and explicit (`-n`) delivery always worked. Anyone reproducing a scan should use:
   `adb shell am broadcast -a com.powerapps.handheld.SCAN --receiver-include-background --es com.symbol.datawedge.data_string TEST-123`.
   A real DataWedge broadcast from a foreground kiosk is a normal app broadcast and is not sent with
   that restriction, but this is the one thing a physical device still has to confirm.
2. **The JNI symbol resolves.** `Java_com_powerapps_handheld_BarcodeReceiver_nativeOnScan` was found
   by the app's class loader with no extra wiring beyond `System.loadLibrary` in the receiver's
   companion object. This was the highest-risk part of the design and it needed no fallback.
3. **The native value setter plus `input`/`change` is enough** for a plain `<input>`. Power Apps runs
   React, which listens for the same events, but the Power Apps control itself was not exercised.
4. **The TV image cannot prove immersive mode** (TV images have no status or navigation bars).
   `WindowCompat` + `WindowInsetsControllerCompat` is in place and the screenshot is edge-to-edge, but
   a phone or handheld profile is the real test.

## 2026-10-26 — Release APK built and verified locally

**Status:** `tauri android build --apk --target aarch64` produces a signed 7,584,815-byte APK
(`app-universal-release.apk`). Rust release compile: **2m00s** for the Tauri dependency tree on
8 cores.

### The one blocking failure, and its fix

The first build failed after 44s in `:app:rustBuildArm64Release`:

```
Process 'command 'node'' finished with non-zero exit value 1
Error: Cannot find module '<repo>/src-tauri/tauri'
```

Tauri generates `gen/android/buildSrc/.../BuildTask.kt` from the command it was initialised with. When
`tauri android init` is reached through the **npm** package its `current_exe()` is `node`, and because
there was no `npm_execpath` in the environment it wrote `executable = "node"`, `args = ["tauri",
"android", "android-studio-script"]`. Gradle then runs that from `src-tauri/`, where no file called
`tauri` exists. (Reproduced root cause in `crates/tauri-cli/src/mobile/init.rs`, the `node` branch.)

Fix, in the committed project: `executable = "tauri"`, `args = ["android", "android-studio-script"]`.
That works for the global npm CLI used locally and in CI. **Anyone who regenerates `gen/android` must
re-apply this two-line change**, or the Gradle-side Rust task cannot find the CLI.

### Artifact checks (release APK)

| Check | Result |
| --- | --- |
| `apksigner verify` | `Verifies`; APK Signature Scheme **v2** only, no v1 JAR signature. minSdk 24 makes that valid. |
| Signer | `CN=PowerApps Handheld Shell Dev Key …`, SHA-256 `4ae9b5f4…980f` (the committed dev key). |
| `aapt2 dump badging` | `com.powerapps.handheld`, versionCode 1000000, versionName 1.0.0, targetSdk 37, `native-code: 'arm64-v8a'`. |
| App label | `PowerApps Handheld Shell` — from `config.json`, not from `productName`. |
| Merged manifest | `com.powerapps.handheld.BarcodeReceiver`, `exported=true`, action `com.powerapps.handheld.SCAN`. |
| Payloads | `classes.dex`, `AndroidManifest.xml`, `lib/arm64-v8a/libpowerapps_mobile_handheld_shell.so`. |
| Embedded config | `strings` on the stripped `.so` contains the `powerAppUrl` value from `config.json`. |
| JNI symbols | `classes.dex` keeps `BarcodeReceiver` and `nativeOnScan`, so the ProGuard keep rule works. |

Note the release APK has **no `META-INF/*.RSA`** because v2 is the only scheme used. A CI check for a
JAR signature file would fail on a perfectly good artifact; `apksigner verify` is the real test.

## 2026-10-26 — Mission start: doc audit, sister-project lessons, and Tauri v2 Android facts

### What the repo contained

- `PRD-PowerApps Mobile Handheld Shell.md` — 485 lines: PRD, implementation guide, task list and most
  of an `AGENTS.md`. **It is truncated mid-sentence at Part 4 rule 6** ("* `[CONFIG]` Configuration par").
  There was no missing tail on disk. Parts 1–3 are intact and were treated as the contract.
- `README.md` — one paragraph, no quickstart, no config reference.
- `LICENSE` — MIT, Corey Burns, 2026.
- No app source, no workflow, no icons, no `.gitignore`, no Pages front door.
- Repo is **already flagged as a template**, public, default branch `main`.
- **Pages is enabled on the legacy `main` / root source.** It served the README at
  `https://spuds0588.github.io/PowerApps-Mobile-Handheld-Shell/`. No Pages workflow was added; the
  branch source picks up a root `index.html` on its own.

### Sister-project lessons carried over

Read `Spuds0588/Powerapp-Frontline-Workspace-Shell` (Tauri v2, Windows) end to end: `agents.md`,
`history.md`, `todo.md`, `release.yml`, `index.html`.

1. **Shared config schema between `build.rs` and the runtime** stops the two from drifting. Reused
   verbatim as `src-tauri/src/config.rs`.
2. **The brief's sample code is not enough to build.** It omits `build.rs`, the icon files and the
   `Ctrl+Shift+Q` exit. Same pattern here: the PRD's tree omits `build.rs`, `icons/`, `.gitignore`,
   `gen/android/`, a keystore and the Gradle/Manifest wiring the scanner needs.
3. **`cargo install tauri-cli` compiles the CLI from source**; the prebuilt npm package takes seconds.
   Used `npm install --global @tauri-apps/cli@2.12.4`.
4. **Rust cache keys may only be written by successful builds.** No `cache-on-failure`, `prefix-key`
   set, bump to invalidate.
5. **Pin the toolchain.** Rust 1.95.0 here (and in CI), JDK 17, pinned NDK.
6. **A copied template may not trigger a build on its first commit** (no repository-created event), so
   `workflow_dispatch` is the guaranteed first run and the README says so first.
7. **Verify the artifact, not the run colour.** The sister project proved its embedded config by
   extracting the binary and grepping it. That idea became the `Verify APK` step.
8. **Pages needs no workflow** and the site is one static file with at most a small inline script.
9. **YAGNI and one-line solutions are the house style**, and a config generator widget was
   deliberately *not* built for the sister's front door. A copyable agent prompt replaced it.

### Tauri v2 Android facts established (not guessed)

Established by running the real CLI on this machine, not from memory:

- `tauri android init --ci` refuses to run without Java (`Java not found in PATH ... JAVA_HOME`).
- The generated project is Gradle 9.6.1 + AGP 9.3.1 + Kotlin 2.2.10, `minSdk 24`,
  `compileSdk 37`, `targetSdk 37`, and `namespace = <identifier>`. The CLI hardcodes 37; it does not
  follow the installed platforms.
- The SDK platform package is **`platforms;android-37.0`**, not `platforms;android-37`. Asking for
  `platforms;android-37` produces `Warning: Failed to find package 'platforms;android-37'` and the
  build then fails on a missing `compileSdk`. This is the single easiest CI trap in this project.
- `bundle.targets: ["apk"]` is **rejected** by the 2.12 config schema:
  `error on bundle > targets: ["apk"] is not valid under any of the schemas`. The format comes from
  `tauri android build --apk`, so the key is omitted entirely.
- `WebviewWindowBuilder::fullscreen()` and `::decorations()` live in a `#[cfg(desktop)]` impl block.
  They do not compile for an Android target. `title()`, `inner_size()` and `resizable()` are
  cross-platform. Immersive mode therefore belongs in `MainActivity.kt`.
- `AppHandle::get_webview` does not exist; the accessor is `get_webview_window`.
- `WebviewUrl` is re-exported at the crate root (`tauri::WebviewUrl`), not `tauri::webview::WebviewUrl`
  (private there).
- `jni 0.21.1` is **already in Tauri's Android dependency tree**. Declaring it explicitly is a pin of
  a transitive dependency, not a new crate.
- Tauri's documented Kotlin-to-Rust mechanism is JNI (`System.loadLibrary("<lib name>")` plus
  `#[no_mangle] Java_<package>_<Class>_<method>`), because Kotlin's `Plugin.trigger()` only reaches
  JavaScript listeners, not Rust.
- The generated Android project is meant to be **committed** once it is customised. Its own
  `.gitignore` files already exclude `build/`, `.gradle/`, `local.properties`, `jniLibs/`,
  `assets/tauri.conf.json`, `tauri.properties`, `tauri.build.gradle.kts`, `tauri.settings.gradle`.

### PRD deviations, and why

| Brief says | Shipped | Why |
| --- | --- | --- |
| `bundle.targets: ["apk"]` | key omitted | Invalid in the Tauri 2.12 config schema; the schema rejects the whole config. |
| `scanner.intentAction: "com.symbol.datawedge.api.ACTION"` | `com.powerapps.handheld.SCAN` | That DataWedge action is the **command API**, not scan output. Filtering on it would catch unrelated broadcasts and usually carry no payload. Zebra documents an app-specific action for Intent output. Callers must set the same string in the profile; the README and front door both say so. |
| Child webview via `WindowBuilder` + `add_child`, sized from `current_monitor()` | One `WebviewWindowBuilder` | The child-webview API needs the `unstable` feature and the brief's sample panics when a monitor is not reported, which is exactly what a phone does. A single window is the whole product. |
| `interceptBackButton` / `keepScreenOn` only described | Wired through Gradle `buildConfigField` into `MainActivity.kt` | On Android these are activity flags, not Rust flags. The brief's schema is unchanged; the flags are still driven by `config.json`. |
| Unsigned release APK | Signed with a committed public dev key | An unsigned APK cannot be installed at all. A fixed key means every build installs over the previous one, on any machine. |
| `appName` described as the display name, no mechanism | `manifestPlaceholders["appLabel"]` from `config.json` | Otherwise the launcher label would come from `tauri.conf.json.productName` and a maker editing `appName` would see nothing change. |
| Sample injection escapes `\` and `'` by hand | Payload is JSON-encoded before interpolation | A barcode is untrusted input; `serde_json::to_string` cannot produce a script escape. |
| Kotlin hardcodes the extra key with fallbacks | `BuildConfig.SCANNER_INTENT_EXTRA_KEY` from `config.json`, then the same fallbacks | Keeps `scanner.intentExtraKey` authoritative while still tolerating OEM differences. |

### Decisions

- **JNI over a Tauri plugin.** A plugin crate would add a second Rust crate, a Kotlin library module,
  a permission set and a `build.gradle` for one function call. The documented JNI path is ~10 lines on
  each side and has no capability surface.
- **Static manifest receiver.** The action is substituted from `config.json` by Gradle, so no runtime
  registration and no `RECEIVER_EXPORTED` handling. It also survives when the activity is not in front.
- **`ui/index.html` stays as the packaged splash** but is never mounted, because the window opens on
  the remote URL. It only exists so `frontendDist` resolves.
- **Icons pruned** to `32x32.png`, `128x128.png`, `icon.ico`. `tauri icon` also writes iOS assets,
  Windows Store logos and an `icons/android/` duplicate of the mipmaps; all removed.
- **No Gradle cache in CI** to start with. A cold Gradle cache is a few minutes; a stale cache is a
  debugging session. Measure first.
