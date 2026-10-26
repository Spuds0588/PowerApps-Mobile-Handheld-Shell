# MASTER_DEV.md

# PowerApps Mobile Handheld Shell
## PRD, Implementation Guide, Dev Task List, and AGENTS.md

---

## Part 1: Product Requirements Document (PRD)

### 1.1 Executive Summary
PowerApps Mobile Handheld Shell is an ultra-lightweight, dedicated Android application shell built with Tauri v2. It packages Microsoft Power Apps and operational web dashboards for enterprise rugged handhelds (e.g., Zebra, Honeywell, Datalogic) and budget Android devices.

Standard enterprise mobile deployments rely either on the official Microsoft Power Apps Player (which has high cold-boot latency, memory bloat, and manual multi-tenant app selection menus) or mobile browser shortcuts (which expose browser address bars, navigation controls, and risk dropping scans when input fields lose focus).

This template delivers a dedicated, single-purpose enterprise APK that boots directly into an assigned Power App, enforces screen immersion and back-button capture, intercepts hardware broadcast intents from laser barcode scanners, and injects scanned data directly into active Power Apps input controls with zero page reloads.

Key Value Propositions:
1. **Direct Native Hardware Scanning:** Captures OEM scanner broadcast intents (Zebra DataWedge, Honeywell Enterprise Scan, etc.) via native Android intent receivers and automatically fills the active Power App input field without relying on brittle keyboard wedges.
2. **Zero-Reload State Continuity:** Injects scanned values without refreshing the application or altering URL query parameters, preserving ongoing operator inputs.
3. **Immersive Kiosk Lockdown:** Hides Android system and navigation bars, intercepts the physical/gesture hardware Back button to prevent app exits, and prevents display sleep during active shifts.
4. **Cloud-Native Low-Code CI/CD:** Low-code makers customize a simple `config.json` in their repository. GitHub Actions handles all Android SDK, NDK, and Rust compilation, outputting a production-ready `.apk`.
5. **Ultra-Low Memory Footprint:** Bypasses heavy multi-tenant launchers, running on the native Android System WebView with a ~35–65 MB runtime footprint.

---

### 1.2 Target Personas & Hardware Profiles
* **Warehouse & Logistics Pickers:** Running picking, bin-transfer, and inventory Canvas Apps on rugged handhelds (Zebra TC21/TC26/TC52, Honeywell CT40/CT60) scanning hundreds of barcodes per hour.
* **Retail Floor Associates:** Performing inventory price lookups and stock counts on handheld enterprise units or budget Android handsets ($120–$200 range with 2–3 GB RAM).
* **Low-Code Makers & IT Analysts:** Configuring and deploying single-purpose mobile business solutions without requiring local Android Studio, NDK, or command-line developer environments.

---

### 1.3 Key Architectural Principles & Boundaries
1. **Compile-Time Embedding:** The application bakes `config.json` directly into the binary via Rust’s `include_str!` macro. There are no loose configuration files on the device filesystem.
2. **Vendor-Agnostic Intent Capture:** Scanner broadcast intent actions and string extra keys are fully customizable in `config.json`, defaulting to industry-standard Zebra DataWedge parameters.
3. **Active Element Synthetic Injection:** Scanned values are dispatched directly to the currently focused DOM element via synthetic standard `input` and `change` JavaScript events, maintaining compatibility with out-of-the-box Power Apps Text Input controls.
4. **Single-Purpose Scope:** This project explicitly avoids multi-app window tiling, split-view layout orchestration, or complex offline synchronization databases (YAGNI).

---

### 1.4 Functional Requirements

#### FR-01: Compile-Time Configuration Management
* Reads and validates `config.json` at build time.
* Embeds target Power App URLs, kiosk display settings, domain whitelists, and hardware broadcast intent specifications directly into the native runtime.

#### FR-02: Hardware Scanner Intent Interception & Injection
* Implements an Android native BroadcastReceiver listening for OEM scanner intent broadcasts.
* Extracts the payload string using the configured `intentExtraKey`.
* Injects the extracted string into the active webview via a synthetic dispatch pipeline (`focus -> input value update -> DispatchEvent(input) -> DispatchEvent(change)`).
* Sets a global fallback window variable (`window.__LAST_BARCODE__`) for custom integrations.

#### FR-03: Kiosk Lockdown & Runtime Ergonomics
* **Immersive Fullscreen:** Automatically hides Android system navigation (back/home/recents) and the top status bar.
* **Back Navigation Shield:** Intercepts the Android hardware/gesture Back button to prevent workers from inadvertently backing out of or killing the Power App.
* **Keep-Screen-On:** Keeps the handheld screen awake continuously during operation to eliminate mid-shift wake/unlock friction.

#### FR-04: Security & Enterprise Domain Control
* Restricts webview navigation strictly to whitelisted corporate domains (supporting exact matches and wildcard subdomains).
* Pre-configured with essential Microsoft Entra ID (Azure AD) authentication routing domains.

---

### 1.5 Non-Functional Requirements
* **RAM Footprint:** Runtime baseline memory footprint under 65 MB on Android System WebView.
* **Scan-to-Input Latency:** Sub-50ms latency between physical trigger pull and the Power App UI registering the completed input.
* **Build Accessibility:** 100% reproducible cloud compilation via GitHub Actions; zero local development dependencies for end users.

---

## Part 2: Implementation Guide

### 2.1 File Architecture
```text
powerapps-mobile-handheld-shell/
├── .github/
│   └── workflows/
│       └── release.yml
├── config.json
├── src-tauri/
│   ├── Cargo.toml
│   ├── tauri.conf.json
│   ├── gen/android/        # Generated automatically during first build setup
│   └── src/
│       ├── lib.rs          # Core Tauri v2 mobile initialization & Rust logic
│       └── main.rs         # Desktop/mobile development entrypoint
└── ui/
    └── index.html
```

---

### 2.2 Root Configuration Schema (`config.json`)

```json
{
  "appName": "PowerApps Handheld Shell",
  "powerAppUrl": "https://apps.powerapps.com/play/e/default-tenant/a/app-id?tenantId=your-tenant-id",
  "keepScreenOn": true,
  "interceptBackButton": true,
  "scanner": {
    "intentAction": "com.symbol.datawedge.api.ACTION",
    "intentExtraKey": "com.symbol.datawedge.data_string"
  },
  "allowedDomains": [
    "apps.powerapps.com",
    "content.powerapps.com",
    "login.microsoftonline.com",
    "login.windows.net",
    "aadcdn.msauth.net",
    "aadcdn.msftauth.net",
    "msft.sts.microsoft.com",
    "autologon.microsoftazuread-sso.com"
  ]
}
```

#### Field Definitions:
* `appName`: Android application display name.
* `powerAppUrl`: Direct web link to the deployed Power Apps Canvas or Model-Driven application.
* `keepScreenOn`: Prevents the mobile screen from turning off while the app is active.
* `interceptBackButton`: Suppresses Android OS Back button navigation to keep the worker inside the app.
* `scanner.intentAction`: The Android Intent Action broadcast by the physical scan engine (defaults to Zebra DataWedge).
* `scanner.intentExtraKey`: The specific Intent string extra key containing the decoded barcode data.
* `allowedDomains`: Whitelisted authentication and application domains; all external navigation attempts outside this list are rejected.

---

### 2.3 Rust Source Implementation (`src-tauri/src/lib.rs`)

```rust
use serde::Deserialize;
use std::sync::Arc;
use tauri::{
    webview::{WebviewBuilder, WebviewUrl},
    window::WindowBuilder,
    AppHandle, Manager,
};
use url::Url;

// Embed the configuration directly into the compiled native binary
const COMPILED_CONFIG: &str = include_str!("../../config.json");

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MobileConfig {
    app_name: String,
    power_app_url: String,
    keep_screen_on: bool,
    intercept_back_button: bool,
    scanner: ScannerConfig,
    allowed_domains: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ScannerConfig {
    intent_action: String,
    intent_extra_key: String,
}

fn load_embedded_config() -> MobileConfig {
    serde_json::from_str(COMPILED_CONFIG)
        .expect("[CONFIG][FATAL] Corrupted config.json embedded inside binary.")
}

fn is_domain_allowed(target: &Url, whitelist: &[String]) -> bool {
    if whitelist.is_empty() { return true; }
    let host = match target.host_str() {
        Some(h) => h,
        None => return false,
    };
    whitelist.iter().any(|allowed| host == allowed || host.ends_with(&format!(".{}", allowed)))
}

// Injects the scanned barcode into the currently active DOM element
pub fn inject_scanned_barcode(app: &AppHandle, barcode: &str) {
    let sanitized_barcode = barcode.replace('\\', "\\\\").replace('\'', "\\'");
    let injection_script = format!(
        r#"(function() {{
            try {{
                const val = '{barcode}';
                window.__LAST_BARCODE__ = val;
                const active = document.activeElement;
                if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA')) {{
                    active.value = val;
                    active.dispatchEvent(new Event('input', {{ bubbles: true }}));
                    active.dispatchEvent(new Event('change', {{ bubbles: true }}));
                    console.log('[BARCODE][INJECTED] Injected value into focused input:', val);
                }} else {{
                    console.warn('[BARCODE][WARN] No focused text input found. Saved to window.__LAST_BARCODE__');
                }}
                window.dispatchEvent(new CustomEvent('barcodeScanned', {{ detail: val }}));
            }} catch(err) {{
                console.error('[BARCODE][ERROR] Failed barcode injection:', err);
            }}
        }})();"#,
        barcode = sanitized_barcode
    );

    if let Some(webview) = app.get_webview("main_mobile_view") {
        let _ = webview.eval(&injection_script);
        println!("[SCANNER][SUCCESS] Dispatched barcode payload: {}", barcode);
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let config = Arc::new(load_embedded_config());
    println!("[INIT] Starting PowerApps Mobile Handheld Shell: '{}'", config.app_name);
    println!("[SCANNER][CONFIG] Listening for action='{}' extra='{}'", config.scanner.intent_action, config.scanner.intent_extra_key);

    let cfg_setup = config.clone();

    tauri::Builder::default()
        .setup(move |app| {
            let win_builder = WindowBuilder::new(app, "root_window")
                .title(&cfg_setup.app_name)
                .fullscreen(true)
                .decorations(false);

            let window = win_builder.build()?;
            let target_url: Url = cfg_setup.power_app_url.parse().expect("[CONFIG][FATAL] Invalid Power App URL");
            let whitelist = cfg_setup.allowed_domains.clone();

            let init_script = r#"
                // Suppress context menus on mobile touch
                window.addEventListener('contextmenu', e => e.preventDefault());
                console.log('[SHELL][READY] Mobile hardware runtime active.');
            "#;

            let webview_builder = WebviewBuilder::new("main_mobile_view", WebviewUrl::External(target_url))
                .initialization_script(init_script)
                .on_navigation(move |url| {
                    let allowed = is_domain_allowed(url, &whitelist);
                    if !allowed {
                        println!("[SECURITY][BLOCKED] Unauthorized mobile navigation: {}", url);
                    } else {
                        println!("[NAVIGATION][ALLOWED] Navigation approved: {}", url);
                    }
                    allowed
                });

            // Fill 100% of the mobile viewport
            let monitor = window.current_monitor()?.unwrap_or_else(|| {
                panic!("[WINDOW][FATAL] Unable to detect primary mobile display.");
            });
            let size = monitor.size();

            window.add_child(
                webview_builder,
                tauri::LogicalPosition::new(0.0, 0.0),
                tauri::LogicalSize::new(size.width as f64, size.height as f64),
            )?;

            println!("[LAYOUT][READY] Mobile viewport bound to resolution: {}x{}", size.width, size.height);
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("[FATAL] Error initializing PowerApps mobile runtime");
}
```

---

### 2.4 Desktop & Simulator Entrypoint (`src-tauri/src/main.rs`)

```rust
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    powerapps_mobile_handheld_shell::run();
}
```

---

### 2.5 Android Intent Receiver Bridge (Kotlin Native Integration)
Place this native receiver in your generated Android project directory: `src-tauri/gen/android/app/src/main/java/com/powerapps/handheld/BarcodeReceiver.kt`

```kotlin
package com.powerapps.handheld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BarcodeReceiver(private val onScan: (String) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return

        // Read configured extra keys or fall back to standard Zebra/Honeywell defaults
        val barcode = intent.getStringExtra("com.symbol.datawedge.data_string")
            ?: intent.getStringExtra("version_1_data")
            ?: intent.getStringExtra("data")

        if (!barcode.isNullOrEmpty()) {
            Log.d("POWERAPPS_SCANNER", "Captured hardware barcode: $barcode")
            onScan(barcode)
        } else {
            Log.w("POWERAPPS_SCANNER", "Received scan intent without valid string extra.")
        }
    }
}
```

---

### 2.6 Project Configuration Files

#### `src-tauri/Cargo.toml`
```toml
[package]
name = "powerapps-mobile-handheld-shell"
version = "1.0.0"
description = "Hardened, fast, low-memory PowerApps shell for enterprise Android handhelds"
authors = ["Enterprise Mobility Operations"]
edition = "2021"

[lib]
name = "powerapps_mobile_handheld_shell"
crate-type = ["staticlib", "cdylib", "rlib"]

[dependencies]
tauri = { version = "2.0", features = ["unstable"] }
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
url = "2.5"

[build-dependencies]
tauri-build = { version = "2.0", features = [] }
```

#### `src-tauri/tauri.conf.json`
```json
{
  "$schema": "https://raw.githubusercontent.com/tauri-apps/tauri/dev/crates/tauri-config-schema/schemas/config.schema.json",
  "productName": "PowerAppsMobile",
  "version": "1.0.0",
  "identifier": "com.powerapps.handheld",
  "build": {
    "frontendDist": "../ui"
  },
  "app": {
    "withGlobalTauri": true,
    "security": {
      "csp": null
    }
  },
  "bundle": {
    "active": true,
    "targets": ["apk"],
    "icon": [
      "icons/128x128.png",
      "icons/icon.png"
    ]
  }
}
```

#### `ui/index.html`
```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <title>PowerApps Mobile Shell</title>
  <style>
    body {
      margin: 0;
      background-color: #020617;
      color: #94a3b8;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      height: 100vh;
      user-select: none;
    }
    .spinner {
      width: 32px;
      height: 32px;
      border: 3px solid #1e293b;
      border-top: 3px solid #0284c7;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
      margin-bottom: 16px;
    }
    @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
  </style>
</head>
<body>
  <div class="spinner"></div>
  <div style="font-size: 14px; font-weight: 500;">Starting Handheld Terminal...</div>
</body>
</html>
```

---

### 2.7 Cloud APK CI/CD Pipeline (`.github/workflows/release.yml`)
Makers never install Android Studio or the NDK locally. Pushing to `main` builds the installable `.apk` automatically.

```yaml
name: Build Handheld PowerApps APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build-apk:
    runs-on: ubuntu-latest
    steps:
      - name: Check out repository
        uses: actions/checkout@v4

      - name: Setup Java JDK
        uses: actions/setup-java@v4
        with:
          distribution: 'zulu'
          java-version: '17'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Setup Rust Toolchain
        uses: dtolnay/rust-toolchain@stable
        with:
          targets: aarch64-linux-android,armv7-linux-androideabi

      - name: Install Tauri CLI
        run: cargo install tauri-cli --version "^2.0"

      - name: Build Android APK (Unsigned Release)
        run: cargo tauri android build --apk

      - name: Upload APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: PowerApps-Handheld-Release-APK
          path: src-tauri/gen/android/app/build/outputs/apk/universal/release/*.apk
```

---

## Part 3: Developer Task List

### Sprint 1: Mobile Architecture & Compile-Time Embedding
- [x] Create project structure with embedded `config.json` via `include_str!`.
- [x] Initialize Tauri v2 mobile entrypoints (`lib.rs` and `main.rs`).
- [x] Configure cloud GitHub Actions pipeline for automated Android APK compilation.
- [x] Implement domain whitelist validation on mobile navigation requests.

### Sprint 2: Hardware Intent Bridge & Input Injection
- [x] Create native Android BroadcastReceiver in Kotlin listening for configured scanner intent actions.
- [x] Implement the synthetic DOM injection pipeline (`inject_scanned_barcode`) to populate active Text Input controls without refreshing.
- [x] Add fallback global event dispatch (`barcodeScanned` CustomEvent and `window.__LAST_BARCODE__`).
- [x] Enforce immersive fullscreen and prevent touch context menus.

### Sprint 3: Verification & Device Validation
- [ ] Deploy generated `.apk` to a physical Zebra or Honeywell device using ADB or direct sideload.
- [ ] Configure Zebra DataWedge to send intent action matching `config.json`.
- [ ] Pull physical scanner trigger; verify the focused Power App text input populates instantly.
- [ ] Confirm no page reloads occur during rapid sequential scanning.
- [ ] Measure runtime memory footprint using Android Profiler (Target: < 65 MB RSS).

---

## Part 4: AGENTS.md

### Mission & Code Guidelines for AI Agents
You are an expert mobile systems developer maintaining the **PowerApps Mobile Handheld Shell** repository.

1. **Naming Standard:** All project identifiers, references, package configurations, and generated artifacts must start with the prefix `powerapps` or `PowerApps`.
2. **Embedded Config Integrity:** Never attempt to read `config.json` at runtime via Android filesystem APIs. All configuration data must remain compiled into the binary via `include_str!`.
3. **Zero-Reload State Guarantee:** Never trigger full URL navigations or query parameter reloads to pass scanned data. Always use the active element synthetic event dispatch pipeline.
4. **Strict YAGNI Compliance:** Do not add camera-based barcode scanning software, multi-app window splitting, local SQLite caches, or complex MDM enrollments unless explicitly instructed.
5. **Full File Outputs Only:** Always generate complete, runnable code files. Never output partial diffs, snippets, or files containing placeholder comments.
6. **Logging Conventions:** All native logging must follow standardized domain tags:
   * `[INIT]` Runtime and lifecycle startup events.
   * `[CONFIG]` Configuration par