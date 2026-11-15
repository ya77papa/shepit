//! Прототип сервера доставки Шепіт.
//!
//! | Метод | Шлях                    | Підпис | Що робить |
//! |-------|-------------------------|--------|-----------|
//! | GET   | /v0/trust-root          | —      | корінь довіри для сертифікатів відправника |
//! | PUT   | /v0/keys                | так    | опублікувати свої ключі (тільки власник ключа) |
//! | GET   | /v0/keys/{identity}     | —      | пакет ключів людини (забирає 1 одноразовий) |
//! | GET   | /v0/certificate         | так    | сертифікат відправника на 24 год |
//! | POST  | /v0/send                | —      | покласти конверт у скриньку (sealed sender: відправник невідомий) |
//! | GET   | /v0/mailbox/{id}        | так    | забрати конверти (тільки власник скриньки) |
//!
//! Сервер бачить: хто отримує (скринька) і розмір. НЕ бачить: текст і відправника.
//! Етап 0: черги в пам'яті; ключі сертифікатів — у $SHEPIT_DATA/issuer.json.

use std::{
    collections::HashMap,
    net::SocketAddr,
    path::PathBuf,
    sync::{Arc, Mutex},
};

use axum::{
    body::Bytes,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    routing::{get, post, put},
    Json, Router,
};
use shepit_core::{
    auth::{now_ms, Auth, Issuer, HEADER, MAX_SKEW_MS},
    wire::{Envelope, KeyUpload, MailboxId, PreKeyBundle},
};

#[derive(Default)]
struct Inner {
    mailboxes: HashMap<MailboxId, Vec<Envelope>>,
    keys: HashMap<String, KeyUpload>,
    /// Використані nonce → час. Захист від повторного відтворення підписаних запитів.
    nonces: HashMap<String, u64>,
}

struct App {
    inner: Mutex<Inner>,
    issuer: Issuer,
}

type Shared = Arc<App>;

const MAX_PAYLOAD_HEX: usize = 2 * 64 * 1024;
const MAX_QUEUE: usize = 1000;
const MAX_ONE_TIME: usize = 100;

/// Перевірити підпис запиту і повернути ідентичність того, хто його підписав.
fn authenticate(app: &App, headers: &HeaderMap, purpose: &str, body: &[u8]) -> Result<String, StatusCode> {
    let raw = headers.get(HEADER).and_then(|v| v.to_str().ok()).ok_or(StatusCode::UNAUTHORIZED)?;
    let auth = Auth::parse(raw).map_err(|_| StatusCode::UNAUTHORIZED)?;
    let now = now_ms();
    auth.verify(purpose, body, now).map_err(|_| StatusCode::UNAUTHORIZED)?;
    let mut inner = app.inner.lock().unwrap();
    inner.nonces.retain(|_, t| now.saturating_sub(*t) <= 2 * MAX_SKEW_MS);
    if inner.nonces.insert(auth.nonce.clone(), now).is_some() {
        return Err(StatusCode::UNAUTHORIZED);
    }
    Ok(auth.identity)
}

async fn trust_root(State(app): State<Shared>) -> String {
    app.issuer.trust_root_hex()
}

async fn put_keys(State(app): State<Shared>, headers: HeaderMap, body: Bytes) -> StatusCode {
    let who = match authenticate(&app, &headers, "PUT /v0/keys", &body) {
        Ok(w) => w,
        Err(s) => return s,
    };
    let Ok(up) = serde_json::from_slice::<KeyUpload>(&body) else {
        return StatusCode::BAD_REQUEST;
    };
    // Опублікувати ключі можна лише для себе: identity у тілі = той, хто підписав.
    if up.identity != who {
        return StatusCode::FORBIDDEN;
    }
    if up.one_time.len() > MAX_ONE_TIME {
        return StatusCode::BAD_REQUEST;
    }
    app.inner.lock().unwrap().keys.insert(up.identity.clone(), up);
    StatusCode::NO_CONTENT
}

async fn get_keys(State(app): State<Shared>, Path(identity): Path<String>) -> Result<Json<PreKeyBundle>, StatusCode> {
    let mut inner = app.inner.lock().unwrap();
    let up = inner.keys.get_mut(&identity).ok_or(StatusCode::NOT_FOUND)?;
    Ok(Json(up.take_bundle()))
}

async fn certificate(State(app): State<Shared>, headers: HeaderMap) -> Result<String, StatusCode> {
    let who = authenticate(&app, &headers, "GET /v0/certificate", b"")?;
    let cert = app.issuer.issue(&who, now_ms()).map_err(|_| StatusCode::BAD_REQUEST)?;
    Ok(hex::encode(cert))
}

async fn send(State(app): State<Shared>, Json(env): Json<Envelope>) -> StatusCode {
    if env.payload.len() > MAX_PAYLOAD_HEX {
        return StatusCode::PAYLOAD_TOO_LARGE;
    }
    let mut inner = app.inner.lock().unwrap();
    let queue = inner.mailboxes.entry(env.to).or_default();
    if queue.len() >= MAX_QUEUE {
        return StatusCode::TOO_MANY_REQUESTS;
    }
    queue.push(env);
    StatusCode::ACCEPTED
}

async fn fetch(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> Result<Json<Vec<Envelope>>, StatusCode> {
    let who = authenticate(&app, &headers, &format!("GET /v0/mailbox/{id}"), b"")?;
    let mailbox = MailboxId::from_hex(&id).ok_or(StatusCode::BAD_REQUEST)?;
    let who_bytes = hex::decode(&who).map_err(|_| StatusCode::BAD_REQUEST)?;
    // Забрати пошту може лише власник скриньки.
    if MailboxId::for_identity(&who_bytes) != mailbox {
        return Err(StatusCode::FORBIDDEN);
    }
    Ok(Json(app.inner.lock().unwrap().mailboxes.remove(&mailbox).unwrap_or_default()))
}

fn load_issuer() -> Issuer {
    let dir = PathBuf::from(std::env::var("SHEPIT_DATA").unwrap_or_else(|_| "data".into()));
    let path = dir.join("issuer.json");
    if let Ok(raw) = std::fs::read(&path) {
        return serde_json::from_slice(&raw).expect("issuer.json пошкоджений");
    }
    let issuer = Issuer::generate();
    std::fs::create_dir_all(&dir).expect("create data dir");
    write_private(&path, &serde_json::to_vec_pretty(&issuer).unwrap()).expect("write issuer.json");
    println!("Створено нові ключі сервера: {}", path.display());
    issuer
}

/// Записати файл, доступний лише власнику (0600 на Linux).
fn write_private(path: &std::path::Path, data: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut opts, 0o600);
    opts.open(path)?.write_all(data)
}

fn app(issuer: Issuer) -> Router {
    let shared = Arc::new(App { inner: Mutex::default(), issuer });
    Router::new()
        .route("/health", get(|| async { "ok" }))
        .route("/v0/trust-root", get(trust_root))
        .route("/v0/keys", put(put_keys))
        .route("/v0/keys/{identity}", get(get_keys))
        .route("/v0/certificate", get(certificate))
        .route("/v0/send", post(send))
        .route("/v0/mailbox/{id}", get(fetch))
        .with_state(shared)
}

/// Ctrl+C або SIGTERM (його надсилає `docker stop`).
async fn shutdown_signal() {
    let ctrl_c = async { tokio::signal::ctrl_c().await.ok(); };
    #[cfg(unix)]
    let term = async {
        if let Ok(mut s) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            s.recv().await;
        }
    };
    #[cfg(not(unix))]
    let term = std::future::pending::<()>();
    tokio::select! { _ = ctrl_c => {}, _ = term => {} }
    println!("shepit-server: зупинка");
}

#[tokio::main]
async fn main() {
    let addr: SocketAddr = std::env::var("SHEPIT_LISTEN")
        .unwrap_or_else(|_| "0.0.0.0:8443".into())
        .parse()
        .expect("SHEPIT_LISTEN: invalid address");
    let issuer = load_issuer();
    println!("Корінь довіри: {}", issuer.trust_root_hex());
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    println!("shepit-server listening on {addr}");
    axum::serve(listener, app(issuer))
        .with_graceful_shutdown(shutdown_signal())
        .await
        .unwrap();
}
