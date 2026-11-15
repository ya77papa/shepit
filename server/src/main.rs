//! Прототип сервера доставки.
//!
//! - PUT  /v0/keys                   — опублікувати свої ключі (KeyUpload)
//! - GET  /v0/keys/{identity}        — отримати пакет ключів людини (забирає 1 одноразовий ключ)
//! - POST /v0/send                   — покласти конверт у скриньку
//! - GET  /v0/mailbox/{id}           — забрати конверти (і видалити їх із сервера)
//! - GET  /health
//!
//! Сервер бачить лише шифротекст. Етап 0: зберігання в пам'яті, без автентифікації
//! (TODO: підписувати KeyUpload і запити до скриньки ключем ідентичності).

use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{Arc, Mutex},
};

use axum::{
    extract::{Path, State},
    http::StatusCode,
    routing::{get, post, put},
    Json, Router,
};
use shepit_core::wire::{Envelope, KeyUpload, MailboxId, PreKeyBundle};

#[derive(Default)]
struct Inner {
    mailboxes: HashMap<MailboxId, Vec<Envelope>>,
    keys: HashMap<String, KeyUpload>,
}

type Store = Arc<Mutex<Inner>>;

const MAX_PAYLOAD_HEX: usize = 2 * 64 * 1024;
const MAX_QUEUE: usize = 1000;
const MAX_ONE_TIME: usize = 100;

async fn put_keys(State(st): State<Store>, Json(up): Json<KeyUpload>) -> StatusCode {
    if up.one_time.len() > MAX_ONE_TIME || hex_len_bad(&up.identity, 66) {
        return StatusCode::BAD_REQUEST;
    }
    st.lock().unwrap().keys.insert(up.identity.clone(), up);
    StatusCode::NO_CONTENT
}

fn hex_len_bad(s: &str, len: usize) -> bool {
    s.len() != len || !s.bytes().all(|b| b.is_ascii_hexdigit())
}

async fn get_keys(State(st): State<Store>, Path(identity): Path<String>) -> Result<Json<PreKeyBundle>, StatusCode> {
    let mut st = st.lock().unwrap();
    let up = st.keys.get_mut(&identity).ok_or(StatusCode::NOT_FOUND)?;
    Ok(Json(up.take_bundle()))
}

async fn send(State(st): State<Store>, Json(env): Json<Envelope>) -> StatusCode {
    if env.payload.len() > MAX_PAYLOAD_HEX {
        return StatusCode::PAYLOAD_TOO_LARGE;
    }
    let mut st = st.lock().unwrap();
    let queue = st.mailboxes.entry(env.to).or_default();
    if queue.len() >= MAX_QUEUE {
        return StatusCode::TOO_MANY_REQUESTS;
    }
    queue.push(env);
    StatusCode::ACCEPTED
}

async fn fetch(State(st): State<Store>, Path(id): Path<String>) -> Result<Json<Vec<Envelope>>, StatusCode> {
    let id = MailboxId::from_hex(&id).ok_or(StatusCode::BAD_REQUEST)?;
    Ok(Json(st.lock().unwrap().mailboxes.remove(&id).unwrap_or_default()))
}

fn app() -> Router {
    Router::new()
        .route("/health", get(|| async { "ok" }))
        .route("/v0/keys", put(put_keys))
        .route("/v0/keys/{identity}", get(get_keys))
        .route("/v0/send", post(send))
        .route("/v0/mailbox/{id}", get(fetch))
        .with_state(Store::default())
}

#[tokio::main]
async fn main() {
    let addr: SocketAddr = std::env::var("SHEPIT_LISTEN")
        .unwrap_or_else(|_| "0.0.0.0:8443".into())
        .parse()
        .expect("SHEPIT_LISTEN: invalid address");
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    println!("shepit-server listening on {addr}");
    axum::serve(listener, app())
        .with_graceful_shutdown(async { tokio::signal::ctrl_c().await.ok(); })
        .await
        .unwrap();
}
