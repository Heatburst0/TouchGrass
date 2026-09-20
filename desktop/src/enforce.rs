// Desktop enforcement: minimize a blocked app's window, or kill its process.

/// Minimize whatever window is in the foreground right now.
#[cfg(windows)]
pub fn minimize_foreground() {
    use windows::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, ShowWindow, SW_MINIMIZE};
    // unsafe: these are raw Win32 calls. GetForegroundWindow returns the HWND of
    // the app the user is looking at; ShowWindow(..SW_MINIMIZE) sends it to the taskbar.
    unsafe {
        let hwnd = GetForegroundWindow();
        let _ = ShowWindow(hwnd, SW_MINIMIZE);
    }
}
#[cfg(not(windows))]
pub fn minimize_foreground() {}

/// Terminate a process by PID (for "force-quit" apps).
pub fn force_quit(pid: u64) {
    if pid == 0 { return; }
    #[cfg(windows)]
    {
        // /F = force, /T = also kill child processes
        let _ = std::process::Command::new("taskkill")
            .args(["/PID", &pid.to_string(), "/F", "/T"]).output();
    }
    #[cfg(not(windows))]
    {
        let _ = std::process::Command::new("kill").arg(pid.to_string()).output();
    }
}
