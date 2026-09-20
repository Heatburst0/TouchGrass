use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

/// Minimal blocking Supabase client: GoTrue auth (email OTP) + PostgREST writes.
pub struct Supabase {
    http: reqwest::blocking::Client,
    url: String,
    anon_key: String,
    access_token: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct Session {
    pub access_token: String,
    pub refresh_token: String,
}

#[derive(Debug, Serialize)]
pub struct RemoteFocusSession {
    pub id: String,
    pub device_id: String,
    pub started_at: String, // RFC3339
    pub ended_at: String,   // RFC3339
    pub planned_focus_min: i64,
    pub focused_min: i64,
    pub active_min: i64,
    pub cycles: i64,
    pub violations: i64,
    pub strict: bool,
    pub outcome: String,
    pub config: Value,
}

#[derive(Debug, Serialize)]
pub struct RemoteDeviceEvent {
    pub device_id: String,
    pub session_id: Option<String>,
    pub kind: String,
    pub occurred_at: String, // RFC3339
    pub payload: Value,
}

#[derive(Debug, Deserialize)]
#[allow(dead_code)] // wire model: some columns are decoded but not all are read yet
pub struct RemoteSchedule {
    pub id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub recurrence: Value,
    #[serde(default)]
    pub start_local_time: String,
    #[serde(default)]
    pub focus_block_min: i64,
    #[serde(default)]
    pub break_min: i64,
    #[serde(default)]
    pub cycles: i64,
    #[serde(default)]
    pub target_platforms: Vec<String>,
    #[serde(default)]
    pub enabled: bool,
}

impl Supabase {
    pub fn new(url: &str, anon_key: &str) -> Self {
        Supabase {
            http: reqwest::blocking::Client::new(),
            url: url.trim_end_matches('/').to_string(),
            anon_key: anon_key.to_string(),
            access_token: None,
        }
    }

    pub fn set_access(&mut self, token: String) {
        self.access_token = Some(token);
    }

    // ---- auth (email OTP: no browser redirect) ----

    pub fn send_otp(&self, email: &str) -> Result<()> {
        let resp = self
            .http
            .post(format!("{}/auth/v1/otp", self.url))
            .header("apikey", &self.anon_key)
            .json(&json!({ "email": email, "create_user": true }))
            .send()?;
        expect_ok(resp, "send OTP")
    }

    pub fn verify_otp(&self, email: &str, token: &str) -> Result<Session> {
        let resp = self
            .http
            .post(format!("{}/auth/v1/verify", self.url))
            .header("apikey", &self.anon_key)
            .json(&json!({ "type": "email", "email": email, "token": token }))
            .send()?;
        json_ok(resp, "verify OTP")
    }

    /// Verify using the token embedded in the default sign-in link (works without
    /// a custom email template / SMTP).
    pub fn verify_token_hash(&self, token_hash: &str) -> Result<Session> {
        let resp = self
            .http
            .post(format!("{}/auth/v1/verify", self.url))
            .header("apikey", &self.anon_key)
            .json(&json!({ "type": "magiclink", "token_hash": token_hash }))
            .send()?;
        json_ok(resp, "verify link")
    }

    pub fn refresh(&self, refresh_token: &str) -> Result<Session> {
        let resp = self
            .http
            .post(format!("{}/auth/v1/token?grant_type=refresh_token", self.url))
            .header("apikey", &self.anon_key)
            .json(&json!({ "refresh_token": refresh_token }))
            .send()?;
        json_ok(resp, "refresh session")
    }

    // ---- PostgREST ----

    fn access(&self) -> Result<&str> {
        self.access_token.as_deref().context("not authenticated")
    }

    fn insert<T: Serialize>(&self, table: &str, body: &T) -> Result<()> {
        let resp = self
            .http
            .post(format!("{}/rest/v1/{}", self.url, table))
            .header("apikey", &self.anon_key)
            .header("Authorization", format!("Bearer {}", self.access()?))
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates")
            .json(body)
            .send()?;
        expect_ok(resp, &format!("insert into {}", table))
    }

    pub fn upsert_device(&self, id: &str, name: &str) -> Result<()> {
        self.insert("devices", &json!({ "id": id, "platform": "DESKTOP", "name": name }))
    }

    pub fn insert_focus_session(&self, s: &RemoteFocusSession) -> Result<()> {
        self.insert("focus_sessions", s)
    }

    pub fn insert_device_event(&self, e: &RemoteDeviceEvent) -> Result<()> {
        self.insert("device_events", e)
    }

    pub fn list_schedules(&self) -> Result<Vec<RemoteSchedule>> {
        let resp = self
            .http
            .get(format!("{}/rest/v1/focus_schedules?select=*", self.url))
            .header("apikey", &self.anon_key)
            .header("Authorization", format!("Bearer {}", self.access()?))
            .send()?;
        json_ok(resp, "list schedules")
    }
}

fn expect_ok(resp: reqwest::blocking::Response, what: &str) -> Result<()> {
    if resp.status().is_success() {
        Ok(())
    } else {
        let code = resp.status();
        Err(anyhow!("{} failed ({}): {}", what, code, resp.text().unwrap_or_default()))
    }
}

fn json_ok<T: for<'de> Deserialize<'de>>(resp: reqwest::blocking::Response, what: &str) -> Result<T> {
    if resp.status().is_success() {
        resp.json::<T>().with_context(|| format!("decode {} response", what))
    } else {
        let code = resp.status();
        Err(anyhow!("{} failed ({}): {}", what, code, resp.text().unwrap_or_default()))
    }
}
