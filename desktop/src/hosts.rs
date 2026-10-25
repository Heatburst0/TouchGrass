// Block sites during focus by null-routing them in the OS hosts file.
// Needs admin/root (writing the hosts file is privileged) — that's expected.
use anyhow::{Context, Result};
use std::fs;
use std::path::PathBuf;

const START: &str = "# TOUCHGRASS-START";
const END: &str = "# TOUCHGRASS-END";

fn hosts_path() -> PathBuf {
    #[cfg(windows)]
    {
        PathBuf::from(std::env::var("SystemRoot").unwrap_or_else(|_| "C:\\Windows".into()))
            .join("System32\\drivers\\etc\\hosts")
    }
    #[cfg(not(windows))]
    {
        PathBuf::from("/etc/hosts")
    }
}

/// Replace our marked block with fresh entries (idempotent).
pub fn apply(sites: &[String]) -> Result<()> {
    let path = hosts_path();
    let out = with_block(&fs::read_to_string(&path).unwrap_or_default(), sites);
    fs::write(&path, out).with_context(|| {
        format!(
            "couldn't write {} — run the agent as Administrator",
            path.display()
        )
    })?;
    flush_dns();
    Ok(())
}

/// Replace our block while preserving the user's hosts text verbatim.
fn with_block(existing: &str, sites: &[String]) -> String {
    let mut out = without_block(existing);
    if !sites.is_empty() {
        out.push('\n');
        out.push_str(START);
        out.push('\n');
        for s in sites {
            let d = s
                .trim()
                .trim_start_matches("https://")
                .trim_start_matches("http://")
                .trim_start_matches("www.");
            if !d.is_empty() {
                out.push_str(&format!("0.0.0.0 {d}\n0.0.0.0 www.{d}\n"));
            }
        }
        out.push_str(END);
        out.push('\n');
    }
    out
}

/// Remove our block (session end / cleanup).
pub fn clear() {
    let path = hosts_path();
    if let Ok(cur) = fs::read_to_string(&path) {
        let cleaned = without_block(&cur);
        if cleaned != cur {
            let _ = fs::write(&path, cleaned);
            flush_dns();
        }
    }
}

/// Remove our marked block and its leading separator, retaining user line endings.
fn without_block(content: &str) -> String {
    let mut out = String::new();
    let mut skip = false;
    for line in content.split_inclusive('\n') {
        let t = line.trim();
        if t == START {
            if out.ends_with('\n') {
                out.pop(); // the separator added by with_block belongs to our block
            }
            skip = true;
            continue;
        }
        if t == END {
            skip = false;
            continue;
        }
        if !skip {
            out.push_str(line);
        }
    }
    out
}

fn flush_dns() {
    #[cfg(windows)]
    {
        let _ = std::process::Command::new("ipconfig")
            .arg("/flushdns")
            .output();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn apply_then_clear_preserves_original_text() {
        let sites = vec!["example.com".to_string()];
        for original in [
            "",
            "127.0.0.1 localhost",
            "# user\n127.0.0.1 localhost\n",
            "# user\r\n127.0.0.1 localhost\r\n\r\n",
        ] {
            assert_eq!(without_block(&with_block(original, &sites)), original);
        }
    }

    #[test]
    fn user_lines_are_preserved() {
        let original = "# custom hosts\n192.0.2.1 intranet\n";
        let blocked = with_block(original, &["example.com".into()]);
        assert!(blocked.starts_with(original));
        let with_user_suffix = format!("{blocked}192.0.2.2 another-host\n");
        assert_eq!(
            without_block(&with_user_suffix),
            format!("{original}192.0.2.2 another-host\n")
        );
    }

    #[test]
    fn apply_is_idempotent_and_replaces_old_sites() {
        let sites = vec!["example.com".into()];
        let once = with_block("127.0.0.1 localhost\n", &sites);
        assert_eq!(with_block(&once, &sites), once);
        assert_eq!(once.matches(START).count(), 1);
        let replaced = with_block(&once, &["other.test".into()]);
        assert!(!replaced.contains("example.com"));
        assert!(replaced.contains("0.0.0.0 other.test\n"));
    }

    #[test]
    fn empty_sites_leave_no_block() {
        let original = "127.0.0.1 localhost\n";
        assert_eq!(with_block(original, &[]), original);
        assert_eq!(
            with_block(&with_block(original, &["example.com".into()]), &[]),
            original
        );
    }

    #[test]
    fn hosts_include_bare_and_www_variants() {
        for site in [
            "example.com",
            " https://www.example.com ",
            "http://example.com",
            "www.example.com",
        ] {
            let blocked = with_block("", &[site.into(), "".into()]);
            assert!(blocked.contains("0.0.0.0 example.com\n0.0.0.0 www.example.com\n"));
            assert!(!blocked.contains("www.www."));
            assert_eq!(blocked.matches("0.0.0.0").count(), 2);
        }
    }
}
