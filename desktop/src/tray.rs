//! System-tray control surface for the hidden `run` watcher (Windows only).
//! Shows a tray icon with Status / Stop, while the schedule + live-session watch
//! loop runs on a background thread.

use crate::config::Config;
use anyhow::Result;
use std::panic::AssertUnwindSafe;
use std::thread;
use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
use tray_icon::{Icon, TrayIconBuilder};
use winapi::um::processthreadsapi::GetCurrentThreadId;
use winapi::um::winuser::{
    DispatchMessageW, GetMessageW, PostThreadMessageW, TranslateMessage, MSG, WM_QUIT,
};

/// Run the watch loop in the background and own the tray + Windows message pump.
pub fn run(cfg: Config) -> Result<()> {
    let email = cfg
        .email
        .clone()
        .unwrap_or_else(|| "(not signed in)".into());

    // Build the tray first so a build failure doesn't strand a running watch thread.
    let status = MenuItem::new("Show status", true, None);
    let stop = MenuItem::new("Stop agent", true, None);
    let menu = Menu::new();
    menu.append_items(&[
        &MenuItem::new("TouchGrass focus agent", false, None),
        &PredefinedMenuItem::separator(),
        &status,
        &stop,
    ])?;

    let tray = TrayIconBuilder::new()
        .with_tooltip("TouchGrass focus agent")
        .with_menu(Box::new(menu))
        .with_icon(make_icon())
        .build()?;

    // The watch loop runs on a background thread. If it ever returns an error or
    // panics, clean up the hosts block, tell the user, and ask the pump to quit —
    // otherwise a dead thread would leave the tray saying "Running" forever.
    // SAFETY: GetCurrentThreadId has no preconditions and returns this thread's id.
    let main_tid = unsafe { GetCurrentThreadId() };
    let mut watch_cfg = cfg;
    thread::spawn(move || {
        let result =
            std::panic::catch_unwind(AssertUnwindSafe(|| crate::schedule::watch(&mut watch_cfg)));
        crate::hosts::clear();
        let msg = match result {
            Ok(Ok(())) => "Agent stopped.".to_string(),
            Ok(Err(e)) => format!("Agent error: {e}"),
            Err(_) => "Agent crashed.".to_string(),
        };
        crate::notify::toast("TouchGrass agent stopped", &msg);
        // SAFETY: posting WM_QUIT to the main thread's message queue is always valid.
        unsafe { PostThreadMessageW(main_tid, WM_QUIT, 0, 0) };
    });

    let status_id = status.id().clone();
    let stop_id = stop.id().clone();
    let menu_rx = MenuEvent::receiver();

    let mut stop_requested = false;
    let mut msg: MSG = unsafe { std::mem::zeroed() };
    loop {
        // SAFETY: `msg` is a valid MSG; a null HWND requests all messages for this
        // thread; the pump runs on the thread that created the tray window.
        let ret = unsafe { GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) };
        if ret <= 0 {
            break; // 0 = WM_QUIT, -1 = error
        }
        // SAFETY: `msg` was just filled by a successful GetMessageW.
        unsafe {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        while let Ok(event) = menu_rx.try_recv() {
            if event.id == stop_id {
                stop_requested = true;
                break;
            } else if event.id == status_id {
                let e = email.clone();
                thread::spawn(move || {
                    crate::notify::toast("TouchGrass agent", &format!("Running · {e}"))
                });
            }
        }
        if stop_requested {
            break;
        }
    }

    crate::hosts::clear();
    drop(tray); // remove the tray icon now rather than leaving a ghost
    if stop_requested {
        std::process::exit(0);
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
