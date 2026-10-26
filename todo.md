# TODO

Working checklist. An item is checked only when it is implemented **and** observed here, in the shape
recorded in `history.md`. Anything that needs rugged hardware stays unchecked on purpose.

## Phase 1 — Repository shape

- [x] Read the brief end to end and survey the repo (it had only the PRD, README and LICENSE).
- [x] Read the sister `Powerapp-Frontline-Workspace-Shell` for its contract, CI lessons and front door.
- [x] Create `agents.md`, `todo.md`, `history.md`.
- [x] Add root `config.json` with the documented schema; keep it tracked so CI can embed it.
- [x] Add `.gitignore` (`src-tauri/target/`, generated schemas, logs). The Android project is committed.
- [x] Add `ui/index.html` splash asset.
- [x] Absorb the brief into `agents.md`. The original PRD file stays in the repo as the source record.

## Phase 2 — Tauri v2 shell

- [x] `src-tauri/Cargo.toml`: `tauri`, `serde`, `serde_json`, `url`, Android-only `jni 0.21`, `tauri-build`.
- [x] `src-tauri/build.rs` + shared `src-tauri/src/config.rs`: invalid JSON, a malformed URL or an empty
      scanner key fails the build.
- [x] `src-tauri/tauri.conf.json`: `PowerAppsMobile`, `com.powerapps.handheld`, `frontendDist: ../ui`.
      `bundle.targets` removed because `["apk"]` is rejected by the 2.12 schema.
- [x] Committed icons (`32x32.png`, `128x128.png`, `icon.ico`) plus generated Android mipmaps.
- [x] `src-tauri/src/lib.rs`: `include_str!` config, single webview on the remote URL, per-navigation
      allowlist with `[SECURITY][BLOCKED]`, boot logging, injection script, JNI entry point.
- [x] `cargo fmt --check` and `cargo check --target aarch64-linux-android --lib` are clean.

## Phase 3 — Android project (committed)

- [x] `tauri android init --ci` generated `src-tauri/gen/android/` and it is committed.
- [x] `app/build.gradle.kts` reads `config.json`: app label, scanner intent action and extra key,
      `keepScreenOn`, `interceptBackButton`.
- [x] Dev signing: committed `dev-release.keystore` (alias/pass `powerapps`) wired to the release type.
- [x] `BarcodeReceiver.kt`: manifest receiver, configured extra key plus OEM fallbacks, JNI call.
- [x] `MainActivity.kt`: immersive bars, Back shield, keep-screen-on — each driven by `BuildConfig`.
- [x] ProGuard keep rule for the JNI class and method name.
- [x] Fix the generated `BuildTask.kt` so Gradle can find the Tauri CLI (`tauri`, not `node tauri`).

## Phase 4 — Local verification

- [x] Release APK builds: 7,584,815 bytes, signed, both ABI flavours wired.
- [x] APK inspected: signature, badging, merged manifest receiver, `classes.dex`, native library,
      embedded `powerAppUrl`, JNI names retained.
- [x] Emulator playtest: boot, page load, allowlist block, Back shield, `KEEP_SCREEN_ON`,
      scan broadcast → JNI → Rust → focused input, no reload.

## Phase 5 — GitHub Actions

- [x] `release.yml`: push to `main` + dispatch, JDK 17, pinned SDK platform/build-tools/NDK, pinned
      Rust and Tauri CLI. No dependency cache: a stale reservation made it unreliable, and a cold build
      is five to eight minutes.
- [x] A `Verify APK` step that fails the job on a bad signature or a missing library, receiver, or
      embedded config.
- [x] First real run green (`40336020894`, commit `924bc3f`), logs read, artifact downloaded and
      inspected: 11,527,470 bytes, signed with the committed key, both ARM libraries, receiver present,
      config compiled in.
- [x] Cold timings recorded in `history.md`: 5m54s job, 318.7s build step.
- [x] Two CI failures found and fixed rather than worked around: `setup-android@v3` running the removed
      `sdkmanager tools`, and Jekyll failing the Pages build on Liquid in the PRD.
- [x] Numbered releases: patch bump from the newest `v*` tag stamped into `tauri.conf.json` and
      `Cargo.toml`, `versionName` asserted against the tag, APK attached to a `vX.Y.Z` release.
      `v1.0.0` and `v1.0.1` published, 11,527,470 and 11,527,518 bytes.
- [x] Config-only push proven end to end: `8eb8877` changed nothing but `config.json` and produced
      `v1.0.1` with the new URL and both added sign-in hosts inside the APK.
- [x] Branch dispatch proven: a realistic maker config (14 hosts, `Contoso Pick & Pack (Zone 4)`, a
      custom scan action) built green and published nothing.
- [x] Docs-only pushes are ignored by the workflow, so a README commit cannot cut a release.
- [x] Verify step hardened: one `.so` per ARM ABI instead of a hardcoded library name.
- [x] The released `v1.0.1` APK installed on the emulator, booted to the configured URL, reached
      Microsoft over the network, and its scan broadcast survived R8 (`[SCANNER][SUCCESS]`).

## Phase 6 — Front door and README

- [x] Root `index.html`, single file, no dependencies, sister-project palette and section pattern.
- [x] Hero rewritten around dedicated devices: a warehouse scanner, a forklift tablet and a low-cost
      handset, with the scanner promise kept. Checked at 1280 px and 390 px, live on Pages.
- [x] Interactive handheld demo that performs the same DOM write the APK performs.
- [x] Config reference, scan-engine setup steps, honest limits, copyable agent prompt.
- [x] `README.md`: quickstart, config reference, scan path, artifact locations, verify commands.
- [x] Pages rebuilt and the live URL checked.

## Phase 7 — Demo profile and config builder

- [ ] `demo.html`: stand-in Power App with three inputs, a last-value panel, field and length facts, the
      `input` / `change` / `barcodeScanned` trail, and a simulate button for desktop visitors.
- [ ] `wizard.html`: four steps, per-step validation, prefilled Microsoft sign-in hosts, copy and
      download, checked at 1280 px and 390 px.
- [ ] `config.demo.json` plus a `config=demo` dispatch that publishes `demo-latest` as a pre-release and
      never creates a version tag.
- [ ] Demo APK installed on the emulator, demo page loaded over Pages, a broadcast scan landing in the
      focused field with the diagnostics panel confirming the string.
- [ ] Test releases `v1.0.0` and `v1.0.1` and their tags removed, demo release in their place.

## Phase 8 — Needs a physical handheld (cannot be done on this host)

- [ ] Sideload the release APK on a Zebra TC21/TC52 or Honeywell CT40/CT60 and confirm it boots.
- [ ] Configure DataWedge Intent output (action, broadcast delivery, no category) and confirm a real
      trigger pull reaches `BarcodeReceiver` **without** the `am` background flag.
- [ ] Confirm the value lands in a real Power Apps text input and that Power Apps does not clear it.
- [ ] Measure scan-to-UI latency (target sub-50 ms) and RSS (target under 65 MB).
- [ ] Confirm the system bars stay hidden and Back stays suppressed on a phone-style navigation mode.
- [ ] Confirm keep-screen-on through a full shift with the screen timeout set low.
- [ ] Sign in with a real Entra ID tenant and add any extra domains it needs to `allowedDomains`.

## Known gaps

- Every green `main` build cuts a release immediately; there is no draft or approval step, and the
  version is stamped in CI rather than committed back to the repository.
- The demo APK points at this repository's Pages demo page, not at a copy's. A copy that dispatches a
  demo build gets an APK for the upstream demo until it edits `config.demo.json`.
- The demo release is a rolling pre-release with no version history; only the newest demo APK is kept.

- The front door's device is a CSS illustration, not a product screenshot, and the demo is a
  simulation of the DOM write rather than a live Power Apps control.
- `allowedDomains` ships with Microsoft's sign-in hosts plus the Power Apps hosts. A tenant that
  redirects somewhere else (a custom domain, a CDN) must add it, or the page will stall and log
  `[SECURITY][BLOCKED]`.
- The committed dev key is public by design. It cannot be used for any store listing and should not be
  reused outside the fleet.
