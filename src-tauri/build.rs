// config.json is validated here, at compile time, so an invalid or incomplete
// configuration fails the build instead of shipping inside the APK.
#[path = "src/config.rs"]
#[allow(dead_code)]
mod config;

fn main() {
    println!("cargo:rerun-if-changed=../config.json");
    println!("cargo:rerun-if-changed=src/config.rs");

    let raw = std::fs::read_to_string("../config.json")
        .expect("[CONFIG][FATAL] config.json is missing from the repository root.");
    let shell = serde_json::from_str::<config::ShellConfig>(&raw)
        .unwrap_or_else(|err| panic!("[CONFIG][FATAL] config.json is invalid: {err}"));

    if url::Url::parse(&shell.power_app_url).is_err() {
        panic!(
            "[CONFIG][FATAL] powerAppUrl is not a valid absolute URL: {}",
            shell.power_app_url
        );
    }
    if shell.scanner.intent_action.is_empty() || shell.scanner.intent_extra_key.is_empty() {
        panic!("[CONFIG][FATAL] scanner.intentAction and scanner.intentExtraKey cannot be empty.");
    }

    tauri_build::build()
}
