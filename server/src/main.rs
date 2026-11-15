//! Прототип сервера доставки.
//!
//! - POST /v0/send            — покласти конверт у скриньку отримувача
//! - GET  /v0/mailbox/{id}    — забрати всі конверти (і видалити їх із сервера)
//! - GET  /health
//!
//! Етап 0: зберігання в пам'яті, без автентифікації. Нічого не логуємо про
//! відправників і отримувачів.

use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{Arc, Mutex},
};

use axum::{
    extract::{Path, State},
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use shepit_core::envelope::{Envelope, MailboxId};

type Store = Arc<Mutex<HashMap<MailboxId, Vec<Envelope>>>>;

const MAX_PAYLOAD: usize = 64 * 1024;
const MAX_QUEUE: usize = 1000;

async fn send(State(store): State<Store>, Json(env): Json<Envelope>) -> StatusCode {
    if env.payload.len() > MAX_PAYLOAD {
        return StatusCode::PAYLOAD_TOO_LARGE;
    }
    let mut map = store.lock().unwrap();
    let queue = map.entry(env.to).or_default();
    if queue.len() >= MAX_QUEUE {
        return StatusCode::TOO_MANY_REQUESTS;
    }
    queue.push(env);
    StatusCode::ACCEPTED
}

async fn fetch(
    State(store): State<Store>,
    Path(id): Path<String>,
) -> Result<Json<Vec<Envelope>>, StatusCode> {
    let id = MailboxId::from_hex(&id).ok_or(StatusCode::BAD_REQUEST)?;
    let envelopes = store.lock().unwrap().remove(&id).unwrap_or_default();
    Ok(Json(envelopes))
}

pub fn app() -> Router {
    let store: Store = Arc::default();
    Router::new()
        .route("/health", get(|| async { "ok" }))
        .route("/v0/send", post(send))
        .route("/v0/mailbox/{id}", get(fetch))
        .with_state(store)
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
