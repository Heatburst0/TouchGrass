use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// Agent config, persisted at ~/.config/touchgrass/agent.toml. Holds the public
/// Supabase url/anon key plus the signed-in session's refresh token and this
/// device's stable id. `allowed_apps` are substrings matched against the active
/// window's app name to decide what counts as productive.
#[derive(Debug, Serialize, Deserialize)]
pub struct Config {
    pub supabase_url: String,
    pub anon_key: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub refresh_token: Option<String>,
    #[serde(default = "default_allowed_apps")]
    pub allowed_apps: Vec<String>,
    // Local seed/fallback for the server-side focus_policy (phone-managed).
    #[serde(default)]
    pub blocked_apps: Vec<String>,
    #[serde(default)]
    pub force_quit_apps: Vec<String>,
    #[serde(default)]
    pub blocked_sites: Vec<String>,
}

fn default_allowed_apps() -> Vec<String> {
    ["Code", "studio64", "idea", "devenv", "WindowsTerminal", "Terminal", "iTerm"]
        .iter()
        .map(|s| s.to_string())
        .collect()
}

impl Config {
    pub fn path() -> Result<PathBuf> {
        let dir = dirs::config_dir()
            .context("could not resolve a config directory")?
            .join("touchgrass");
        Ok(dir.join("agent.toml"))
    }

    pub fn load() -> Result<Config> {
        let path = Self::path()?;
        let text = fs::read_to_string(&path).with_context(|| {
            format!(
                "no config at {}. Copy config.example.toml there and fill in supabase_url + anon_key.",
                path.display()
            )
        })?;
        toml::from_str(&text).context("failed to parse agent.toml")
    }

    pub fn save(&self) -> Result<()> {
        let path = Self::path()?;
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&path, toml::to_string_pretty(self)?)?;
        Ok(())
    }

    /// Return the stable device id, creating (and persisting) one on first use.
    pub fn device_id_or_new(&mut self) -> Result<String> {
        if let Some(id) = &self.device_id {
            return Ok(id.clone());
        }
        let id = uuid::Uuid::new_v4().to_string();
        self.device_id = Some(id.clone());
        self.save()?;
        Ok(id)
    }
}
