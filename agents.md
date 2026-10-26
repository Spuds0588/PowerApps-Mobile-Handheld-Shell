# Agent Guide — PowerApps Mobile Handheld Shell

## Product contract

A **Tauri v2 Android shell** that gives one Microsoft Power App its own Android device - a warehouse
scanner, a forklift tablet or a low-cost handset - and does exactly this:

- Embed the root `config.json` at compile time with `include_str!`; invalid or incomplete config must
  fail the build, not the runtime.
- Boot straight into `powerAppUrl` in a single webview. No app picker, no address bar, no splash
  screen to dismiss.
- Receive the OEM scan engine's broadcast intent, then write the payload into the **focused** DOM
  control through the native value setter plus `input` and `change` events. Never navigate, never
  reload, never pass the value in a query parameter.
- Keep the payload reachable as `window.__LAST_BARCODE__` and as a `barcodeScanned` window event.
- Check every navigation against `allowedDomains` (apex plus subdomains) and log
  `[SECURITY][BLOCKED]` on rejection.
- Honour `keepScreenOn` and `interceptBackButton`, and keep the system bars hidden.
- Do nothing else: no camera scanning, no database, no sync, no MDM, no second window, no updater,
  no telemetry, no IPC surface.

The brief has been absorbed into this contract, `src-tauri/src/config.rs` and `history.md`. If code
and this contract disagree, fix the code or record the decision in `history.md`.

## Stack and layout

| Path | Purpose |
| --- | --- |
| `config.json` | The only file a maker edits. Tracked: CI and the local build embed it. |
| `src-tauri/src/config.rs` | The config schema, shared by `build.rs` and the runtime so they cannot drift. |
| `src-tauri/build.rs` | Validates `config.json` (JSON, URL, scanner keys) before `tauri_build::build()`. |
| `src-tauri/src/lib.rs` | Window, allowlist, boot logging, JNI scanner entry point, DOM injection. |
| `src-tauri/src/main.rs` | Desktop entry point for `cargo`/`tauri dev` on a workstation. |
| `src-tauri/tauri.conf.json` | Product name, identifier `com.powerapps.handheld`, `frontendDist: ../ui`. |
| `src-tauri/icons/` | `32x32`, `128x128` and `icon.ico` only. Pruned from the full `tauri icon` set on purpose. |
| `src-tauri/gen/android/` | **Committed** Android Studio project. Carries the receiver, the kiosk activity, the Gradle config and the dev key. |
| `src-tauri/gen/android/app/src/main/java/com/powerapps/handheld/BarcodeReceiver.kt` | Manifest receiver, extra-key fallbacks, JNI call into Rust. |
| `src-tauri/gen/android/app/src/main/java/com/powerapps/handheld/MainActivity.kt` | Immersive bars, Back shield, keep-screen-on. |
| `src-tauri/gen/android/app/build.gradle.kts` | Reads `config.json` into manifest placeholders and `BuildConfig`; dev signing. |
| `src-tauri/gen/android/app/dev-release.keystore` | Public development key. Committed on purpose. |
| `ui/index.html` | Packaged splash asset. Not mounted, because the window opens on the remote URL. |
| `index.html` | Published front door (GitHub Pages, legacy `main` / root source). |
| `wizard.html` | Config builder served by Pages. Validates in the tab and emits `config.json`; no build step. |
| `demo.html` | Stand-in Power App served by Pages. Three inputs plus the scanner passthrough panel. |
| `config.demo.json` | Demo profile. Points the shell at `demo.html`; copied over `config.json` only in a demo build. |
| `.github/workflows/release.yml` | Android APK build, artifact verification, version stamping, a numbered GitHub release for copies, and the rolling `demo-latest` release for the demo profile. |

## Commands

Maintainers only. The maker never installs any of this.

```bash
# Toolchain for a local Android build (adjust to your machine)
export JAVA_HOME="$HOME/android-dev/jdk-17"
export ANDROID_HOME="$HOME/android-dev/sdk"
export NDK_HOME="$ANDROID_HOME/ndk/27.3.13750724"

cargo fmt --manifest-path src-tauri/Cargo.toml --check
cargo check --manifest-path src-tauri/Cargo.toml --target aarch64-linux-android --lib
python3 -m json.tool config.json
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))"
tauri android build --apk --target aarch64 --ci                     # one ABI, fast feedback
tauri android build --apk --target aarch64 --target armv7 --ci      # what CI builds
adb install -r src-tauri/gen/android/app/build/outputs/apk/universal/release/*.apk
adb shell am broadcast -a com.powerapps.handheld.SCAN \
  --es com.symbol.datawedge.data_string "TEST-123"                  # fake a hardware scan
python3 -m http.server 8899                                         # index.html, wizard.html, demo.html
gh workflow run release.yml && gh run watch
gh workflow run release.yml -f config=demo                          # rebuild the demo APK in place
gh release view demo-latest
gh run download <run-id> -n PowerApps-Handheld-Release-APK
gh release list                                                      # v1.0.0, v1.0.1, ...
gh release download v1.0.1                                           # what a maker actually gets
gh workflow run release.yml --ref <branch>                           # build a maker config, publish nothing
```

## Rules

1. **YAGNI.** No settings UI, no IPC commands, no plugins, no database, no updater, no camera, no
   analytics. The shell frames one page and routes scans into it.
2. **One-line solutions.** Prefer the standard library or an idiomatic one-liner. Add a helper only
   when it removes a real duplicate or encodes a rule the code cannot show.
3. **Dependencies stay tiny.** `tauri`, `serde`, `serde_json`, `url`, plus `jni 0.21` for Android
   only, which Tauri already links. `tauri-build` is the only build dependency besides `serde`,
   `serde_json` and `url` for the config check. Adding anything else needs a recorded reason.
4. **Config stays embedded.** `config.json` is read by `include_str!`, `build.rs` and Gradle. Never
   add a runtime filesystem or `assets/` read, and never ship a loose config file.
5. **Never navigate to deliver a scan.** No reloads, no query parameters, no
   `history.replaceState` bridge. The only write path is the focused-control injection script.
6. **Fail closed.** A non-empty `allowedDomains` blocks every host outside the list and its
   subdomains. An empty list means allow-all; document it, do not silently change it.
7. **Logging tags are stable.** `[INIT]`, `[CONFIG]`, `[NAVIGATION]`, `[SECURITY]`, `[SCANNER]`,
   `[LAYOUT]`, `[FATAL]`. Kotlin uses its own `POWERAPPS_*` logcat tags.
8. **Commit `src-tauri/gen/android/`.** It holds the receiver, the kiosk activity and the signing
   config. Never delete it, never regenerate it in CI, and never hand-edit the files Tauri rewrites
   per build (`tauri.properties`, `tauri.build.gradle.kts`, `tauri.settings.gradle`, `jniLibs/`,
   `assets/tauri.conf.json`) - they are git-ignored.
9. **The dev key is public by design.** `dev-release.keystore` (alias and passwords `powerapps`)
   exists so every build installs over the last one. These APKs never go to a store. Never reuse this
   key for anything that leaves the fleet.
10. **Android only.** No desktop bundle target, no iOS, no macOS. `src-tauri/gen/schemas/` and all
    build output stay git-ignored. Android is called Android in user-facing text.
11. **Pages stays static.** `index.html`, `wizard.html` and `demo.html` are plain HTML, CSS and inline
    script, with no external font, image, script or build step. Do not add a Pages deploy workflow:
    Pages is already enabled on legacy `main` / root.
12. **Full files.** When generating or replacing code, write complete runnable files.
13. **Releases are the delivery.** A green `main` build stamps the next patch version from the newest
    `v*` tag into `tauri.conf.json` and `Cargo.toml`, asserts the APK carries that version, and
    publishes the APK on a `vX.Y.Z` release. The tag is the version record: the stamp is never
    committed back, because that push would restart the job. Docs-only pushes (`**.md`, `index.html`,
    `.nojekyll`, `LICENSE`, and the two Pages pages `wizard.html` and `demo.html`) do not build or
    release. A run from any other ref builds and uploads but publishes nothing, which is how a maker
    tests a config without cutting a version.
14. **The demo is a profile, not a branch.** `config.demo.json` is copied over `config.json` inside the
    runner when the workflow is dispatched with `config=demo`; the APK goes to the rolling
    `demo-latest` pre-release and never gets a version tag. The template repository itself publishes no
    numbered releases, because its `config.json` is still the placeholder; a copy has a different
    repository name and releases normally. Keep the demo URL pointed at this repository's Pages.

## CI discipline (learned the hard way)

The user's standing complaint about these repos is builds that fail silently or pass without
producing a working artifact. Therefore:

1. **Never call a run green from the run list alone.** Read the failing step's log, and after a
   successful run download the artifact and inspect it.
2. **The workflow verifies its own APK** before uploading: `apksigner verify`, `aapt2 dump badging`,
   presence of both `lib/*/libpowerapps_mobile_handheld_shell.so`, the `BarcodeReceiver` action in the
   merged manifest, and `"powerAppUrl"` inside the stripped native library. Keep those checks; they
   are what stops a broken APK from shipping as a green run.
3. **Pin the moving parts.** JDK 17, Rust 1.95.0, `@tauri-apps/cli` 2.12.4, NDK 27.3.13750724,
   `platforms;android-37.0`, `build-tools;37.0.0`. The SDK platform package is `android-37.0` (with
   the `.0`); `platforms;android-37` does not exist and `sdkmanager` silently finds nothing.
4. **`NDK_HOME` must point at the versioned NDK directory**, not `$ANDROID_HOME/ndk`. Assert with
   `test -d` in the workflow.
5. **The demo dispatch has its own concurrency group.** `release-<ref>-demo` must not cancel a
   production build for the same commit, and neither may cancel the other's release.
6. **There is no dependency cache, deliberately.** A cold APK build is five to eight minutes and a maker
   builds rarely. If you add one, only successful builds may write its key: a cancelled run held a
   `Swatinem/rust-cache` reservation that then refused every later save.
7. **A copied template may not run on its first commit.** There is no repository-created event, so
   `workflow_dispatch` is the guaranteed first run. Say so first in the README.
8. **Record the numbers.** Job wall time, per-step time, artifact size, and every failure and fix go
   into `history.md`. Do not paraphrase a guess as a measurement, and never derive a step time by
   subtracting from the job wall — read it from the log.
9. **Checkout stays `fetch-depth: 0`.** The next version comes from `git tag`, and generated release
   notes need the previous tag's commit. A shallow clone silently republishes `v1.0.0` and then fails
   on the tag that already exists.
10. **A re-run must not cut a second release.** The publish step skips when the checked-out commit
   already carries a `v*` tag, and it runs last, so a failed verify never leaves a release behind.
   It also needs `contents: write`; the rest of the job needs nothing.

## Verification gates

| Change | Must run |
| --- | --- |
| Any Rust | `cargo fmt --check`, `cargo check --target aarch64-linux-android --lib` |
| `config.json` | `python3 -m json.tool`; a real build must fail on invalid JSON or a bad URL |
| `app/build.gradle.kts`, manifest, Kotlin | A real `tauri android build --apk`, then inspect the APK |
| Scanner or injection path | `adb shell am broadcast` into a booted emulator or device; confirm the focused field changed |
| Workflow | YAML parse, then a dispatched run; read the logs, download the artifact, inspect it |
| Release or version stamping | Run the extracted stamp block against a scratch repo with no tags and with an old tag, then a branch dispatch and a `main` push; inspect the release page, asset size and `versionName` |
| `index.html` | Serve locally, exercise the trigger button, check 1280 px and 390 px, no console errors, then check the live Pages URL |
| `wizard.html` | Serve locally, walk all four steps, fail validation on purpose, parse the generated JSON, check 1280 px and 390 px |
| `demo.html` | Serve locally, scan with **Simulate a scan**, confirm value, field and event trail update, then repeat in the demo APK |
| `gen/android` regeneration | Diff against git; the receiver, `MainActivity.kt`, signing config and `rootDirRel` must survive |

The emulator is a convenience, not the acceptance test. A physical Zebra, Honeywell or Datalogic
device is still required for: real DataWedge broadcast behaviour, trigger latency, memory footprint,
and screen-on during a shift.

## Cleanup after every task

This machine is a limited-memory development environment. Leaving build output around is a bug.

- `rm -rf src-tauri/target/` after local compile or build checks. It is hundreds of megabytes per
  Android target and it is git-ignored.
- Remove `src-tauri/gen/android/{.gradle,build,app/build}` and `src-tauri/gen/android/local.properties`
  when the build is finished. Keep the committed project files.
- Delete downloaded CI artifacts, extracted APKs, screenshots and logs from the workspace. Keep
  `index.html`, `ui/index.html`, `src-tauri/icons/` and the committed Android project.
- Never commit `~/.gradle`, the NDK/SDK, or anything under `src-tauri/gen/android/app/src/main/jniLibs`.
- Finish with `git status` (only intended files) and `du -sh .` (workspace should stay in the low
  megabytes).
