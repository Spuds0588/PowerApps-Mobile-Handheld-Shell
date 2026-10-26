use std::sync::OnceLock;

use tauri::webview::{PermissionKind, PermissionResponse};
use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};
use url::Url;

mod config;
use config::{NativeConfig, ShellConfig};

/// config.json is embedded in the binary at compile time, so there is no loose
/// configuration file on the device and no runtime filesystem read.
const COMPILED_CONFIG: &str = include_str!("../../config.json");

/// The scanner arrives on a JNI thread with no Rust context, so the app handle
/// is parked here while the shell comes up.
static SHELL: OnceLock<AppHandle> = OnceLock::new();

const MAIN_WEBVIEW: &str = "powerapps";

/// Runs on every page load of the handheld viewport.
const INIT_SCRIPT: &str = r#"(function () {
  window.addEventListener('contextmenu', function (event) { event.preventDefault(); });
  window.addEventListener('focusin', function (event) {
    var target = event.target;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) {
      window.__LAST_FOCUS__ = target;
    }
  });
  console.log('[SHELL][READY] Mobile hardware runtime active.');
})();"#;

/// Scanned values are written straight into the focused control. `__BARCODE__`
/// is replaced with a JSON string so no scanned byte can reach the parser.
const INJECTION_SCRIPT: &str = r#"(function () {
  try {
    var value = __BARCODE__;
    window.__LAST_BARCODE__ = value;
    var active = document.activeElement;
    var isTextField = active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA');
    // A scan can arrive after the keyboard closed or the page blurred. Fall back to
    // the last field the operator touched, which is where they expect the value.
    if (!isTextField && window.__LAST_FOCUS__ && document.contains(window.__LAST_FOCUS__)) {
      active = window.__LAST_FOCUS__;
      isTextField = true;
    }
    if (isTextField) {
      var proto = active.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(active, value);
      active.dispatchEvent(new Event('input', { bubbles: true }));
      active.dispatchEvent(new Event('change', { bubbles: true }));
      console.log('[SCANNER][INJECTED] Filled focused control:', value);
    } else {
      console.warn('[SCANNER][WARN] No focused text input. Value kept in window.__LAST_BARCODE__');
    }
    window.dispatchEvent(new CustomEvent('barcodeScanned', { detail: value }));
  } catch (err) {
    console.error('[SCANNER][ERROR] Barcode injection failed:', err);
  }
})();"#;

fn load_embedded_config() -> ShellConfig {
    serde_json::from_str(COMPILED_CONFIG)
        .expect("[CONFIG][FATAL] Corrupted config.json embedded inside the binary.")
}

/// Device capabilities are opt-in. Anything the maker left off is refused before
/// Android can ask the operator, so a granted OS permission cannot re-open it.
fn native_permission(native: NativeConfig, kind: PermissionKind) -> PermissionResponse {
    let allowed = match kind {
        PermissionKind::Camera => native.camera,
        PermissionKind::Microphone => native.microphone,
        PermissionKind::Geolocation => native.location,
        _ => true,
    };
    if allowed {
        PermissionResponse::Default
    } else {
        println!("[NATIVE][DENIED] {kind:?} is not enabled in config.json");
        PermissionResponse::Deny
    }
}

fn is_domain_allowed(target: &Url, whitelist: &[String]) -> bool {
    if whitelist.is_empty() {
        return true;
    }
    match target.host_str() {
        Some(host) => whitelist
            .iter()
            .any(|allowed| host == allowed || host.ends_with(&format!(".{allowed}"))),
        None => false,
    }
}

/// Injects a scanned barcode into the focused control of the running app,
/// without navigating and without reloading the Power App.
fn inject_scanned_barcode(app: &AppHandle, barcode: &str) {
    let Some(webview) = app.get_webview_window(MAIN_WEBVIEW) else {
        eprintln!("[SCANNER][ERROR] Handheld viewport '{MAIN_WEBVIEW}' is not mounted yet.");
        return;
    };
    let payload = serde_json::to_string(barcode).unwrap_or_else(|_| String::from("\"\""));
    let script = INJECTION_SCRIPT.replace("__BARCODE__", &payload);
    match webview.eval(script) {
        Ok(()) => println!("[SCANNER][SUCCESS] Dispatched barcode payload: {barcode}"),
        Err(err) => eprintln!("[SCANNER][ERROR] Could not dispatch barcode payload: {err}"),
    }
}

/// Called from `BarcodeReceiver.kt` over JNI.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_powerapps_handheld_BarcodeReceiver_nativeOnScan<'local>(
    mut env: jni::JNIEnv<'local>,
    _receiver: jni::objects::JObject<'local>,
    value: jni::objects::JString<'local>,
) {
    let barcode = match env.get_string(&value) {
        Ok(text) => String::from(text),
        Err(err) => {
            eprintln!("[SCANNER][ERROR] Undecodable scan intent payload: {err}");
            return;
        }
    };
    println!("[SCANNER][RECEIVED] Hardware scan captured: {barcode}");
    match SHELL.get() {
        Some(app) => inject_scanned_barcode(app, &barcode),
        None => eprintln!("[SCANNER][ERROR] Shell is not running; barcode discarded."),
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let config = load_embedded_config();
    println!(
        "[INIT] Starting PowerApps Mobile Handheld Shell: '{}'",
        config.app_name
    );
    println!(
        "[CONFIG] Kiosk keepScreenOn={} interceptBackButton={}",
        config.keep_screen_on, config.intercept_back_button
    );
    println!(
        "[CONFIG] Scanner action='{}' extra='{}'",
        config.scanner.intent_action, config.scanner.intent_extra_key
    );
    let native = config.native;
    println!(
        "[CONFIG] Native camera={} cameraScanner={} microphone={} location={}",
        native.camera, native.camera_scanner, native.microphone, native.location
    );

    let power_app_url: Url = config
        .power_app_url
        .parse()
        .expect("[CONFIG][FATAL] powerAppUrl is not a valid absolute URL.");

    tauri::Builder::default()
        .setup(move |app| {
            SHELL.set(app.handle().clone()).ok();

            let whitelist = config.allowed_domains;
            WebviewWindowBuilder::new(app, MAIN_WEBVIEW, WebviewUrl::External(power_app_url))
                // Fullscreen and decoration flags are desktop-only in Tauri; on
                // Android the activity owns the screen and MainActivity.kt hides
                // the system bars.
                .title(&config.app_name)
                .initialization_script(INIT_SCRIPT)
                .on_permission_request(move |_, kind| native_permission(native, kind))
                .on_navigation(move |url| {
                    let allowed = is_domain_allowed(url, &whitelist);
                    if allowed {
                        println!("[NAVIGATION][ALLOWED] {}", url);
                    } else {
                        println!("[SECURITY][BLOCKED] Unauthorized navigation: {}", url);
                    }
                    allowed
                })
                .build()?;

            println!("[LAYOUT][READY] Handheld viewport mounted.");
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("[FATAL] Error initializing PowerApps mobile runtime");
}
