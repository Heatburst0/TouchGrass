//! Supabase Realtime wakeups; REST polling remains the source of truth.
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tungstenite::{stream::MaybeTlsStream, Message, WebSocket};

const TOPIC: &str = "realtime:touchgrass-agent";
const HEARTBEAT_SECS: u64 = 25;

/// A table invalidation, coalesced until the consumer next wakes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Event {
    ActiveSession,
    WorkTargets,
    Schedules,
    Policy,
}

#[derive(Default)]
struct State {
    events: Mutex<Vec<Event>>,
    wake: Condvar,
    token: Mutex<String>,
    connected: AtomicBool,
}

fn state() -> &'static State {
    static STATE: OnceLock<State> = OnceLock::new();
    STATE.get_or_init(State::default)
}

/// Start one reconnecting worker. Failure leaves the normal polling fallback alive.
pub fn start(url: &str, anon_key: &str, token: &str) {
    set_access_token(token);
    static STARTED: OnceLock<()> = OnceLock::new();
    STARTED.get_or_init(|| {
        let url = websocket_url(url, anon_key);
        if let Ok(url) = url {
            if std::thread::Builder::new()
                .name("supabase-realtime".into())
                .spawn(move || worker(&url))
                .is_err()
            {
                eprintln!("realtime: could not start worker; using polling");
            }
        } else {
            eprintln!("realtime: invalid Supabase URL; using polling");
        }
    });
}

/// Supply the latest JWT without waiting on network I/O.
pub fn set_access_token(token: &str) {
    *state().token.lock().unwrap_or_else(|e| e.into_inner()) = token.to_owned();
}

/// True only after the server has acknowledged the channel join.
pub fn is_connected() -> bool {
    state().connected.load(Ordering::Relaxed)
}

/// Wait up to `timeout` for invalidations and drain the coalesced batch.
pub fn wait(timeout: Duration) -> Vec<Event> {
    state().wait(timeout)
}

/// Return invalidations deferred by a running session to the watch loop.
pub fn publish(events: impl IntoIterator<Item = Event>) {
    state().publish(events);
}

impl State {
    fn wait(&self, timeout: Duration) -> Vec<Event> {
        let events = self.events.lock().unwrap_or_else(|e| e.into_inner());
        let (mut events, _) = self
            .wake
            .wait_timeout_while(events, timeout, |events| events.is_empty())
            .unwrap_or_else(|e| e.into_inner());
        std::mem::take(&mut *events)
    }

    fn publish(&self, events: impl IntoIterator<Item = Event>) {
        let mut pending = self.events.lock().unwrap_or_else(|e| e.into_inner());
        for event in events {
            if !pending.contains(&event) {
                pending.push(event);
            }
        }
        if !pending.is_empty() {
            self.wake.notify_all();
        }
    }
}

fn websocket_url(base: &str, anon_key: &str) -> Result<String> {
    let mut url = reqwest::Url::parse(base).context("invalid Supabase URL")?;
    let scheme = match url.scheme() {
        "https" => "wss",
        "http" => "ws",
        _ => bail!("unsupported Supabase URL scheme"),
    };
    url.set_scheme(scheme)
        .map_err(|_| anyhow::anyhow!("invalid scheme"))?;
    url.set_path(&format!(
        "{}/realtime/v1/websocket",
        url.path().trim_end_matches('/')
    ));
    url.set_query(None);
    url.set_fragment(None);
    url.query_pairs_mut()
        .append_pair("apikey", anon_key)
        .append_pair("vsn", "1.0.0");
    Ok(url.to_string())
}

fn join(token: &str) -> Value {
    json!({
        "topic": TOPIC, "event": "phx_join", "ref": "1", "join_ref": "1",
        "payload": {
            "config": {
                "broadcast": {"self": false}, "presence": {"key": ""},
                "postgres_changes": (["active_sessions", "work_targets", "focus_schedules", "focus_policy"]
                    .map(|table| json!({"event": "*", "schema": "public", "table": table})))
            },
            "access_token": token
        }
    })
}

/// Assign monotonically increasing refs to heartbeat and token messages.
fn message(event: &str, payload: Value, next_ref: &mut u64) -> Value {
    let reference = next_ref.to_string();
    *next_ref = next_ref.wrapping_add(1);
    json!({"topic": if event == "heartbeat" { "phoenix" } else { TOPIC },
        "event": event, "payload": payload, "ref": reference})
}

fn parse_event(frame: &Value) -> Option<Event> {
    if frame["event"] != "postgres_changes" || frame["topic"] != TOPIC {
        return None;
    }
    match frame["payload"]["data"]["table"].as_str()? {
        "active_sessions" => Some(Event::ActiveSession),
        "work_targets" => Some(Event::WorkTargets),
        "focus_schedules" => Some(Event::Schedules),
        "focus_policy" => Some(Event::Policy),
        _ => None,
    }
}

fn worker(url: &str) {
    let mut backoff = 1;
    loop {
        let started = Instant::now();
        // Never print transport errors: they may contain the URL or credentials.
        let _ = connected_loop(url);
        state().connected.store(false, Ordering::Relaxed);
        if started.elapsed() >= Duration::from_secs(HEARTBEAT_SECS * 2) {
            backoff = 1;
        }
        eprintln!("realtime: disconnected; retrying in {backoff}s (polling fallback)");
        std::thread::sleep(Duration::from_secs(backoff));
        backoff = (backoff * 2).min(60);
    }
}

fn set_timeouts(socket: &mut WebSocket<MaybeTlsStream<TcpStream>>) -> Result<()> {
    let stream = match socket.get_mut() {
        MaybeTlsStream::Plain(stream) => stream,
        MaybeTlsStream::Rustls(stream) => &mut stream.sock,
        _ => bail!("unsupported TLS stream"),
    };
    stream.set_read_timeout(Some(Duration::from_millis(250)))?;
    stream.set_write_timeout(Some(Duration::from_secs(5)))?;
    Ok(())
}

fn connected_loop(url: &str) -> Result<()> {
    let (mut socket, _) = tungstenite::connect(url)?;
    set_timeouts(&mut socket)?;
    let mut token = state()
        .token
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    socket.send(Message::Text(join(&token).to_string().into()))?;
    let mut next_ref = 2;
    let mut heartbeat = Instant::now();
    let mut received = Instant::now();
    loop {
        if received.elapsed() >= Duration::from_secs(60) {
            bail!("Realtime response timed out");
        }
        let latest = state()
            .token
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        if latest != token {
            let update = message(
                "access_token",
                json!({"access_token": latest}),
                &mut next_ref,
            );
            socket.send(Message::Text(update.to_string().into()))?;
            token = latest;
        }
        if heartbeat.elapsed() >= Duration::from_secs(HEARTBEAT_SECS) {
            let frame = message("heartbeat", json!({}), &mut next_ref);
            socket.send(Message::Text(frame.to_string().into()))?;
            heartbeat = Instant::now();
        }
        match socket.read() {
            Ok(Message::Text(text)) => {
                received = Instant::now();
                if let Ok(frame) = serde_json::from_str::<Value>(&text) {
                    if frame["event"] == "phx_reply" {
                        if frame["payload"]["status"] == "error" {
                            eprintln!("realtime: phx_reply status error");
                            bail!("channel request rejected");
                        }
                        if frame["topic"] == TOPIC
                            && frame["ref"] == "1"
                            && frame["payload"]["status"] == "ok"
                        {
                            if !is_connected() {
                                eprintln!("realtime: connected");
                            }
                            state().connected.store(true, Ordering::Relaxed);
                            // Reconcile changes missed while disconnected, once per join.
                            publish([
                                Event::ActiveSession,
                                Event::WorkTargets,
                                Event::Schedules,
                                Event::Policy,
                            ]);
                        }
                    } else if frame["event"] == "system" && frame["payload"]["status"] == "error" {
                        eprintln!("realtime: system error");
                        bail!("channel system error");
                    } else if frame["event"] == "phx_error" || frame["event"] == "phx_close" {
                        bail!("channel closed");
                    }
                    if let Some(event) = parse_event(&frame) {
                        publish([event]);
                    }
                }
            }
            Ok(Message::Close(_)) => bail!("websocket closed"),
            Ok(Message::Ping(_)) => socket.flush()?,
            Ok(_) => {}
            Err(tungstenite::Error::Io(e))
                if matches!(
                    e.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) => {}
            Err(e) => return Err(e.into()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wait_wakes_early_coalesces_and_drains_pending_events() {
        let state = std::sync::Arc::new(State::default());
        let consumer = state.clone();
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let (result_tx, result_rx) = std::sync::mpsc::channel();
        let thread = std::thread::spawn(move || {
            ready_tx.send(()).unwrap();
            result_tx
                .send(consumer.wait(Duration::from_secs(10)))
                .unwrap();
        });
        ready_rx.recv().unwrap();
        state.publish([Event::Policy, Event::Policy, Event::ActiveSession]);
        assert_eq!(
            result_rx.recv_timeout(Duration::from_secs(2)).unwrap(),
            vec![Event::Policy, Event::ActiveSession]
        );
        thread.join().unwrap();
        assert!(state.wait(Duration::ZERO).is_empty());
        state.publish([Event::WorkTargets]);
        assert_eq!(state.wait(Duration::ZERO), vec![Event::WorkTargets]);
    }

    #[test]
    fn join_subscribes_to_all_tables_with_jwt() {
        let frame = join("user-jwt");
        assert_eq!(frame["topic"], TOPIC);
        assert_eq!(frame["event"], "phx_join");
        assert_eq!(frame["ref"], "1");
        assert_eq!(frame["join_ref"], "1");
        assert_eq!(frame["payload"]["access_token"], "user-jwt");
        assert_eq!(
            frame["payload"]["config"],
            json!({
                "broadcast": {"self": false}, "presence": {"key": ""},
                "postgres_changes": [
                    {"event":"*", "schema":"public", "table":"active_sessions"},
                    {"event":"*", "schema":"public", "table":"work_targets"},
                    {"event":"*", "schema":"public", "table":"focus_schedules"},
                    {"event":"*", "schema":"public", "table":"focus_policy"}
                ]
            })
        );
    }

    #[test]
    fn heartbeat_and_token_updates_share_increasing_refs() {
        let mut reference = 2;
        assert_eq!(
            message("heartbeat", json!({}), &mut reference),
            json!({
                "topic":"phoenix", "event":"heartbeat", "payload":{}, "ref":"2"
            })
        );
        assert_eq!(
            message(
                "access_token",
                json!({"access_token":"new-jwt"}),
                &mut reference
            ),
            json!({
                "topic":TOPIC, "event":"access_token", "payload":{"access_token":"new-jwt"}, "ref":"3"
            })
        );
        assert_eq!(message("heartbeat", json!({}), &mut reference)["ref"], "4");
    }

    #[test]
    fn parses_only_table_invalidations_on_our_channel() {
        for (table, expected) in [
            ("active_sessions", Event::ActiveSession),
            ("work_targets", Event::WorkTargets),
            ("focus_schedules", Event::Schedules),
            ("focus_policy", Event::Policy),
        ] {
            let frame = json!({"topic":TOPIC, "event":"postgres_changes", "payload":{"data":{"table":table}}});
            assert_eq!(parse_event(&frame), Some(expected));
        }
        for frame in [
            json!({"event":"phx_reply", "payload":{"status":"ok"}}),
            json!({"event":"heartbeat", "payload":{}}),
            json!({"event":"system"}),
            json!({"topic":TOPIC,"event":"postgres_changes","payload":{"data":{"table":"other"}}}),
            json!({"topic":TOPIC,"event":"postgres_changes","payload":{}}),
        ] {
            assert_eq!(parse_event(&frame), None);
        }
        // A control message must remain ignored even if it carries table data.
        for event in [
            "phx_reply",
            "heartbeat",
            "system",
            "presence_state",
            "presence_diff",
        ] {
            let frame = json!({"topic":TOPIC, "event":event,
                "payload":{"status":"ok", "data":{"table":"active_sessions"}}});
            assert_eq!(parse_event(&frame), None);
        }
        assert_eq!(
            parse_event(
                &json!({"topic":"another-channel", "event":"postgres_changes",
            "payload":{"data":{"table":"active_sessions"}}})
            ),
            None
        );
    }

    #[test]
    fn builds_websocket_urls_and_encodes_query_values() {
        for base in [
            "https://example.supabase.co",
            "https://example.supabase.co/",
        ] {
            assert_eq!(
                websocket_url(base, "anon").unwrap(),
                "wss://example.supabase.co/realtime/v1/websocket?apikey=anon&vsn=1.0.0"
            );
        }
        let url =
            reqwest::Url::parse(&websocket_url("http://localhost:54321/", "a&b").unwrap()).unwrap();
        assert_eq!(url.scheme(), "ws");
        assert_eq!(url.port(), Some(54321));
        assert_eq!(
            url.query_pairs().collect::<Vec<_>>(),
            vec![
                ("apikey".into(), "a&b".into()),
                ("vsn".into(), "1.0.0".into())
            ]
        );
        assert!(websocket_url("not a URL", "anon").is_err());
    }
}
