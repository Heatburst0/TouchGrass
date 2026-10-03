//! System-tray control surface for the hidden `run` watcher (Windows only).
//! Shows a tray icon with Status / Stop, while the schedule + live-session watch
//! loop runs on a background thread.

use crate::config::Config;
use anyhow::Result;
use std::thread;
use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
use tray_icon::{Icon, TrayIconBuilder};
use winapi::um::winuser::{DispatchMessageW, GetMessageW, TranslateMessage, MSG};

/// Run the watch loop in the background and own the tray + Windows message pump.
pub fn run(cfg: Config) -> Result<()> {
    let email = cfg.email.clone().unwrap_or_else(|| "(not signed in)".into());

    let mut watch_cfg = cfg;
    thread::spawn(move || {
        if let Err(e) = crate::schedule::watch(&mut watch_cfg) {
            eprintln!("watch loop error: {e}");
        }
    });

    let status = MenuItem::new("Show status", true, None);
    let stop = MenuItem::new("Stop agent", true, None);
    let menu = Menu::new();
    menu.append_items(&[
        &MenuItem::new("TouchGrass focus agent", false, None),
        &PredefinedMenuItem::separator(),
        &status,
        &stop,
    ])?;

    let _tray = TrayIconBuilder::new()
        .with_tooltip("TouchGrass focus agent")
        .with_menu(Box::new(menu))
        .with_icon(make_icon())
        .build()?;

    let status_id = status.id().clone();
    let stop_id = stop.id().clone();
    let menu_rx = MenuEvent::receiver();

    let mut msg: MSG = unsafe { std::mem::zeroed() };
    unsafe {
        while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
            while let Ok(event) = menu_rx.try_recv() {
                if event.id == stop_id {
                    crate::hosts::clear();
                    std::process::exit(0);
                } else if event.id == status_id {
                    crate::notify::toast("TouchGrass agent", &format!("Running · {email}"));
                }
            }
        }
    }
    Ok(())
}

/// A simple solid grass-green 32×32 icon so we don't have to ship an .ico.
fn make_icon() -> Icon {
    let size: u32 = 32;
    let mut rgba = Vec::with_capacity((size * size * 4) as usize);
    for _ in 0..(size * size) {
        rgba.extend_from_slice(&[0x7B, 0xE0, 0x4A, 0xFF]); // grass green, opaque
    }
    Icon::from_rgba(rgba, size, size).expect("valid icon")
}
