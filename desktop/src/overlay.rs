//! On-screen focus overlays: a click-through mini timer in the top-right corner
//! and a translucent event banner just above the taskbar. They are the agent's
//! own windows, so Do Not Disturb / Focus Assist can't hide them (unlike toasts).
//!
//! Session code updates shared state from any thread via `phase` / `stats` /
//! `banner` / `clear_hud`. The windows live on the thread that runs the Win32
//! message pump — the tray loop for `run`, or `run_blocking` for `focus`.

#[cfg(not(windows))]
use anyhow::Result;
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

const BANNER_SECS: u64 = 4;

/// Accent colour of a banner.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Tone {
    Good,
    Warn,
    Bad,
}

#[derive(Clone)]
#[cfg_attr(not(windows), allow(dead_code))]
struct Hud {
    focus: bool,
    cycle: i64,
    cycles: i64,
    phase_end: Instant,
    productive_secs: i64,
    off_task_secs: i64,
}

#[derive(Clone)]
#[cfg_attr(not(windows), allow(dead_code))]
struct Banner {
    title: String,
    body: String,
    tone: Tone,
    until: Instant,
}

#[derive(Default)]
struct State {
    hud: Option<Hud>,
    banner: Option<Banner>,
}

fn state() -> MutexGuard<'static, State> {
    static STATE: OnceLock<Mutex<State>> = OnceLock::new();
    STATE
        .get_or_init(|| Mutex::new(State::default()))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

/// Show the corner timer for a focus (or break) phase lasting `secs`.
pub fn phase(focus: bool, cycle: i64, cycles: i64, secs: i64) {
    let mut s = state();
    let (productive_secs, off_task_secs) = s
        .hud
        .as_ref()
        .map_or((0, 0), |h| (h.productive_secs, h.off_task_secs));
    s.hud = Some(Hud {
        focus,
        cycle,
        cycles,
        phase_end: Instant::now() + Duration::from_secs(secs.max(0) as u64),
        productive_secs,
        off_task_secs,
    });
}

/// Update the live productive / off-task totals shown on the corner timer.
pub fn stats(productive_secs: i64, off_task_secs: i64) {
    let mut s = state();
    if let Some(h) = s.hud.as_mut() {
        h.productive_secs = productive_secs;
        h.off_task_secs = off_task_secs;
    }
}

/// Hide the corner timer (session over).
pub fn clear_hud() {
    state().hud = None;
}

/// Pop a translucent banner above the taskbar for a few seconds.
pub fn banner(title: &str, body: &str, tone: Tone) {
    state().banner = Some(Banner {
        title: title.to_string(),
        body: body.to_string(),
        tone,
        until: Instant::now() + Duration::from_secs(BANNER_SECS),
    });
}

#[cfg(windows)]
pub use win::{init, run_blocking, set_dpi_aware};

/// No overlay off Windows.
#[cfg(not(windows))]
pub fn init() -> Result<()> {
    Ok(())
}

/// No DPI setup off Windows.
#[cfg(not(windows))]
pub fn set_dpi_aware() {}

/// Off Windows there is no pump to run; just run the work.
#[cfg(not(windows))]
pub fn run_blocking<F>(f: F) -> Result<()>
where
    F: FnOnce() -> Result<()> + Send + 'static,
{
    f()
}

#[cfg(windows)]
mod win {
    use super::{clear_hud, state, Tone, BANNER_SECS};
    use anyhow::{anyhow, bail, Result};
    use std::ptr::{null, null_mut};
    use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
    use std::time::{Duration, Instant};
    use winapi::shared::minwindef::{HINSTANCE, LPARAM, LRESULT, UINT, WPARAM};
    use winapi::shared::windef::{HDC, HWND, RECT};
    use winapi::um::libloaderapi::GetModuleHandleW;
    use winapi::um::processthreadsapi::GetCurrentThreadId;
    use winapi::um::wingdi::{
        CreateFontW, CreateRoundRectRgn, CreateSolidBrush, DeleteObject, GetDeviceCaps,
        SelectObject, SetBkMode, SetTextColor, CLEARTYPE_QUALITY, CLIP_DEFAULT_PRECIS,
        DEFAULT_CHARSET, DEFAULT_PITCH, FF_DONTCARE, FW_BOLD, FW_NORMAL, FW_SEMIBOLD, LOGPIXELSY,
        OUT_DEFAULT_PRECIS, TRANSPARENT,
    };
    use winapi::um::winuser::{
        BeginPaint, CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, DrawTextW,
        EndPaint, FillRect, GetClientRect, GetDC, GetMessageW, InvalidateRect, KillTimer,
        LoadCursorW, PostMessageW, PostQuitMessage, PostThreadMessageW, RegisterClassW, ReleaseDC,
        SetLayeredWindowAttributes, SetProcessDPIAware, SetTimer, SetWindowPos, SetWindowRgn,
        ShowWindow, SystemParametersInfoW, TranslateMessage, CS_HREDRAW, CS_VREDRAW,
        DT_END_ELLIPSIS, DT_LEFT, DT_NOPREFIX, DT_SINGLELINE, DT_VCENTER, HWND_TOPMOST, IDC_ARROW,
        LWA_ALPHA, MSG, PAINTSTRUCT, SPI_GETWORKAREA, SWP_NOACTIVATE, SWP_SHOWWINDOW, SW_HIDE,
        WM_APP, WM_PAINT, WM_QUIT, WM_TIMER, WNDCLASSW, WS_EX_LAYERED, WS_EX_NOACTIVATE,
        WS_EX_TOOLWINDOW, WS_EX_TOPMOST, WS_EX_TRANSPARENT, WS_POPUP,
    };

    // Logical (96-dpi) sizes; scaled to the real DPI at runtime.
    const HUD_W: i32 = 228;
    const HUD_H: i32 = 92;
    const BANNER_W: i32 = 440;
    const BANNER_H: i32 = 64;
    const MARGIN: i32 = 16;
    const RADIUS: i32 = 16;
    const ACCENT_W: i32 = 4;
    const ALPHA: u8 = 232; // ~91% opaque
    const TIMER_ID: usize = 1;
    const TICK_MS: u32 = 250;

    const fn rgb(r: u8, g: u8, b: u8) -> u32 {
        (r as u32) | ((g as u32) << 8) | ((b as u32) << 16)
    }
    const BG: u32 = rgb(20, 22, 21);
    const TEXT: u32 = rgb(237, 243, 239);
    const MUTED: u32 = rgb(140, 160, 149);
    const GREEN: u32 = rgb(123, 224, 74);
    const AMBER: u32 = rgb(251, 191, 36);
    const RED: u32 = rgb(248, 113, 113);

    /// Private message: ask the overlay's pump thread to quit (reliable wake-up for
    /// `run_blocking`, routed through a known window instead of the thread queue).
    const WM_APP_QUIT: UINT = WM_APP + 1;

    static INIT: AtomicBool = AtomicBool::new(false);
    static CLASS_REGISTERED: AtomicBool = AtomicBool::new(false);
    static HUD: AtomicIsize = AtomicIsize::new(0);
    static BANNER: AtomicIsize = AtomicIsize::new(0);
    static SCALE_PCT: AtomicIsize = AtomicIsize::new(100);

    fn px(v: i32) -> i32 {
        v * SCALE_PCT.load(Ordering::Relaxed) as i32 / 100
    }

    fn handle(slot: &AtomicIsize) -> HWND {
        slot.load(Ordering::Relaxed) as HWND
    }

    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    /// Declare the process DPI-aware. Must run before the process creates any
    /// window (including the tray's), otherwise Windows bitmap-scales the overlay.
    pub fn set_dpi_aware() {
        // SAFETY: no preconditions; failure just leaves the default awareness.
        unsafe { SetProcessDPIAware() };
    }

    /// Create the (hidden) overlay windows and their repaint timer. Must run on
    /// the thread that pumps Win32 messages. Idempotent once it has succeeded;
    /// a failed attempt cleans up and can be retried.
    pub fn init() -> Result<()> {
        if INIT.load(Ordering::SeqCst) {
            return Ok(());
        }
        // SAFETY: plain Win32 calls with valid arguments; every pointer passed
        // (class name, WNDCLASSW) outlives the call that uses it, and every
        // window created on a failure path is destroyed before returning.
        unsafe {
            let screen = GetDC(null_mut());
            let dpi = GetDeviceCaps(screen, LOGPIXELSY).max(96);
            ReleaseDC(null_mut(), screen);
            SCALE_PCT.store((dpi * 100 / 96) as isize, Ordering::Relaxed);

            let hinst = GetModuleHandleW(null());
            let class = wide("TouchGrassOverlay");
            if !CLASS_REGISTERED.load(Ordering::SeqCst) {
                let wc = WNDCLASSW {
                    style: CS_HREDRAW | CS_VREDRAW,
                    lpfnWndProc: Some(wnd_proc),
                    cbClsExtra: 0,
                    cbWndExtra: 0,
                    hInstance: hinst,
                    hIcon: null_mut(),
                    hCursor: LoadCursorW(null_mut(), IDC_ARROW),
                    hbrBackground: null_mut(),
                    lpszMenuName: null(),
                    lpszClassName: class.as_ptr(),
                };
                if RegisterClassW(&wc) == 0 {
                    bail!("RegisterClassW failed");
                }
                CLASS_REGISTERED.store(true, Ordering::SeqCst);
            }

            let hud = create(&class, hinst, px(HUD_W), px(HUD_H))?;
            let banner = match create(&class, hinst, px(BANNER_W), px(BANNER_H)) {
                Ok(b) => b,
                Err(e) => {
                    DestroyWindow(hud);
                    return Err(e);
                }
            };
            if SetTimer(hud, TIMER_ID, TICK_MS, None) == 0 {
                DestroyWindow(hud);
                DestroyWindow(banner);
                bail!("SetTimer failed");
            }
            HUD.store(hud as isize, Ordering::SeqCst);
            BANNER.store(banner as isize, Ordering::SeqCst);
        }
        INIT.store(true, Ordering::SeqCst); // published only after full success
        Ok(())
    }

    /// A hidden, click-through, always-on-top, no-taskbar, translucent popup.
    unsafe fn create(class: &[u16], hinst: HINSTANCE, w: i32, h: i32) -> Result<HWND> {
        let hwnd = CreateWindowExW(
            WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
            class.as_ptr(),
            class.as_ptr(),
            WS_POPUP,
            0,
            0,
            w,
            h,
            null_mut(),
            null_mut(),
            hinst,
            null_mut(),
        );
        if hwnd.is_null() {
            bail!("CreateWindowExW failed");
        }
        // A layered window can't be shown until its attributes are set.
        if SetLayeredWindowAttributes(hwnd, 0, ALPHA, LWA_ALPHA) == 0 {
            DestroyWindow(hwnd);
            bail!("SetLayeredWindowAttributes failed");
        }
        // Rounded corners are cosmetic: on failure keep a square window. The system
        // owns the region only after SetWindowRgn succeeds, so free it otherwise.
        let rgn = CreateRoundRectRgn(0, 0, w + 1, h + 1, px(RADIUS), px(RADIUS));
        if !rgn.is_null() && SetWindowRgn(hwnd, rgn, 1) == 0 {
            DeleteObject(rgn as _);
        }
        Ok(hwnd)
    }

    /// Run `work` on a background thread while this thread pumps messages so the
    /// overlay can draw (used by the `focus` command, which has no tray loop).
    pub fn run_blocking<F>(work: F) -> Result<()>
    where
        F: FnOnce() -> Result<()> + Send + 'static,
    {
        if let Err(e) = init() {
            eprintln!("overlay unavailable: {e}");
            return work();
        }
        // SAFETY: GetCurrentThreadId has no preconditions.
        let pump_tid = unsafe { GetCurrentThreadId() };
        let worker = std::thread::spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(work));
            // Leave the final banner up for a moment before the process exits.
            if state().banner.is_some() {
                std::thread::sleep(Duration::from_secs(BANNER_SECS));
            }
            // Wake the pump via our own window (reliable); fall back to the thread
            // queue if that post fails.
            // SAFETY: posting to a window/thread handle never dereferences memory.
            unsafe {
                if PostMessageW(handle(&HUD), WM_APP_QUIT, 0, 0) == 0 {
                    PostThreadMessageW(pump_tid, WM_QUIT, 0, 0);
                }
            }
            result
        });
        pump();
        clear_hud();
        // SAFETY: the timer belongs to the HUD window on this thread.
        unsafe { KillTimer(handle(&HUD), TIMER_ID) };
        match worker.join() {
            Ok(Ok(result)) => result,
            _ => Err(anyhow!("focus session crashed")),
        }
    }

    fn pump() {
        // SAFETY: a standard message loop on the thread that owns the windows;
        // `msg` is only read after a successful GetMessageW fills it.
        unsafe {
            let mut msg: MSG = std::mem::zeroed();
            while GetMessageW(&mut msg, null_mut(), 0, 0) > 0 {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
    }

    unsafe extern "system" fn wnd_proc(
        hwnd: HWND,
        msg: UINT,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        match msg {
            WM_TIMER => {
                tick();
                0
            }
            WM_PAINT => {
                paint(hwnd);
                0
            }
            WM_APP_QUIT => {
                PostQuitMessage(0);
                0
            }
            _ => DefWindowProcW(hwnd, msg, wparam, lparam),
        }
    }

    /// Show/hide + reposition both windows from the shared state, then repaint.
    unsafe fn tick() {
        let now = Instant::now();
        let (show_hud, show_banner) = {
            let mut s = state();
            if s.banner.as_ref().is_some_and(|b| now >= b.until) {
                s.banner = None;
            }
            (s.hud.is_some(), s.banner.is_some())
        }; // lock released before any Win32 call that could re-enter wnd_proc

        let work = work_area();
        let hud = handle(&HUD);
        if show_hud {
            let (w, h) = (px(HUD_W), px(HUD_H));
            SetWindowPos(
                hud,
                HWND_TOPMOST,
                work.right - w - px(MARGIN),
                work.top + px(MARGIN),
                w,
                h,
                SWP_NOACTIVATE | SWP_SHOWWINDOW,
            );
            InvalidateRect(hud, null(), 0);
        } else {
            ShowWindow(hud, SW_HIDE);
        }

        let banner = handle(&BANNER);
        if show_banner {
            let (w, h) = (px(BANNER_W), px(BANNER_H));
            let x = work.left + (work.right - work.left - w) / 2;
            SetWindowPos(
                banner,
                HWND_TOPMOST,
                x,
                work.bottom - h - px(MARGIN),
                w,
                h,
                SWP_NOACTIVATE | SWP_SHOWWINDOW,
            );
            InvalidateRect(banner, null(), 0);
        } else {
            ShowWindow(banner, SW_HIDE);
        }
    }

    /// The desktop minus the taskbar.
    unsafe fn work_area() -> RECT {
        let mut r: RECT = std::mem::zeroed();
        SystemParametersInfoW(SPI_GETWORKAREA, 0, &mut r as *mut RECT as *mut _, 0);
        r
    }

    unsafe fn paint(hwnd: HWND) {
        let mut ps: PAINTSTRUCT = std::mem::zeroed();
        let hdc = BeginPaint(hwnd, &mut ps);
        let mut rc: RECT = std::mem::zeroed();
        GetClientRect(hwnd, &mut rc);
        fill(hdc, &rc, BG);
        SetBkMode(hdc, TRANSPARENT as i32);
        if hwnd == handle(&HUD) {
            paint_hud(hdc, &rc);
        } else {
            paint_banner(hdc, &rc);
        }
        EndPaint(hwnd, &ps);
    }

    unsafe fn paint_hud(hdc: HDC, rc: &RECT) {
        let Some(h) = state().hud.clone() else { return };
        let accent = if h.focus { GREEN } else { AMBER };
        fill(
            hdc,
            &RECT {
                left: 0,
                top: 0,
                right: px(ACCENT_W),
                bottom: rc.bottom,
            },
            accent,
        );

        let (left, right) = (px(16), rc.right - px(12));
        let label = format!(
            "{}  ·  {}/{}",
            if h.focus { "FOCUS" } else { "BREAK" },
            h.cycle,
            h.cycles
        );
        text(
            hdc,
            &label,
            RECT {
                left,
                top: px(8),
                right,
                bottom: px(26),
            },
            11,
            FW_BOLD,
            accent,
            DT_LEFT,
        );

        let remain = h
            .phase_end
            .saturating_duration_since(Instant::now())
            .as_secs();
        let clock = format!("{:02}:{:02}", remain / 60, remain % 60);
        text(
            hdc,
            &clock,
            RECT {
                left,
                top: px(24),
                right,
                bottom: px(60),
            },
            28,
            FW_BOLD,
            TEXT,
            DT_LEFT,
        );

        let stats = format!(
            "{}m productive  ·  {}m off-task",
            (h.productive_secs + 30) / 60,
            (h.off_task_secs + 30) / 60
        );
        text(
            hdc,
            &stats,
            RECT {
                left,
                top: px(62),
                right,
                bottom: px(84),
            },
            11,
            FW_NORMAL,
            MUTED,
            DT_LEFT,
        );
    }

    unsafe fn paint_banner(hdc: HDC, rc: &RECT) {
        let Some(b) = state().banner.clone() else {
            return;
        };
        let accent = match b.tone {
            Tone::Good => GREEN,
            Tone::Warn => AMBER,
            Tone::Bad => RED,
        };
        fill(
            hdc,
            &RECT {
                left: 0,
                top: 0,
                right: px(ACCENT_W),
                bottom: rc.bottom,
            },
            accent,
        );

        let (left, right) = (px(18), rc.right - px(16));
        if b.body.is_empty() {
            text(
                hdc,
                &b.title,
                RECT {
                    left,
                    top: 0,
                    right,
                    bottom: rc.bottom,
                },
                14,
                FW_SEMIBOLD,
                TEXT,
                DT_LEFT | DT_VCENTER,
            );
        } else {
            text(
                hdc,
                &b.title,
                RECT {
                    left,
                    top: px(9),
                    right,
                    bottom: px(32),
                },
                14,
                FW_SEMIBOLD,
                TEXT,
                DT_LEFT,
            );
            text(
                hdc,
                &b.body,
                RECT {
                    left,
                    top: px(32),
                    right,
                    bottom: px(54),
                },
                12,
                FW_NORMAL,
                MUTED,
                DT_LEFT,
            );
        }
    }

    unsafe fn fill(hdc: HDC, rc: &RECT, color: u32) {
        let brush = CreateSolidBrush(color);
        FillRect(hdc, rc, brush);
        DeleteObject(brush as _);
    }

    /// Draw one line of Segoe UI text, ellipsised to fit `rc`.
    unsafe fn text(
        hdc: HDC,
        s: &str,
        mut rc: RECT,
        size: i32,
        weight: i32,
        color: u32,
        flags: u32,
    ) {
        let face = wide("Segoe UI");
        let font = CreateFontW(
            -px(size),
            0,
            0,
            0,
            weight,
            0,
            0,
            0,
            DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS,
            CLIP_DEFAULT_PRECIS,
            CLEARTYPE_QUALITY,
            DEFAULT_PITCH | FF_DONTCARE,
            face.as_ptr(),
        );
        let old = SelectObject(hdc, font as _);
        SetTextColor(hdc, color);
        let w = wide(s);
        DrawTextW(
            hdc,
            w.as_ptr(),
            -1,
            &mut rc,
            flags | DT_SINGLELINE | DT_NOPREFIX | DT_END_ELLIPSIS,
        );
        SelectObject(hdc, old);
        DeleteObject(font as _);
    }
}
