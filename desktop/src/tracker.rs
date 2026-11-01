use active_win_pos_rs::get_active_window;
use device_query::{DeviceQuery, DeviceState};

/// Foreground window metadata. Titles are used locally to parse project names.
#[derive(Default)]
pub struct WindowInfo {
    pub app: String,
    pub pid: u64,
    pub title: String,
}

/// Samples input *activity* (not contents) and the active window's app.
pub struct Tracker {
    device: DeviceState,
    last_mouse: (i32, i32),
    last_keys: usize,
}

impl Tracker {
    pub fn new() -> Self {
        let device = DeviceState::new();
        let mouse = device.get_mouse();
        let keys = device.get_keys().len();
        Tracker {
            last_mouse: mouse.coords,
            last_keys: keys,
            device,
        }
    }

    /// True if keyboard or mouse input happened since the previous call. We record
    /// only that input occurred (mouse moved / keys held), never which keys.
    pub fn sample_active(&mut self) -> bool {
        let mouse = self.device.get_mouse();
        let keys = self.device.get_keys();
        let moved = mouse.coords != self.last_mouse;
        let typed = !keys.is_empty() || keys.len() != self.last_keys;
        self.last_mouse = mouse.coords;
        self.last_keys = keys.len();
        moved || typed
    }

    /// The active window's app name (lowercased) and its process id; ("", 0) if
    /// it can't be read.
    #[allow(dead_code)] // compatibility API; sampling now also needs the title
    pub fn active_window(&self) -> (String, u64) {
        let window = self.active_window_info();
        (window.app, window.pid)
    }

    /// Read the foreground window, or empty metadata when it is unavailable.
    pub fn active_window_info(&self) -> WindowInfo {
        match get_active_window() {
            Ok(w) => WindowInfo {
                app: w.app_name.to_lowercase(),
                pid: w.process_id,
                title: w.title,
            },
            Err(_) => WindowInfo::default(),
        }
    }
}
