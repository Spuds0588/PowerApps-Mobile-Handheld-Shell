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
    /// Apex domains allowed to load; subdomains are included automatically.
    pub allowed_domains: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScannerConfig {
    /// Intent action the OEM scan engine broadcasts.
    pub intent_action: String,
    /// Intent string extra holding the decoded barcode.
    pub intent_extra_key: String,
}
