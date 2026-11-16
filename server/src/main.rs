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
//! | GET   | /v0/mailbox/{id}        | так    | переглянути конверти (тільки власник скриньки) |
//! | POST  | /v0/mailbox/{id}/ack    | так    | «отримав і зберіг до N» — сервер видаляє |
//! | GET   | /v0/events              | так    | потік подій (SSE): «у скриньці нова пошта» — миттєва доставка |
//! | GET   | /v0/turn                | так    | тимчасовий логін до ретранслятора дзвінків (TURN) |
//! | GET   | /v0/push/key            | —      | публічний ключ VAPID для підписки на сповіщення |
//! | PUT   | /v0/push                | так    | підписати браузер на сповіщення (Web Push) |
//! | DELETE| /v0/push                | так    | відписати |
//!
//! Також роздає веб-клієнт зі `$SHEPIT_WEB` (типово `./web`, якщо тека існує).
//!
//! Сервер бачить: хто отримує (скринька) і розмір. НЕ бачить: текст і відправника.
//! Дані — у $SHEPIT_DATA: issuer.json (ключі сертифікатів) і shepit.db (SQLite: ключі, черги).

mod db;
#[cfg(feature = "push")]
mod push;

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
    wire::{Ack, Envelope, KeyUpload, MailboxId, MailboxItem, PreKeyBundle},
};

struct App {
    db: db::Db,
    /// «Дзвіночки»: підписані на події скриньки клієнти (відкриті потоки /v0/events).
    bells: Mutex<HashMap<MailboxId, tokio::sync::broadcast::Sender<()>>>,
    turn: Option<Turn>,
    /// Сигнал зупинки: відкриті потоки подій мають закритися, інакше сервер не завершиться.
    shutdown: tokio::sync::watch::Receiver<bool>,
    #[cfg(feature = "push")]
    pusher: Arc<push::Pusher>,
    /// Коли востаннє будили скриньку через «нову пошту» (не частіше ніж раз на 30 с).
    #[cfg(feature = "push")]
    last_mail_push: Mutex<HashMap<MailboxId, u64>>,
    /// Використані nonce → час. Захист від повторного відтворення підписаних запитів.
    /// У пам'яті: після перезапуску старі запити все одно відсіче вікно часу.
    nonces: Mutex<HashMap<String, u64>>,
    issuer: Issuer,
}

/// Ретранслятор дзвінків (coturn з `use-auth-secret`): сервер видає тимчасові логіни,
/// пароль = HMAC-SHA1(спільний секрет, логін). Сам ретранслятор бачить лише зашифрований звук.
struct Turn {
    secret: String,
    urls: Vec<String>,
}

const TURN_TTL_S: u64 = 12 * 60 * 60;

fn load_turn() -> Option<Turn> {
    let secret = std::env::var("SHEPIT_TURN_SECRET").ok().filter(|s| !s.is_empty())?;
    let urls: Vec<String> = std::env::var("SHEPIT_TURN_URLS")
        .unwrap_or_default()
        .split(',')
        .map(|u| u.trim().to_owned())
        .filter(|u| !u.is_empty())
        .collect();
    (!urls.is_empty()).then_some(Turn { secret, urls })
}

fn internal(e: rusqlite::Error) -> StatusCode {
    eprintln!("db: {e}");
    StatusCode::INTERNAL_SERVER_ERROR
}

type Shared = Arc<App>;

/// Голосове повідомлення до ~30 с (Opus 16 кбіт/с) вміщається з запасом.
const MAX_PAYLOAD_HEX: usize = 2 * 96 * 1024;
const MAX_QUEUE: usize = 1000;
const MAX_ONE_TIME: usize = 100;
const FETCH_LIMIT: usize = 100;

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
        Ok(true) => {
            let listening = match app.bells.lock().unwrap().get(&env.to) {
                Some(bell) => bell.send(()).is_ok(),
                None => false,
            };
            #[cfg(feature = "push")]
            wake_up(&app, env.to, env.wake.as_deref() == Some("call"), listening);
            #[cfg(not(feature = "push"))]
            let _ = listening;
            StatusCode::ACCEPTED
        }
        Ok(false) => StatusCode::TOO_MANY_REQUESTS,
        Err(e) => internal(e),
    }
}

/// Скринька, якою володіє той, хто підписав запит (інакше 403).
fn own_mailbox(who: &str, id: &str) -> Result<MailboxId, StatusCode> {
    let mailbox = MailboxId::from_hex(id).ok_or(StatusCode::BAD_REQUEST)?;
    let who_bytes = hex::decode(who).map_err(|_| StatusCode::BAD_REQUEST)?;
    if MailboxId::for_identity(&who_bytes) != mailbox {
        return Err(StatusCode::FORBIDDEN);
    }
    Ok(mailbox)
}

async fn fetch(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> Result<Json<Vec<MailboxItem>>, StatusCode> {
    let who = authenticate(&app, &headers, &format!("GET /v0/mailbox/{id}"), b"")?;
    let mailbox = own_mailbox(&who, &id)?;
    Ok(Json(app.db.list(&mailbox, FETCH_LIMIT).map_err(internal)?))
}

async fn ack(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>, body: Bytes) -> StatusCode {
    let r = (|| {
        let who = authenticate(&app, &headers, &format!("POST /v0/mailbox/{id}/ack"), &body)?;
        let mailbox = own_mailbox(&who, &id)?;
        let a: Ack = serde_json::from_slice(&body).map_err(|_| StatusCode::BAD_REQUEST)?;
        app.db.ack(&mailbox, a.upto).map_err(internal)
    })();
    match r {
        Ok(_) => StatusCode::NO_CONTENT,
        Err(s) => s,
    }
}

fn data_dir() -> PathBuf {
    PathBuf::from(std::env::var("SHEPIT_DATA").unwrap_or_else(|_| "data".into()))
}

/// Потік подій для власника скриньки. Подія `mail` = «забери пошту» (вміст не передається).
async fn events(
    State(app): State<Shared>,
    headers: HeaderMap,
) -> Result<impl axum::response::IntoResponse, StatusCode> {
    use axum::response::sse::{Event, KeepAlive, Sse};
    use futures_util::StreamExt;
    let who = authenticate(&app, &headers, "GET /v0/events", b"")?;
    let mailbox = MailboxId::for_identity(&hex::decode(&who).map_err(|_| StatusCode::BAD_REQUEST)?);
    let rx = {
        let mut bells = app.bells.lock().unwrap();
        bells.retain(|_, tx| tx.receiver_count() > 0);
        bells.entry(mailbox).or_insert_with(|| tokio::sync::broadcast::channel(16).0).subscribe()
    };
    // Одразу «mail»: клієнт перевірить пошту, яка могла прийти, поки потік перепідключався.
    let first = futures_util::stream::once(async { Ok::<_, std::convert::Infallible>(Event::default().data("mail")) });
    let rest = tokio_stream::wrappers::BroadcastStream::new(rx)
        .map(|_| Ok::<_, std::convert::Infallible>(Event::default().data("mail")));
    let mut stop = app.shutdown.clone();
    let until = async move {
        let _ = stop.wait_for(|s| *s).await;
    };
    let sse = Sse::new(first.chain(rest).take_until(until)).keep_alive(KeepAlive::new().interval(std::time::Duration::from_secs(20)));
    // nginx не повинен буферизувати потік.
    Ok(([(header::HeaderName::from_static("x-accel-buffering"), HeaderValue::from_static("no"))], sse))
}

/// Розбудити отримувача через Web Push: дзвінок — завжди (терміново);
/// звичайна пошта — лише якщо застосунок не слухає потік подій, і не частіше ніж раз на 30 с.
#[cfg(feature = "push")]
fn wake_up(app: &Shared, mailbox: MailboxId, call: bool, listening: bool) {
    if !call {
        if listening {
            return;
        }
        let now = now_ms();
        let mut last = app.last_mail_push.lock().unwrap();
        if last.get(&mailbox).is_some_and(|t| now.saturating_sub(*t) < 30_000) {
            return;
        }
        last.insert(mailbox, now);
    }
    let app = app.clone();
    tokio::task::spawn_blocking(move || {
        let subs = match app.db.push_subs(&mailbox) {
            Ok(s) => s,
            Err(e) => return eprintln!("push: {e}"),
        };
        let kind = if call { push::Kind::Call } else { push::Kind::Mail };
        for sub in subs {
            match app.pusher.send(&sub, kind) {
                push::Outcome::Sent => {}
                push::Outcome::Gone => {
                    let _ = app.db.delete_push(&sub.endpoint);
                }
                push::Outcome::Failed(e) => eprintln!("push: {e}"),
            }
        }
    });
}

#[derive(serde::Deserialize)]
struct PushKeys {
    p256dh: String,
    auth: String,
}

#[derive(serde::Deserialize)]
struct PushSubscription {
    endpoint: String,
    keys: Option<PushKeys>,
}

async fn push_key(State(_app): State<Shared>) -> Result<String, StatusCode> {
    #[cfg(feature = "push")]
    return Ok(_app.pusher.public_b64.clone());
    #[cfg(not(feature = "push"))]
    Err(StatusCode::NOT_FOUND)
}

/// Дозволені сервіси сповіщень (щоб сервер не можна було змусити слати запити куди завгодно).
fn push_endpoint_ok(endpoint: &str) -> bool {
    const HOSTS: &[&str] = &[
        "fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com",
        "wns2-par02p.notify.windows.com", "notify.windows.com",
    ];
    let Some(rest) = endpoint.strip_prefix("https://") else { return false };
    let host = rest.split(['/', ':']).next().unwrap_or("");
    HOSTS.iter().any(|h| host == *h || host.ends_with(&format!(".{h}")))
}

async fn put_push(State(app): State<Shared>, headers: HeaderMap, body: Bytes) -> StatusCode {
    let r = (|| {
        let who = authenticate(&app, &headers, "PUT /v0/push", &body)?;
        let mailbox = MailboxId::for_identity(&hex::decode(&who).map_err(|_| StatusCode::BAD_REQUEST)?);
        let sub: PushSubscription = serde_json::from_slice(&body).map_err(|_| StatusCode::BAD_REQUEST)?;
        let keys = sub.keys.ok_or(StatusCode::BAD_REQUEST)?;
        if !push_endpoint_ok(&sub.endpoint) || sub.endpoint.len() > 1024 {
            return Err(StatusCode::BAD_REQUEST);
        }
        let s = db::PushSub { endpoint: sub.endpoint, p256dh: keys.p256dh, auth: keys.auth };
        app.db.put_push(&mailbox, &s).map_err(internal)
    })();
    r.map(|_| StatusCode::NO_CONTENT).unwrap_or_else(|s| s)
}

async fn delete_push(State(app): State<Shared>, headers: HeaderMap, body: Bytes) -> StatusCode {
    let r = (|| {
        authenticate(&app, &headers, "DELETE /v0/push", &body)?;
        let sub: PushSubscription = serde_json::from_slice(&body).map_err(|_| StatusCode::BAD_REQUEST)?;
        app.db.delete_push(&sub.endpoint).map_err(internal)
    })();
    r.map(|_| StatusCode::NO_CONTENT).unwrap_or_else(|s| s)
}

#[derive(serde::Serialize)]
struct TurnCredentials {
    urls: Vec<String>,
    username: String,
    credential: String,
    ttl: u64,
}

async fn turn_credentials(State(app): State<Shared>, headers: HeaderMap) -> Result<Json<TurnCredentials>, StatusCode> {
    use base64::Engine;
    use hmac::{Hmac, Mac};
    use rand::Rng;
    authenticate(&app, &headers, "GET /v0/turn", b"")?;
    let t = app.turn.as_ref().ok_or(StatusCode::NOT_FOUND)?;
    // Логін не містить ідентичності користувача — ретранслятор не знає, хто дзвонить.
    let expiry = now_ms() / 1000 + TURN_TTL_S;
    let username = format!("{expiry}:{}", hex::encode(rand::rng().random::<[u8; 8]>()));
    let mut mac = Hmac::<sha1::Sha1>::new_from_slice(t.secret.as_bytes()).expect("hmac key");
    mac.update(username.as_bytes());
    let credential = base64::engine::general_purpose::STANDARD.encode(mac.finalize().into_bytes());
    Ok(Json(TurnCredentials { urls: t.urls.clone(), username, credential, ttl: TURN_TTL_S }))
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
    img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; \
    form-action 'self'; frame-ancestors 'none'";

fn web_dir() -> Option<PathBuf> {
    let dir = PathBuf::from(std::env::var("SHEPIT_WEB").unwrap_or_else(|_| "web".into()));
    dir.join("index.html").is_file().then_some(dir)
}

fn app(issuer: Issuer, db: db::Db, shutdown: tokio::sync::watch::Receiver<bool>) -> Router {
    use tower_http::{services::{ServeDir, ServeFile}, set_header::SetResponseHeaderLayer};
    let turn = load_turn();
    println!("Ретранслятор дзвінків: {}", turn.as_ref().map(|t| t.urls.join(", ")).unwrap_or_else(|| "не налаштовано".into()));
    #[cfg(feature = "push")]
    let pusher = {
        let p = push::Pusher::load(&data_dir());
        println!("Сповіщення (Web Push): увімкнено");
        Arc::new(p)
    };
    let shared = Arc::new(App {
        db,
        nonces: Mutex::default(),
        bells: Mutex::default(),
        turn,
        shutdown,
        #[cfg(feature = "push")]
        pusher,
        #[cfg(feature = "push")]
        last_mail_push: Mutex::default(),
        issuer,
    });
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
        .route("/v0/mailbox/{id}/ack", post(ack))
        .route("/v0/events", get(events))
        .route("/v0/turn", get(turn_credentials))
        .route("/v0/push/key", get(push_key))
        .route("/v0/push", put(put_push).delete(delete_push))
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
async fn shutdown_signal(tx: tokio::sync::watch::Sender<bool>) {
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
    let _ = tx.send(true);
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
    let (tx, rx) = tokio::sync::watch::channel(false);
    axum::serve(listener, app(issuer, db, rx))
        .with_graceful_shutdown(shutdown_signal(tx))
        .await
        .unwrap();
}
