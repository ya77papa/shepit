//! Прототип сервера доставки Шепіт.
//!
//! | Метод | Шлях                    | Підпис | Що робить |
//! |-------|-------------------------|--------|-----------|
//! | GET   | /v0/trust-root          | —      | корінь довіри для сертифікатів відправника |
//! | PUT   | /v0/keys                | так    | опублікувати свої ключі (тільки власник ключа) |
//! | GET   | /v0/keys/status         | так    | скільки моїх одноразових ключів лишилось (404 — ключів немає) |
//! | GET   | /v0/keys/{identity}     | —      | пакет ключів людини (забирає 1 одноразовий) |
//! | GET   | /v0/certificate         | так    | сертифікат відправника на 24 год |
//! | POST  | /v0/send                | —      | покласти конверт у скриньку (sealed sender: відправник невідомий) |
//! | GET   | /v0/mailbox/{id}        | так    | забрати конверти (тільки власник скриньки) |
//!
//! Також роздає веб-клієнт зі `$SHEPIT_WEB` (типово `./web`, якщо тека існує).
//!
//! Сервер бачить: хто отримує (скринька) і розмір. НЕ бачить: текст і відправника.
//! Дані — у $SHEPIT_DATA: issuer.json (ключі сертифікатів) і shepit.db (SQLite: ключі, черги).

mod db;

use std::{
    collections::HashMap,
    net::SocketAddr,
    path::PathBuf,
    sync::{Arc, Mutex},
};

use axum::{
    body::Bytes,
    extract::{Path, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    routing::{get, post, put},
    Json, Router,
};
use shepit_core::{
    auth::{now_ms, Auth, Issuer, HEADER, MAX_SKEW_MS},
    wire::{Envelope, KeyUpload, MailboxId, PreKeyBundle},
};

struct App {
    db: db::Db,
    /// Використані nonce → час. Захист від повторного відтворення підписаних запитів.
    /// У пам'яті: після перезапуску старі запити все одно відсіче вікно часу.
    nonces: Mutex<HashMap<String, u64>>,
    issuer: Issuer,
}

fn internal(e: rusqlite::Error) -> StatusCode {
    eprintln!("db: {e}");
    StatusCode::INTERNAL_SERVER_ERROR
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
    let mut nonces = app.nonces.lock().unwrap();
    nonces.retain(|_, t| now.saturating_sub(*t) <= 2 * MAX_SKEW_MS);
    if nonces.insert(auth.nonce.clone(), now).is_some() {
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
    match app.db.put_keys(&up) {
        Ok(()) => StatusCode::NO_CONTENT,
        Err(e) => internal(e),
    }
}

async fn get_keys(State(app): State<Shared>, Path(identity): Path<String>) -> Result<Json<PreKeyBundle>, StatusCode> {
    app.db.take_bundle(&identity).map_err(internal)?.map(Json).ok_or(StatusCode::NOT_FOUND)
}

#[derive(serde::Serialize)]
struct KeyStatus {
    one_time: usize,
}

async fn key_status(State(app): State<Shared>, headers: HeaderMap) -> Result<Json<KeyStatus>, StatusCode> {
    let who = authenticate(&app, &headers, "GET /v0/keys/status", b"")?;
    let n = app.db.one_time_left(&who).map_err(internal)?.ok_or(StatusCode::NOT_FOUND)?;
    Ok(Json(KeyStatus { one_time: n }))
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
    match app.db.push(&env, MAX_QUEUE, now_ms()) {
        Ok(true) => StatusCode::ACCEPTED,
        Ok(false) => StatusCode::TOO_MANY_REQUESTS,
        Err(e) => internal(e),
    }
}

async fn fetch(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> Result<Json<Vec<Envelope>>, StatusCode> {
    let who = authenticate(&app, &headers, &format!("GET /v0/mailbox/{id}"), b"")?;
    let mailbox = MailboxId::from_hex(&id).ok_or(StatusCode::BAD_REQUEST)?;
    let who_bytes = hex::decode(&who).map_err(|_| StatusCode::BAD_REQUEST)?;
    // Забрати пошту може лише власник скриньки.
    if MailboxId::for_identity(&who_bytes) != mailbox {
        return Err(StatusCode::FORBIDDEN);
    }
    Ok(Json(app.db.drain(&mailbox).map_err(internal)?))
}

fn data_dir() -> PathBuf {
    PathBuf::from(std::env::var("SHEPIT_DATA").unwrap_or_else(|_| "data".into()))
}

fn load_issuer() -> Issuer {
    let dir = data_dir();
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

/// Жорстка політика безпеки контенту: лише власні скрипти і WASM, жодних сторонніх доменів,
/// вбудовування у фрейми заборонене. Головний захист веб-клієнта від XSS.
const CSP: &str = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'; \
    img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; \
    form-action 'self'; frame-ancestors 'none'";

fn web_dir() -> Option<PathBuf> {
    let dir = PathBuf::from(std::env::var("SHEPIT_WEB").unwrap_or_else(|_| "web".into()));
    dir.join("index.html").is_file().then_some(dir)
}

fn app(issuer: Issuer, db: db::Db) -> Router {
    use tower_http::{services::{ServeDir, ServeFile}, set_header::SetResponseHeaderLayer};
    let shared = Arc::new(App { db, nonces: Mutex::default(), issuer });
    // Раз на годину прибирати недоставлені конверти старші за 30 днів.
    let pruner = shared.clone();
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(3600));
        loop {
            tick.tick().await;
            if let Ok(n @ 1..) = pruner.db.prune(now_ms()) {
                println!("Прибрано старих конвертів: {n}");
            }
        }
    });
    let api = Router::new()
        .route("/health", get(|| async { "ok" }))
        .route("/v0/trust-root", get(trust_root))
        .route("/v0/keys", put(put_keys))
        .route("/v0/keys/status", get(key_status))
        .route("/v0/keys/{identity}", get(get_keys))
        .route("/v0/certificate", get(certificate))
        .route("/v0/send", post(send))
        .route("/v0/mailbox/{id}", get(fetch))
        .with_state(shared)
        .layer(SetResponseHeaderLayer::overriding(header::CACHE_CONTROL, HeaderValue::from_static("no-store")));

    let router = match web_dir() {
        Some(dir) => {
            println!("Веб-клієнт: {}", dir.display());
            // Файли в assets/ мають хеш в імені → кешуються назавжди; index.html — ніколи.
            let assets = Router::new()
                .fallback_service(ServeDir::new(dir.join("assets")))
                .layer(SetResponseHeaderLayer::overriding(
                    header::CACHE_CONTROL,
                    HeaderValue::from_static("public, max-age=31536000, immutable"),
                ));
            let index = ServeFile::new(dir.join("index.html"));
            api.nest("/assets", assets)
                .route_service("/", index.clone())
                .fallback_service(ServeDir::new(&dir).fallback(index))
        }
        None => api,
    };
    router
        // index.html та інше без власного Cache-Control — завжди перевіряти свіжість.
        .layer(SetResponseHeaderLayer::if_not_present(header::CACHE_CONTROL, HeaderValue::from_static("no-cache")))
        .layer(SetResponseHeaderLayer::overriding(header::CONTENT_SECURITY_POLICY, HeaderValue::from_static(CSP)))
        .layer(SetResponseHeaderLayer::overriding(header::X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff")))
        .layer(SetResponseHeaderLayer::overriding(header::REFERRER_POLICY, HeaderValue::from_static("no-referrer")))
        .layer(SetResponseHeaderLayer::overriding(
            header::HeaderName::from_static("cross-origin-opener-policy"),
            HeaderValue::from_static("same-origin"),
        ))
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
    let db = db::Db::open(&data_dir().join("shepit.db")).expect("open shepit.db");
    println!("Корінь довіри: {}", issuer.trust_root_hex());
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    println!("shepit-server listening on {addr}");
    axum::serve(listener, app(issuer, db))
        .with_graceful_shutdown(shutdown_signal())
        .await
        .unwrap();
}
