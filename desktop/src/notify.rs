//! Desktop toast notifications. Windows-only; a no-op elsewhere so the rest of
//! the agent stays platform-agnostic.

#[cfg(windows)]
pub fn toast(title: &str, body: &str) {
    use tauri_winrt_notification::{Duration, Toast};
    let result = Toast::new(Toast::POWERSHELL_APP_ID)
        .title(title)
        .text1(body)
        .duration(Duration::Short)
        .show();
    if let Err(e) = result {
        eprintln!("toast failed: {e}");
    }
}

#[cfg(not(windows))]
pub fn toast(_title: &str, _body: &str) {}
