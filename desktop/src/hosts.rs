// Block sites during focus by null-routing them in the OS hosts file.
// Needs admin/root (writing the hosts file is privileged) — that's expected.
use anyhow::{Context, Result};
use std::fs;
use std::path::PathBuf;

const START: &str = "# TOUCHGRASS-START";
const END: &str = "# TOUCHGRASS-END";

fn hosts_path() -> PathBuf {
    #[cfg(windows)]
    { PathBuf::from(std::env::var("SystemRoot").unwrap_or_else(|_| "C:\\Windows".into()))
        .join("System32\\drivers\\etc\\hosts") }
    #[cfg(not(windows))]
    { PathBuf::from("/etc/hosts") }
}

/// Replace our marked block with fresh entries (idempotent).
pub fn apply(sites: &[String]) -> Result<()> {
    let path = hosts_path();
    let mut out = strip_block(&fs::read_to_string(&path).unwrap_or_default());
    if !sites.is_empty() {
        out.push('\n'); out.push_str(START); out.push('\n');
        for s in sites {
            let d = s.trim().trim_start_matches("https://").trim_start_matches("http://").trim_start_matches("www.");
            if !d.is_empty() { out.push_str(&format!("0.0.0.0 {d}\n0.0.0.0 www.{d}\n")); }
        }
        out.push_str(END); out.push('\n');
    }
    fs::write(&path, out).with_context(|| format!("couldn't write {} — run the agent as Administrator", path.display()))?;
    flush_dns();
    Ok(())
}

/// Remove our block (session end / cleanup).
pub fn clear() {
    let path = hosts_path();
    if let Ok(cur) = fs::read_to_string(&path) {
        let cleaned = strip_block(&cur);
        if cleaned != cur { let _ = fs::write(&path, cleaned); flush_dns(); }
    }
}

fn strip_block(content: &str) -> String {
    let mut lines = Vec::new();
    let mut skip = false;
    for line in content.lines() {
        let t = line.trim();
        if t == START { skip = true; continue; }
        if t == END { skip = false; continue; }
        if !skip { lines.push(line); }
    }
    lines.join("\n")
}

fn flush_dns() {
    #[cfg(windows)]
    { let _ = std::process::Command::new("ipconfig").arg("/flushdns").output(); }
}
