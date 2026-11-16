//! Канали: один (власник ключа каналу) пише — багато читають.
//!
//! Канал = пара ключів ECDSA P-256. Ідентифікатор = перші 16 байтів SHA-256 від публічного
//! ключа (hex). Змінювати опис і публікувати дописи може лише той, хто має приватний ключ:
//! кожен запит підписаний (`x-shepit-channel: <час>.<nonce>.<підпис>`).
//!
//! Два типи:
//! - **зашифрований** — опис і дописи зашифровані ключем, який є лише в посиланні-запрошенні
//!   (після `#`, на сервер не потрапляє). Сервер зберігає шифротекст і не знає, хто підписаний;
//! - **публічний** — опис і дописи відкриті (JSON), канал видно в пошуку.
//!
//! Читати може будь-хто, хто знає ідентифікатор (для зашифрованого це марно без ключа).
//! Створити канал може лише зареєстрований користувач; власника сервер не запам'ятовує.

use axum::{
    body::Bytes,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    Json,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD as B64, Engine};
use p256::ecdsa::{signature::Verifier, Signature, VerifyingKey};
use sha2::{Digest, Sha256};
use shepit_core::auth::{now_ms, MAX_SKEW_MS};

use crate::{authenticate, db, internal, Shared};

pub const HEADER: &str = "x-shepit-channel";
/// Опис каналу (назва, опис, аватар).
pub const MAX_META: usize = 96 * 1024;
/// Допис (текст або голосове до ~30 с у base64).
pub const MAX_POST: usize = 300 * 1024;
const PAGE: usize = 50;

/// Ідентифікатор каналу з публічного ключа.
pub fn channel_id(pub_raw: &[u8]) -> String {
    hex::encode(&Sha256::digest(pub_raw)[..16])
}

fn valid_id(id: &str) -> bool {
    id.len() == 32 && id.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// Перевірити підпис ключем каналу. Повідомлення: "METHOD path\nчас\nnonce\nsha256(body) hex".
pub fn verify(app: &crate::App, pub_raw: &[u8], headers: &HeaderMap, method: &str, path: &str, body: &[u8]) -> Result<(), StatusCode> {
    let raw = headers.get(HEADER).and_then(|v| v.to_str().ok()).ok_or(StatusCode::UNAUTHORIZED)?;
    let mut parts = raw.splitn(3, '.');
    let (Some(t), Some(nonce), Some(sig)) = (parts.next(), parts.next(), parts.next()) else {
        return Err(StatusCode::UNAUTHORIZED);
    };
    let t: u64 = t.parse().map_err(|_| StatusCode::UNAUTHORIZED)?;
    let now = now_ms();
    if t.abs_diff(now) > MAX_SKEW_MS || nonce.is_empty() || nonce.len() > 64 {
        return Err(StatusCode::UNAUTHORIZED);
    }
    let msg = format!("{method} {path}\n{t}\n{nonce}\n{}", hex::encode(Sha256::digest(body)));
    let vk = VerifyingKey::from_sec1_bytes(pub_raw).map_err(|_| StatusCode::BAD_REQUEST)?;
    let sig = B64.decode(sig).ok().and_then(|s| Signature::from_slice(&s).ok()).ok_or(StatusCode::UNAUTHORIZED)?;
    vk.verify(msg.as_bytes(), &sig).map_err(|_| StatusCode::UNAUTHORIZED)?;
    let mut nonces = app.nonces.lock().unwrap();
    if nonces.insert(format!("c:{nonce}"), now).is_some() {
        return Err(StatusCode::UNAUTHORIZED);
    }
    Ok(())
}

#[derive(serde::Deserialize)]
pub struct PutChannel {
    /// Публічний ключ каналу: точка P-256 без стиснення (65 байтів), base64url.
    #[serde(rename = "pub")]
    pub_key: String,
    #[serde(default)]
    public: bool,
    /// Опис: JSON (публічний канал) або шифротекст base64url (зашифрований).
    meta: String,
}

/// Створити канал або оновити опис.
pub async fn put_channel(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>, body: Bytes) -> StatusCode {
    let r = (|| {
        if !valid_id(&id) {
            return Err(StatusCode::BAD_REQUEST);
        }
        let req: PutChannel = serde_json::from_slice(&body).map_err(|_| StatusCode::BAD_REQUEST)?;
        let pub_raw = B64.decode(&req.pub_key).map_err(|_| StatusCode::BAD_REQUEST)?;
        if channel_id(&pub_raw) != id || req.meta.len() > MAX_META {
            return Err(StatusCode::BAD_REQUEST);
        }
        if req.public && serde_json::from_str::<serde_json::Value>(&req.meta).is_err() {
            return Err(StatusCode::BAD_REQUEST);
        }
        let path = format!("/v0/channels/{id}");
        verify(&app, &pub_raw, &headers, "PUT", &path, &body)?;
        let now = now_ms();
        match app.db.channel(&id).map_err(internal)? {
            Some(c) => {
                if c.public != req.public {
                    return Err(StatusCode::CONFLICT); // тип каналу не змінюється
                }
                app.db.update_channel_meta(&id, &req.meta, now).map_err(internal)?;
            }
            None => {
                // Новий канал — лише від зареєстрованого користувача (власника не зберігаємо).
                let who = authenticate(&app, &headers, &format!("PUT {path}"), &body)?;
                app.require_account(&who).map_err(|(s, _)| s)?;
                app.db.create_channel(&id, &req.pub_key, req.public, &req.meta, now).map_err(internal)?;
            }
        }
        Ok(())
    })();
    r.map(|_| StatusCode::NO_CONTENT).unwrap_or_else(|s| s)
}

pub async fn get_channel(State(app): State<Shared>, Path(id): Path<String>) -> Result<Json<db::Channel>, StatusCode> {
    app.db.channel(&id).map_err(internal)?.map(Json).ok_or(StatusCode::NOT_FOUND)
}

fn owner_check(app: &crate::App, id: &str, headers: &HeaderMap, method: &str, path: &str, body: &[u8]) -> Result<(), StatusCode> {
    let c = app.db.channel(id).map_err(internal)?.ok_or(StatusCode::NOT_FOUND)?;
    let pub_raw = B64.decode(&c.pub_key).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    verify(app, &pub_raw, headers, method, path, body)
}

/// Видалити канал (власник).
pub async fn delete_channel(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> StatusCode {
    let r = owner_check(&app, &id, &headers, "DELETE", &format!("/v0/channels/{id}"), b"")
        .and_then(|_| app.db.delete_channel(&id).map_err(internal));
    r.map(|_| StatusCode::NO_CONTENT).unwrap_or_else(|s| s)
}

#[derive(serde::Deserialize)]
pub struct NewPost {
    data: String,
}

#[derive(serde::Serialize)]
pub struct Posted {
    seq: i64,
    at: u64,
}

pub async fn post(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>, body: Bytes) -> Result<Json<Posted>, StatusCode> {
    owner_check(&app, &id, &headers, "POST", &format!("/v0/channels/{id}/posts"), &body)?;
    let p: NewPost = serde_json::from_slice(&body).map_err(|_| StatusCode::BAD_REQUEST)?;
    if p.data.is_empty() || p.data.len() > MAX_POST {
        return Err(StatusCode::PAYLOAD_TOO_LARGE);
    }
    let at = now_ms();
    let seq = app.db.add_post(&id, &p.data, at).map_err(internal)?;
    Ok(Json(Posted { seq, at }))
}

pub async fn delete_post(State(app): State<Shared>, headers: HeaderMap, Path((id, seq)): Path<(String, i64)>) -> StatusCode {
    let r = owner_check(&app, &id, &headers, "DELETE", &format!("/v0/channels/{id}/posts/{seq}"), b"")
        .and_then(|_| app.db.delete_post(&id, seq).map_err(internal));
    match r {
        Ok(1..) => StatusCode::NO_CONTENT,
        Ok(_) => StatusCode::NOT_FOUND,
        Err(s) => s,
    }
}

#[derive(serde::Deserialize)]
pub struct PostsQuery {
    /// Нові дописи після цього номера (для оновлення стрічки).
    after: Option<i64>,
    /// Старіші за цей номер (гортання назад). Без after/before — найновіші.
    before: Option<i64>,
    limit: Option<usize>,
}

pub async fn posts(State(app): State<Shared>, Path(id): Path<String>, Query(q): Query<PostsQuery>) -> Result<Json<Vec<db::Post>>, StatusCode> {
    if !valid_id(&id) {
        return Err(StatusCode::BAD_REQUEST);
    }
    let limit = q.limit.unwrap_or(PAGE).clamp(1, 200);
    Ok(Json(app.db.posts(&id, q.after, q.before, limit).map_err(internal)?))
}

#[derive(serde::Deserialize)]
pub struct SearchQuery {
    #[serde(default)]
    q: String,
}

/// Пошук публічних каналів за назвою й описом.
pub async fn search(State(app): State<Shared>, Query(q): Query<SearchQuery>) -> Result<Json<Vec<db::Channel>>, StatusCode> {
    let q = q.q.trim();
    if q.chars().count() > 100 {
        return Err(StatusCode::BAD_REQUEST);
    }
    Ok(Json(app.db.search_public_channels(q, 50).map_err(internal)?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::{signature::Signer, SigningKey};

    #[test]
    fn signature_format() {
        // Підпис у форматі WebCrypto (r||s, 64 байти) з ключа у форматі «raw» (65 байтів).
        let sk = SigningKey::from_slice(&[7u8; 32]).unwrap();
        let pub_raw = sk.verifying_key().to_encoded_point(false).as_bytes().to_vec();
        assert_eq!(pub_raw.len(), 65);
        let msg = "POST /v0/channels/x/posts\n1\nn\nabc";
        let sig: Signature = sk.sign(msg.as_bytes());
        let vk = VerifyingKey::from_sec1_bytes(&pub_raw).unwrap();
        assert!(vk.verify(msg.as_bytes(), &Signature::from_slice(&sig.to_bytes()).unwrap()).is_ok());
        assert_eq!(channel_id(&pub_raw).len(), 32);
    }
}
