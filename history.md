# History

Newest first. Every number here was measured on this machine or in a named GitHub Actions run.

## 2026-10-26 — Native inputs v1: an opt-in permission gate, offline by default

**Status:** merged as #3 (`755e472`). The default config's APK declares `INTERNET` and nothing else; the
demo config's declares the six. Camera preview, microphone capture and the location prompt were observed
on the emulator. The location fix, file upload and photo capture were not: this system image has no GPS
provider, no DocumentsUI and only a TV camera stub. Those three stay unchecked in `todo.md`.

### What changed

- `src-tauri/src/config.rs`: `NativeConfig` (`camera`, `cameraScanner`, `microphone`, `location`), all
  `false`, `#[serde(default)]` on the field and `default` on the struct, plus a test that a `config.json`
  written before the block existed still parses with every input off.
- `src-tauri/gen/android/app/build.gradle.kts`: reads the same block and merges one overlay per enabled
  feature with `variant.sources.manifests.addStaticManifestFile`. `cameraScanner` maps to the camera
  overlay, so our own reader can hold `CAMERA` while `getUserMedia` stays closed to the page.
- `src-tauri/gen/android/app/src/features/{camera,microphone,location}/AndroidManifest.xml`: the
  permissions, plus `uses-feature … required="false"` for camera, microphone, location and GPS, because
  declaring `CAMERA` or a location permission otherwise filters out devices that lack the hardware.
- `src-tauri/src/lib.rs`: `native_permission` is the whole runtime policy — one match from
  `PermissionKind` to a config flag, `Default` when on, `Deny` plus
  `[NATIVE][DENIED] <kind> is not enabled in config.json` when off. Mounted once through
  `.on_permission_request` on the window builder. One `[CONFIG] Native …` startup line beside the kiosk
  and scanner lines. `INIT_SCRIPT` keeps `__LAST_FOCUS__`; `INJECTION_SCRIPT` falls back to it when
  `document.activeElement` is no longer a text field.
- `file_paths.xml`: `external-files-path` with `path="."`, which is exactly `getExternalFilesDir(null)`.
  wry writes a captured photo to `…/files/Pictures` under that root, so the provider can build its URI.
- `gradle.properties`: `org.gradle.configuration-cache=false`. Gradle was caching the overlay set, so a
  config edit rebuilt the APK with the permissions from the config before it.
- Maker surfaces: `wizard.html` device checkboxes written into the generated JSON, `demo.html` a
  capabilities card behind one button, the front door's config table and "Honest limits" wording,
  `README.md`'s permission table, the no-permission note for uploads and capture, and how to reset a
  denied prompt.
- `release.yml`: the existing **Verify APK** step now derives the expected permission list from
  `config.json` and fails the job on any difference.
- `agents.md`: rule 14, a new device permission goes through the block, the overlay folder and the plan.

### Runs and permission lists

| Run | Config | Job | Device permissions |
| --- | --- | --- | --- |
| `40398494767` branch `feat/native-inputs-v1` | demo profile | 6m34s, green | – |
| `40399624409` push `main` | shipped `config.json` | 7m55s, green | `INTERNET` only, expected and in APK |
| `40400135768` dispatch `config=demo` | demo profile | 5m39s, green | the six, expected and in APK |

The six are `INTERNET`, `CAMERA`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `ACCESS_COARSE_LOCATION`
and `ACCESS_FINE_LOCATION`. `demo-latest` re-published at **11,499,314 bytes**.

### On the emulator (demo APK, API 36, 1280x720)

| Check | Result |
| --- | --- |
| Boot | `[CONFIG] Native camera=true cameraScanner=false microphone=true location=true` |
| Camera on | system prompt, granted, live preview labelled `camera 0, facing back`, Stop released it |
| Microphone on | system prompt, granted, `open: microphone` and the level bar moving |
| Location on | system prompt for location, granted coarse and fine |
| Deny path | an all-off APK logged three `[NATIVE][DENIED]` lines and showed no system dialog |
| Focus memory | focus moved off the fields, then a broadcast scan landed in the last field anyway |
| File upload | "You don't have an app that can do this" — no DocumentsUI on this image |

### Findings

1. **The Gradle configuration cache silently freezes the permission set.** `build.gradle.kts` reads
   `config.json` at configuration time, which the cache cannot see, so switching a flag and rebuilding
   shipped the previous manifest. Turning the cache off costs a few seconds a build and removes a class
   of "the APK has the wrong permissions" reports that looks like a shell bug.
2. **`addStaticManifestFile` is still there in AGP 9.3.1**, which is why the permission overlays need no
   second module, no `src/<variant>` duplication and no product flavours. One folder per feature, one
   map entry in Gradle, and a feature that is off leaves no line in the merged manifest.
3. **wry's geolocation hook reads backwards.** `onGeolocationPermissionRequestNative` returns a boolean
   where `true` means *deny* and `false` means *let Android's normal flow continue*, including the
   runtime request. A handler that returned `false` for "denied" would grant exactly what the config
   turned off; the gate therefore returns `PermissionResponse::Deny`, never a raw boolean, and the
   emulator run showed the runtime prompt arriving only for the feature that was on.
4. **The capture fix is a path mapping, not code.** Without `external-files-path`, `getUriForFile` throws
   for `…/files/Pictures`, and wry catches that and shows a plain file picker instead of the camera — no
   error anywhere, which is why the bug read as "photo capture does nothing".
5. **The only installed system image is an Android TV build**, so `<input type=file>` has no picker app
   and `IMAGE_CAPTURE` resolves to `com.android.tv.frameworkpackagestubs.Stubs$CameraStub`. Blocked on
   the image, not on the shell.
6. **The same image registers no GPS provider.** `adb emu geo fix` is acknowledged, `dumpsys location`
   shows the app's own `Request[@0, HIGH_ACCURACY, WorkSource{10090 com.powerapps.handheld}]`, and no fix
   arrives; `cmd location providers add-test-provider gps` is refused with
   `SecurityException: android from uid 2000 not allowed to perform MOCK_LOCATION`. The request side of
   the location path is proven, the readout is not.
7. **An unanswered prompt reads as a refusal.** Chromium ends a `getCurrentPosition` call after about
   ten seconds, so the panel showed `refused: Timeout expired` for the first location attempt while the
   system dialog was still up. Read the logcat line and the permission state before treating that as a
   shell fault.
8. **A capture probe on the demo page was written, tried, and dropped.** With no camera app it could
   only prove the same toast, and the plan's card does not carry it; the demo now documents capture
   rather than pretending to test it.

### Structure this left behind

Each concern has one owner, and they meet only through `config.json`:

- `src-tauri/src/config.rs` — the schema and the defaults. The only place that names the four flags.
- `src-tauri/src/lib.rs` — runtime policy. `native_permission` is the single decision point, consumed
  once by the webview builder; the page-side fallbacks live in the two scripts next to it.
- `src-tauri/gen/android/app/build.gradle.kts` — build-time permissions. One map from feature name to
  enabled, one overlay path each; nothing else in Gradle knows about `native`.
- `src-tauri/gen/android/app/src/features/<feature>/AndroidManifest.xml` — the permissions and the
  `uses-feature` lines for that feature, and nothing else. Removing a feature is deleting one folder and
  one map entry.
- Pages — `wizard.html` writes the block, `demo.html` exercises the result, `index.html` and `README.md`
  explain it. None of them duplicate the permission list; they read the same flags.

## 2026-10-26 — Demo profile, a device test page, and a config builder

**Status:** the two verification releases and their tags are gone. The repository carries one
pre-release, `demo-latest`, whose APK opens the Pages demo page. That APK was installed on the emulator
and a broadcast scan filled the page's focused field, with the page's own panel confirming the value.

### What changed

- `demo.html`: a stand-in pick list served by Pages. Three inputs, a last-value panel, length/field/hook
  facts, and the `input` / `change` / `barcodeScanned` trail. **Simulate a scan** performs the same DOM
  write the shell performs: the `HTMLInputElement.prototype` setter, then `input`, `change`, and a
  `barcodeScanned` event dispatched on `window`.
- `config.demo.json`: the demo profile. It points at
  `https://spuds0588.github.io/PowerApps-Mobile-Handheld-Shell/demo.html` and allows only
  `spuds0588.github.io`.
- `wizard.html`: a four-step builder (app, device, domains, config) with per-step validation, the
  Microsoft sign-in hosts prefilled, a copy button and a `config.json` download. It writes the same six
  keys the template reads.
- The workflow takes a `workflow_dispatch` input `config` (`production` or `demo`) and its own
  concurrency group per profile. A demo run copies `config.demo.json` over `config.json` inside the
  runner, publishes `demo-latest` as a pre-release with the APK renamed `PowerApps-Handheld-Demo.apk`,
  and never stamps a version tag.
- The template repository skips numbered releases (`github.repository` check), because its own
  `config.json` is still the placeholder. A copy has a different repository name, so it releases
  normally.
- `demo.html` and `wizard.html` joined `paths-ignore`: the APK embeds a URL, not the page.

### Runs and artifact

| Run | What it proves | Job wall | Build step | Publish step |
| --- | --- | --- | --- | --- |
| `40382354353` push `292f388` | production build is green and the template publishes nothing | 485s | 442.0s | both skipped |
| `40382420224` dispatch `config=demo` | the demo profile builds, verifies and publishes | 390s | 353.0s | demo 4.0s |

The demo release is the only one on the repository. `PowerApps-Handheld-Demo.apk`, **11,527,282
bytes**, SHA-256 `ba6c25f799576a48ee6b7183fe4a87c4014be8886c3c9af5fd7f800097af6bc0`:
`versionCode='1000000' versionName='1.0.0'`, label `PowerApps Shell Demo`, both ARM ABIs,
`Verifies` under signature v2, and the demo URL and app name inside the stripped library.

### The demo APK on the emulator

| Check | Result |
| --- | --- |
| Install | `Success`; launcher label `PowerApps Shell Demo` |
| Boot | `[INIT] Starting PowerApps Mobile Handheld Shell: 'PowerApps Shell Demo'` |
| Navigation | `[NAVIGATION][ALLOWED] https://spuds0588.github.io/PowerApps-Mobile-Handheld-Shell/demo.html` |
| Scan | a broadcast with the configured action reached Kotlin and Rust, then `[SCANNER][SUCCESS]` |
| Page | `MAT-4417-B` landed in the focused Item field; the panel reported length 10, field `item`, hook `set`, and `input` / `change` / `barcodeScanned` at 14:11:01.889, .890 and .905 |
| Layout | Four facts on one row and the whole trail inside 1280x720, with no scroll |

### Findings

1. **A `CustomEvent` needs `bubbles: true` to reach a `window` listener from an input.** The shell
   dispatches on `window`, so the demo must do the same; dispatching on the input filled the field while
   the diagnostics panel stayed empty, which reads exactly like a broken injection path.
2. **Deleting a release with `--cleanup-tag` resets the version.** With both `v*` tags gone, the next
   stamp is `v1.0.0`, which is why the demo APK reports 1.0.0.
3. **`demo.html` and `wizard.html` do not belong in the build trigger.** A page edit now reaches Pages
   without spending nine minutes of runner time.
4. **The fact grid wrapped at 1280x720** (`auto-fit` chose three tracks), pushing the trail below the
   fold. Four fixed columns at 900px and up fixed it, and the redundant trail summary line was dropped.

## 2026-10-26 — Numbered releases, proven from a config-only push

**Status:** three green runs, two published releases. `v1.0.0` and `v1.0.1` each carry the APK, and
the `v1.0.1` APK was installed and exercised on the emulator with its ARM64 library. **Later:** both
releases were deleted, since they were built from the placeholder config; see the demo entry above.

### What changed

- Every green build on `main` stamps the next patch version from the newest `v*` tag into
  `tauri.conf.json` and `Cargo.toml`, builds, and publishes a `vX.Y.Z` release with the APK and a table
  of the `config.json` it was built from. A fresh copy has no tags, so its first build is `v1.0.0`.
- The APK's `versionName` is asserted against the version about to be tagged, so a release cannot
  describe a different binary. `versionCode` follows on its own: 1.0.0 → 1000000, 1.0.1 → 1000001.
- Docs-only pushes (`**.md`, `index.html`, `.nojekyll`, `LICENSE`) no longer build or release.
- The publish step runs last, needs `contents: write`, is limited to `refs/heads/main`, and skips a
  commit that already carries a `v*` tag, so re-running a job cannot cut a second release.
- The verify step no longer hardcodes the native library name. It asserts one `.so` per ARM ABI, which
  survives a rename of the crate.

### Runs

| Run | What it proves | Job wall | Build step | Release |
| --- | --- | --- | --- | --- |
| `40376311360` push `93fa453` | stamping and publishing | 401s | 361.0s | `v1.0.0` |
| `40377393835` push `8eb8877`, **config.json only** | the flow a maker runs | 343s | 296.0s | `v1.0.1` |
| `40377462192` dispatch on `test/maker-config` | a realistic maker config builds; a branch run publishes nothing | 456s | 426.0s | none, step skipped |

`Publish release` took 5.0s in both main runs and is skipped elsewhere. Nothing was called green from the
run list alone; both releases were inspected by hand:

- `v1.0.1` asset `app-universal-release.apk`, **11,527,518 bytes**, SHA-256
  `2d378a7a8e1878135e435dda64a299fc9de4f225f2843a4debe85449397d95e6`.
- `package: versionCode='1000001' versionName='1.0.1'`, label `PowerApps Handheld Shell`,
  `native-code: 'arm64-v8a' 'armeabi-v7a'`, `Verifies` under APK signature v2, both libraries packaged.
- The release body carries the new `powerAppUrl` (`your-environment-id` / `your-app-id`), and the
  workflow's own grep found that URL and the new allowlist hosts inside the stripped `.so`.
- The branch run's APK was labelled `Contoso Pick & Pack (Zone 4)` with its 14-host allowlist and custom
  scan action, through a release build with R8.

### The released APK on a real Android image

`gh release download v1.0.1`, uninstall the debug-signed build, install, launch. The x86_64 image ships
`libndk_translation.so` and reports `x86_64,arm64-v8a`, so the actual ARM artifact runs here.

| Check | Result |
| --- | --- |
| Install | `Success` |
| Boot | `[INIT] Starting PowerApps Mobile Handheld Shell`, then the `[CONFIG]` kiosk and scanner lines |
| Navigation | `[NAVIGATION][ALLOWED] https://apps.powerapps.com/play/e/your-environment-id/a/your-app-id?tenantId=your-tenant-id` |
| Real network | Microsoft answered the placeholder with `{"code":"EndpointInvalid","message":"Environment id your-environment-id is invalid."}`, drawn edge to edge with no browser chrome |
| Scan in a release build | `[SCANNER][RECEIVED]` in Kotlin and Rust, then `[SCANNER][SUCCESS]`: R8 kept the JNI class |
| Back | `[SECURITY][BLOCKED] Hardware Back suppressed.`, activity still `topResumedActivity` |
| Screen | `fl=KEEP_SCREEN_ON` on the window |

The DOM half of the scan path needs a page with a focused input, so it was taken again on a local debug
build. What the release APK adds is that the receiver, the JNI bridge and the injection dispatch survive
R8.

### The full scan path, again, with an awkward maker config

Debug x86_64 build, local page on `127.0.0.1:8766` over `adb reverse`, `appName` deliberately set to
`Pick & Pack (Zone 4)`:

| Check | Result |
| --- | --- |
| Label | `application-label:'Pick & Pack (Zone 4)'` — AGP escapes the placeholder, so `&` needs no workaround |
| Page load | `[LAYOUT][READY]`, `[NAVIGATION][ALLOWED]`, page beacon `loaded=1` |
| Allowlist | the page's own `location.href = http://example.com/` produced `[SECURITY][BLOCKED] Unauthorized navigation: http://example.com/` |
| Two scans | `[SCANNER][INJECTED] Filled focused control` for `PLT-0093-22` and `00012345678905`; the page logged `input`, `change` and the `barcodeScanned` event for both |
| No reload | `test.html` was fetched twice in total: once by a host `curl` check, once by the app. The scans added none. |

### Findings

1. **No maker-config build bug turned up.** Spaces, `&`, `(`, `)`, a long URL carrying `?` and `&`, and
a custom scan action all build in debug and release. The hardcoded library name in the verify step was
the one real fragility left, and it is gone.
2. **`fetch-depth: 0` is load-bearing.** Without tags the stamp step picks `1.0.0` every time and the
release then collides with the tag that already exists. The stamp block was run against a scratch repo
with no tags and with an old `v1.0.4` tag before it went near CI.
3. **The test server has to be a real background process.** A `nohup ... &` inside a synchronous shell
call dies when the call returns. The webview then shows `net::ERR_EMPTY_RESPONSE`, the page's JavaScript
never runs, and it looks exactly like a broken injection path. Start it as a background process and
check HTTP 200 before blaming the app.
4. **The emulator can run ARM64**, so the released artifact can be tested here instead of a locally
built ABI twin.
5. **`paths-ignore` is what keeps releases meaningful.** Otherwise a docs commit bumps the patch and
republishes a byte-identical APK.
6. **Microsoft's Power Apps auth hosts are four, not one.** The shipped default was missing
`login.microsoft.com` and `secure.aadcdn.microsoftonline-p.com`
([requirements](https://learn.microsoft.com/en-us/power-apps/limits-and-config)). The default now has all
four plus the known SSO and STS hosts. `config.json` now says environment id, not tenant id, for the
`/e/` segment. Power Apps also lists `*.azureedge.net`, `*.azurefd.net`, `graph.microsoft.com` and
`*.azure-apim.net`; those stay opt-in and are named in the README.
7. **The two-line `BuildTask.kt` fix is still required.** Nothing regenerated `gen/android` in these
runs, and the local build would fail at `:app:rustBuildX8664Debug` without it.

## 2026-10-26 — Front door sells dedicated devices, not one device class

**Status:** `index.html` hero rewritten, checked at 1280 px and 390 px, no console errors, live on
Pages.

- New H1: "Give one Power App its own Android device." The lede names a warehouse scanner, a forklift
tablet and the cheap handset a field tech carries, and keeps the scan-into-the-focused-field promise.
- Chips moved from vendor names to device classes (`Warehouse scanners`, `Forklift & counter tablets`,
`Low-cost handsets`, `Android 7+`). The vendor names already live in the demo section.
- Meta title, description and Open Graph copy follow the same pitch. The micro line and the build
section say the build publishes a numbered release, and the flow section sends makers to Releases
instead of Actions artifacts.
- The first lede measured 447 characters and read as a paragraph; it was trimmed to 351.
- README follows the same framing and documents the shipped allowlist against Microsoft's published
Power Apps host list.

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

1. **The Rust cache save never recovered, so the step was removed.**
   `Failed to save: Unable to reserve cache with key v1-build-apk-Linux-x64-f273623e-95e1c76a, another
   job may be creating this cache.` It first happened when a run was cancelled by `concurrency`
   mid-build, and it repeated on the next successful run (`40337375428`) with no competing job and an
   empty cache list — the reservation never released. Rather than keep a step that warns on every
   build to save a couple of minutes on a build a maker runs rarely, `Swatinem/rust-cache` was dropped.
   Cold end-to-end timings measured after that: **5m54s** and **8m11s** (build step 318.7s and 440.2s,
   SDK install 2.9s versus 15.7s) — runner variance, no cache either way. Budget about ten minutes.
2. **Action majors matter now.** `actions/checkout@v4`, `setup-node@v4`, `setup-java@v4` and
   `upload-artifact@v4` run on Node 20, which the runner forces onto Node 24 with a deprecation
   warning; `setup-java@v4` is itself deprecated. Bumped to `checkout@v7`, `setup-node@v7`,
   `setup-java@v6`, `upload-artifact@v7` — all four ran clean in the green job.
3. **Pushing a docs commit cancels the in-flight build** because of `concurrency: cancel-in-progress`.
   Expected, and worth knowing before a long build is thrown away mid-flight.
4. **`platforms;android-37.0` is correct and `platforms;android-37` is not.** Confirmed again in CI:
   the pinned install succeeded and the assertion on `platforms/android-37.0` passed.
5. **Jekyll breaks twice, so the site no longer uses it.** Excluding the PRD with `_config.yml` fixed
   the first failure, then `history.md` — which quotes `{{ try {{ const val` in a code block — failed
   the very next Pages build the same way. GitHub Pages renders Liquid before it looks at code fences.
   The site is one static file with no plugins, so `_config.yml` was removed and an empty `.nojekyll`
   added: Pages now publishes the branch verbatim, and the footer links to `agents.md`, `history.md`
   and `todo.md` on GitHub, where they are rendered properly. Rebuilding a markdown page with Jekyll
   was never part of the product.

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
