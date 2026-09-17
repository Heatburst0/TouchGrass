use active_win_pos_rs::get_active_window;
use device_query::{DeviceQuery, DeviceState};

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

    /// The active window's app name, lowercased; empty if it can't be read.
    pub fn active_app(&self) -> String {
        match get_active_window() {
            Ok(w) => w.app_name.to_lowercase(),
            Err(_) => String::new(),
        }
    }
}
