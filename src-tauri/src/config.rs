use serde::Deserialize;

/// The schema of the root `config.json`.
///
/// Nothing reads this file at runtime: it is embedded with `include_str!`
/// (see `src/lib.rs`) and validated before compilation by `build.rs`.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShellConfig {
    /// Android application display name.
    pub app_name: String,
    /// Direct link to the deployed Power Apps canvas or model-driven app.
    pub power_app_url: String,
    /// Keep the handheld screen awake for the whole shift.
    pub keep_screen_on: bool,
    /// Swallow the hardware and gesture Back button.
    pub intercept_back_button: bool,
    /// Scanner broadcast shape for this fleet.
    pub scanner: ScannerConfig,
    /// Opt-in device capabilities. Absent in an older config.json, which means all off.
    #[serde(default)]
    pub native: NativeConfig,
    /// Apex domains allowed to load; subdomains are included automatically.
    pub allowed_domains: Vec<String>,
}

/// Camera, microphone and location are granted only when the maker asks for them.
/// Gradle reads the same block to decide which permissions the APK declares, so an
/// APK never carries a permission its config cannot use. `camera_scanner` is
/// reserved for the camera scanner and only forces the camera permission today.
#[derive(Debug, Clone, Copy, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct NativeConfig {
    pub camera: bool,
    pub camera_scanner: bool,
    pub microphone: bool,
    pub location: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScannerConfig {
    /// Intent action the OEM scan engine broadcasts.
    pub intent_action: String,
    /// Intent string extra holding the decoded barcode.
    pub intent_extra_key: String,
}

#[cfg(test)]
mod tests {
    use super::ShellConfig;

    /// A config.json written before the `native` block existed must keep building,
    /// with every device input off. That is what the defaults buy.
    #[test]
    fn config_without_native_block_keeps_every_input_off() {
        let raw = r#"{
          "appName": "Contoso Pick & Pack",
          "powerAppUrl": "https://apps.powerapps.com/play/e/env/a/app",
          "keepScreenOn": true,
          "interceptBackButton": true,
          "scanner": {
            "intentAction": "com.powerapps.handheld.SCAN",
            "intentExtraKey": "com.symbol.datawedge.data_string"
          },
          "allowedDomains": ["apps.powerapps.com"]
        }"#;

        let config: ShellConfig = serde_json::from_str(raw).expect("older config must parse");
        assert!(!config.native.camera);
        assert!(!config.native.microphone);
        assert!(!config.native.location);
        assert!(!config.native.camera_scanner);
    }
}
