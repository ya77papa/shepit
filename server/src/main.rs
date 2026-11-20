//! Прототип сервера доставки Шепіт.
//!
//! | Метод | Шлях                    | Підпис | Що робить |
//! |-------|-------------------------|--------|-----------|
//! | GET   | /v0/trust-root          | —      | корінь довіри для сертифікатів відправника |
//! | PUT   | /v0/keys                | так    | опублікувати свої ключі (тільки власник ключа) |
//! | GET   | /v0/keys/status         | так    | скільки моїх одноразових ключів лишилось (404 — ключів немає) |
//! | GET   | /v0/keys/{identity}     | —      | пакет ключів людини (забирає 1 одноразовий) |
//! | GET   | /v0/certificate         | так    | сертифікат відправника на 4 год (лише зареєстрованим) |
//! | POST  | /v0/send                | —      | покласти конверт у скриньку (sealed sender: відправник невідомий) |
//! | GET   | /v0/mailbox/{id}        | так    | переглянути конверти (тільки власник скриньки) |
//! | POST  | /v0/mailbox/{id}/ack    | так    | «отримав і зберіг до N» — сервер видаляє |
//! | GET   | /v0/events              | так    | потік подій (SSE): «у скриньці нова пошта» — миттєва доставка |
//! | GET   | /v0/turn                | так    | тимчасовий логін до ретранслятора дзвінків (TURN) |
//! | GET   | /v0/push/key            | —      | публічний ключ VAPID для підписки на сповіщення |
//! | PUT   | /v0/push                | так    | підписати браузер на сповіщення (Web Push) |
//! | DELETE| /v0/push                | так    | відписати |
//! | GET   | /v0/registration        | —      | режим реєстрації: open / invite / closed |
//! | POST  | /v0/invites/mine        | так    | моє запрошення (код) для нових користувачів |
//! | …     | /v0/channels/…          | ключ каналу | канали (див. `channels.rs`) |
//! | …     | /v0/admin/…             | сесія  | адмін-панель (див. `admin.rs`) |
//!
//! Нова ідентичність реєструється першою публікацією ключів (PUT /v0/keys). У режимі
//! «за запрошенням» запит має містити заголовок `x-shepit-invite: <код>`.
//!
//! Також роздає веб-клієнт зі `$SHEPIT_WEB` (типово `./web`, якщо тека існує).
//!
//! Сервер бачить: хто отримує (скринька) і розмір. НЕ бачить: текст і відправника.
//! Дані — у $SHEPIT_DATA: issuer.json (ключі сертифікатів) і shepit.db (SQLite: ключі, черги).

mod admin;
mod channels;
mod db;
mod gateway;
mod presence;
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
    admin: admin::Admin,
    started: u64,
    /// Кому вже позначено активність сьогодні (щоб не писати в базу на кожен запит).
    seen: Mutex<HashMap<String, u64>>,
    /// «В мережі» / «востаннє…» — лише в пам'яті.
    presence: presence::Presence,
    /// Шлюз mesh: пакети від телефонів-ретрансляторів.
    gateway: gateway::Gateway,
}

const INVITE_HEADER: &str = "x-shepit-invite";

#[derive(Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
enum Mode {
    Open,
    Invite,
    Closed,
}

impl App {
    fn mode(&self) -> Mode {
        match self.db.setting("registration").ok().flatten().as_deref() {
            Some("invite") => Mode::Invite,
            Some("closed") => Mode::Closed,
            _ => Mode::Open,
        }
    }

    fn user_invite_limit(&self) -> usize {
        self.db.setting("user_invite_limit").ok().flatten().and_then(|v| v.parse().ok()).unwrap_or(5)
    }

    /// Лише зареєстровані акаунти отримують сертифікати відправника й доступ до ретранслятора.
    fn require_account(&self, who: &str) -> Result<(), Refusal> {
        if self.db.is_account(who).map_err(|e| (internal(e), ""))? {
            Ok(())
        } else if self.db.is_blocked(who).unwrap_or(false) {
            Err((StatusCode::FORBIDDEN, "account-blocked"))
        } else {
            Err((StatusCode::FORBIDDEN, "not-registered"))
        }
    }

    fn touch(&self, who: &str) {
        let day = now_ms() / db::DAY_MS;
        let mut seen = self.seen.lock().unwrap();
        if seen.get(who) == Some(&day) {
            return;
        }
        if seen.len() > 100_000 {
            seen.clear();
        }
        seen.insert(who.to_owned(), day);
        let _ = self.db.touch(who, day);
    }
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

/// Фото (клієнт стискає до ~600 КБ) або голосове — з запасом на шифрування й base64.
const MAX_PAYLOAD_HEX: usize = 2 * 1200 * 1024;
/// Межа тіла запиту /v0/send (JSON з hex-вмістом).
const MAX_SEND_BODY: usize = MAX_PAYLOAD_HEX + 64 * 1024;
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
    drop(nonces);
    app.touch(&auth.identity);
    Ok(auth.identity)
}

async fn trust_root(State(app): State<Shared>) -> String {
    app.issuer.trust_root_hex()
}

/// Відповідь з поясненням причини відмови (клієнт показує її людині).
type Refusal = (StatusCode, &'static str);

async fn put_keys(State(app): State<Shared>, headers: HeaderMap, body: Bytes) -> Result<StatusCode, Refusal> {
    let plain = |s: StatusCode| (s, "");
    let who = authenticate(&app, &headers, "PUT /v0/keys", &body).map_err(plain)?;
    let up = serde_json::from_slice::<KeyUpload>(&body).map_err(|_| plain(StatusCode::BAD_REQUEST))?;
    // Опублікувати ключі можна лише для себе: identity у тілі = той, хто підписав.
    if up.identity != who {
        return Err(plain(StatusCode::FORBIDDEN));
    }
    if up.one_time.len() > MAX_ONE_TIME {
        return Err(plain(StatusCode::BAD_REQUEST));
    }
    // Нова ідентичність — це реєстрація: перевірити режим і запрошення.
    if !app.db.is_account(&who).map_err(|e| plain(internal(e)))? {
        if app.db.is_blocked(&who).map_err(|e| plain(internal(e)))? {
            return Err((StatusCode::FORBIDDEN, "account-blocked"));
        }
        let invite = headers.get(INVITE_HEADER).and_then(|v| v.to_str().ok()).and_then(admin::normalize_code);
        let invite = match (app.mode(), invite) {
            (Mode::Closed, _) => return Err((StatusCode::FORBIDDEN, "registration-closed")),
            (Mode::Invite, None) => return Err((StatusCode::FORBIDDEN, "invite-required")),
            (Mode::Invite, Some(code)) => Some(code),
            // Вільна реєстрація: код, якщо є, все одно зараховується (статистика запрошень).
            (Mode::Open, code) => code,
        };
        let mut r = app.db.register(&who, invite.as_deref(), now_ms()).map_err(|e| plain(internal(e)))?;
        if r == db::Register::BadInvite && app.mode() == Mode::Open {
            r = app.db.register(&who, None, now_ms()).map_err(|e| plain(internal(e)))?;
        }
        match r {
            db::Register::BadInvite => return Err((StatusCode::FORBIDDEN, "invite-invalid")),
            db::Register::Blocked => return Err((StatusCode::FORBIDDEN, "account-blocked")),
            db::Register::Ok => {}
        }
    }
    app.db.put_keys(&up).map_err(|e| plain(internal(e)))?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(serde::Serialize)]
struct Registration {
    mode: Mode,
}

async fn registration(State(app): State<Shared>) -> Json<Registration> {
    Json(Registration { mode: app.mode() })
}

#[derive(serde::Serialize)]
struct MyInvite {
    /// None — у вільному режимі код не потрібен або ліміт вичерпано.
    code: Option<String>,
    mode: Mode,
    /// Скільки ще нових запрошень можна створити.
    left: usize,
}

/// Код запрошення для мого посилання: чинний (ще не використаний) або новий у межах ліміту.
#[derive(serde::Deserialize)]
struct Beat {
    online: bool,
    #[serde(default = "yes")]
    share: bool,
}
fn yes() -> bool {
    true
}

/// «Я тут» (застосунок на екрані) або «згорнув». Клієнт шле раз на хвилину, поки відкритий.
async fn presence_beat(State(app): State<Shared>, headers: HeaderMap, body: Bytes) -> Result<StatusCode, Refusal> {
    let who = authenticate(&app, &headers, "POST /v0/presence", &body).map_err(|s| (s, ""))?;
    app.require_account(&who)?;
    let b: Beat = serde_json::from_slice(&body).map_err(|_| (StatusCode::BAD_REQUEST, ""))?;
    app.touch(&who);
    app.presence.beat(&who, b.online, b.share, now_ms());
    Ok(StatusCode::NO_CONTENT)
}

#[derive(serde::Deserialize)]
struct PresenceQuery {
    ids: Vec<String>,
}

/// Статуси співрозмовників (до 200 за раз).
async fn presence_query(State(app): State<Shared>, headers: HeaderMap, body: Bytes) -> Result<Json<HashMap<String, presence::Status>>, Refusal> {
    let who = authenticate(&app, &headers, "POST /v0/presence/query", &body).map_err(|s| (s, ""))?;
    app.require_account(&who)?;
    let q: PresenceQuery = serde_json::from_slice(&body).map_err(|_| (StatusCode::BAD_REQUEST, ""))?;
    if q.ids.len() > 200 {
        return Err((StatusCode::BAD_REQUEST, ""));
    }
    Ok(Json(app.presence.query(&who, &q.ids, now_ms())))
}

/// Користувач сам видаляє свій акаунт: ключі, недоставлену пошту, підписки, запрошення.
/// Канали, групи й бесіди застосунок прибирає перед цим сам (сервер не знає, чиї вони).
async fn delete_my_account(State(app): State<Shared>, headers: HeaderMap) -> StatusCode {
    let who = match authenticate(&app, &headers, "DELETE /v0/account", b"") {
        Ok(w) => w,
        Err(s) => return s,
    };
    let Ok(bytes) = hex::decode(&who) else { return StatusCode::BAD_REQUEST };
    let mailbox = MailboxId::for_identity(&bytes);
    match app.db.delete_account(&who, &mailbox, false, now_ms()) {
        Ok(_) => {
            app.bells.lock().unwrap().remove(&mailbox);
            app.seen.lock().unwrap().remove(&who);
            app.presence.forget(&who);
            println!("Акаунт видалено власником: {}…", &who[..who.len().min(10)]);
            StatusCode::NO_CONTENT
        }
        Err(e) => internal(e),
    }
}

/// Публічний ключ шлюзу mesh (застосунки беруть його, поки інтернет є).
async fn gateway_key(State(app): State<Shared>) -> String {
    app.gateway.public_hex()
}

#[derive(serde::Deserialize)]
struct GatewayReq {
    blob: String,
}

#[derive(serde::Deserialize)]
struct GatewayInner {
    to: String,
    exp: u64,
}

/// Ретранслятор вивантажує пакет телефона без інтернету. Ретранслятор має бути
/// зареєстрований (щоб шлюз не став відкритою поштовою скринькою для всього інтернету).
async fn gateway_upload(State(app): State<Shared>, headers: HeaderMap, body: Bytes) -> Result<StatusCode, Refusal> {
    let who = authenticate(&app, &headers, "POST /v0/gateway", &body).map_err(|s| (s, ""))?;
    app.require_account(&who)?;
    let req: GatewayReq = serde_json::from_slice(&body).map_err(|_| (StatusCode::BAD_REQUEST, ""))?;
    if req.blob.len() > MAX_PAYLOAD_HEX {
        return Err((StatusCode::PAYLOAD_TOO_LARGE, ""));
    }
    let blob = hex::decode(&req.blob).map_err(|_| (StatusCode::BAD_REQUEST, ""))?;
    let plain = app.gateway.open(&blob).ok_or((StatusCode::BAD_REQUEST, "gateway-invalid"))?;
    let inner: GatewayInner = serde_json::from_slice(&plain).map_err(|_| (StatusCode::BAD_REQUEST, "gateway-invalid"))?;
    if inner.exp < now_ms() {
        return Ok(StatusCode::GONE); // прострочений — ніхто вже не чекає
    }
    let to = MailboxId::from_hex(&inner.to).ok_or((StatusCode::BAD_REQUEST, "gateway-invalid"))?;
    // Конверт для адресата: його застосунок упізнає mesh за wake = "mesh" і тегом усередині.
    let mut env = Envelope::new(to, &plain);
    env.wake = Some("mesh".into());
    match app.db.push(&env, MAX_QUEUE, now_ms()) {
        Ok(true) => {
            let listening = match app.bells.lock().unwrap().get(&env.to) {
                Some(bell) => bell.send(()).is_ok(),
                None => false,
            };
            #[cfg(feature = "push")]
            wake_up(&app, env.to, false, listening);
            #[cfg(not(feature = "push"))]
            let _ = listening;
            Ok(StatusCode::ACCEPTED)
        }
        Ok(false) => Ok(StatusCode::TOO_MANY_REQUESTS),
        Err(e) => Err((internal(e), "")),
    }
}

async fn my_invite(State(app): State<Shared>, headers: HeaderMap) -> Result<Json<MyInvite>, Refusal> {
    let who = authenticate(&app, &headers, "POST /v0/invites/mine", b"").map_err(|s| (s, ""))?;
    app.require_account(&who)?;
    let internal = |e| (internal(e), "");
    let mode = app.mode();
    let limit = app.user_invite_limit();
    let now = now_ms();
    let (active, total) = app.db.user_invite(&who, now).map_err(internal)?;
    if mode != Mode::Invite {
        return Ok(Json(MyInvite { code: None, mode, left: limit.saturating_sub(total) }));
    }
    if let Some(code) = active {
        return Ok(Json(MyInvite { code: Some(code), mode, left: limit.saturating_sub(total) }));
    }
    if total >= limit {
        return Ok(Json(MyInvite { code: None, mode, left: 0 }));
    }
    let inv = db::Invite {
        code: admin::new_invite_code(), created: now, creator: Some(who), note: String::new(),
        max_uses: 1, uses: 0, expires: Some(now + 30 * db::DAY_MS), revoked: false,
    };
    app.db.create_invite(&inv).map_err(internal)?;
    Ok(Json(MyInvite { code: Some(inv.code), mode, left: limit - total - 1 }))
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

async fn certificate(State(app): State<Shared>, headers: HeaderMap) -> Result<String, Refusal> {
    let who = authenticate(&app, &headers, "GET /v0/certificate", b"").map_err(|s| (s, ""))?;
    app.require_account(&who)?;
    let cert = app.issuer.issue(&who, now_ms()).map_err(|_| (StatusCode::BAD_REQUEST, ""))?;
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
            // «quiet» — службове (підтвердження доставки/перегляду): сповіщенням не будити.
            #[cfg(feature = "push")]
            if env.wake.as_deref() != Some("quiet") {
                wake_up(&app, env.to, env.wake.as_deref() == Some("call"), listening);
            }
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

async fn turn_credentials(State(app): State<Shared>, headers: HeaderMap) -> Result<Json<TurnCredentials>, Refusal> {
    use base64::Engine;
    use hmac::{Hmac, Mac};
    use rand::Rng;
    let who = authenticate(&app, &headers, "GET /v0/turn", b"").map_err(|s| (s, ""))?;
    app.require_account(&who)?;
    let t = app.turn.as_ref().ok_or((StatusCode::NOT_FOUND, ""))?;
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
    img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; \
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
        admin: admin::Admin::default(),
        started: now_ms(),
        seen: Mutex::default(),
        presence: Default::default(),
        gateway: gateway::Gateway::load(&data_dir()),
    });
    // Раз на годину прибирати недоставлені конверти (медіа — старші за 3 дні, решту — за 30).
    let pruner = shared.clone();
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(3600));
        loop {
            tick.tick().await;
            pruner.presence.prune(now_ms());
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
        .route("/v0/presence", post(presence_beat))
        .route("/v0/gateway-key", get(gateway_key))
        .route("/v0/gateway", post(gateway_upload).layer(axum::extract::DefaultBodyLimit::max(MAX_SEND_BODY)))
        .route("/v0/account", axum::routing::delete(delete_my_account))
        .route("/v0/presence/query", post(presence_query))
        .route("/v0/send", post(send).layer(axum::extract::DefaultBodyLimit::max(MAX_SEND_BODY)))
        .route("/v0/mailbox/{id}", get(fetch))
        .route("/v0/mailbox/{id}/ack", post(ack))
        .route("/v0/events", get(events))
        .route("/v0/turn", get(turn_credentials))
        .route("/v0/push/key", get(push_key))
        .route("/v0/push", put(put_push).delete(delete_push))
        .route("/v0/registration", get(registration))
        .route("/v0/invites/mine", post(my_invite))
        .route("/v0/channels", get(channels::search))
        .route("/v0/channels/{id}", get(channels::get_channel).put(channels::put_channel).delete(channels::delete_channel))
        .route("/v0/channels/{id}/posts", get(channels::posts).post(channels::post))
        .route("/v0/channels/{id}/posts/{seq}", axum::routing::delete(channels::delete_post))
        .route("/v0/admin/channels", get(admin_channels))
        .route("/v0/admin/channels/{id}", axum::routing::delete(admin_delete_channel))
        .route("/v0/admin/login", post(admin_login))
        .route("/v0/admin/logout", post(admin_logout))
        .route("/v0/admin/overview", get(admin_overview))
        .route("/v0/admin/settings", put(admin_settings))
        .route("/v0/admin/invites", get(admin_invites).post(admin_create_invite))
        .route("/v0/admin/invites/{code}", axum::routing::delete(admin_revoke_invite))
        .route("/v0/admin/users", get(admin_users))
        .route("/v0/admin/wipe", post(admin_wipe))
        .route("/v0/admin/users/{identity}", axum::routing::delete(admin_delete_user))
        .route("/v0/admin/blocked/{identity}", axum::routing::delete(admin_unblock))
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

// ---------- адмін-панель ----------

#[derive(serde::Deserialize)]
struct LoginReq {
    password: String,
}

async fn admin_login(State(app): State<Shared>, Json(req): Json<LoginReq>) -> axum::response::Response {
    use axum::response::IntoResponse;
    let a = app.clone();
    let r = tokio::task::spawn_blocking(move || a.admin.login(&data_dir(), &req.password, now_ms())).await;
    match r {
        Ok(admin::Login::Ok(token)) => {
            (StatusCode::NO_CONTENT, [(header::SET_COOKIE, admin::set_cookie(&token))]).into_response()
        }
        Ok(admin::Login::Wrong) => (StatusCode::UNAUTHORIZED, "wrong-password").into_response(),
        Ok(admin::Login::Locked(s)) => (StatusCode::TOO_MANY_REQUESTS, format!("locked:{s}")).into_response(),
        Ok(admin::Login::NotConfigured) => (StatusCode::NOT_FOUND, "not-configured").into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

async fn admin_logout(State(app): State<Shared>, headers: HeaderMap) -> impl axum::response::IntoResponse {
    app.admin.logout(&headers);
    (StatusCode::NO_CONTENT, [(header::SET_COOKIE, admin::clear_cookie())])
}

fn admin_guard(app: &App, headers: &HeaderMap) -> Result<(), StatusCode> {
    app.admin.check(&data_dir(), headers, now_ms())
}

#[derive(serde::Serialize)]
struct Settings {
    registration: Mode,
    user_invite_limit: usize,
}

#[derive(serde::Serialize)]
struct ServerInfo {
    version: &'static str,
    commit: &'static str,
    started: u64,
    now: u64,
    turn: Vec<String>,
    web_push: bool,
    live_connections: usize,
    db_bytes: u64,
    disk_free: Option<u64>,
    disk_total: Option<u64>,
}

#[derive(serde::Serialize)]
struct Overview {
    settings: Settings,
    stats: db::Stats,
    server: ServerInfo,
}

async fn admin_overview(State(app): State<Shared>, headers: HeaderMap) -> Result<Json<Overview>, StatusCode> {
    admin_guard(&app, &headers)?;
    let dir = data_dir();
    let db_bytes = ["shepit.db", "shepit.db-wal"].iter().filter_map(|f| std::fs::metadata(dir.join(f)).ok()).map(|m| m.len()).sum();
    let disk = admin::disk_space(&dir);
    let live = app.bells.lock().unwrap().values().map(|b| b.receiver_count()).sum();
    Ok(Json(Overview {
        settings: Settings { registration: app.mode(), user_invite_limit: app.user_invite_limit() },
        stats: app.db.stats(now_ms()).map_err(internal)?,
        server: ServerInfo {
            version: env!("CARGO_PKG_VERSION"),
            commit: option_env!("SHEPIT_COMMIT").unwrap_or("dev"),
            started: app.started,
            now: now_ms(),
            turn: app.turn.as_ref().map(|t| t.urls.clone()).unwrap_or_default(),
            web_push: cfg!(feature = "push"),
            live_connections: live,
            db_bytes,
            disk_free: disk.map(|d| d.0),
            disk_total: disk.map(|d| d.1),
        },
    }))
}

#[derive(serde::Deserialize)]
struct SettingsReq {
    registration: Option<Mode>,
    user_invite_limit: Option<usize>,
}

async fn admin_settings(State(app): State<Shared>, headers: HeaderMap, Json(req): Json<SettingsReq>) -> StatusCode {
    if let Err(s) = admin_guard(&app, &headers) {
        return s;
    }
    let r = (|| {
        if let Some(m) = req.registration {
            let v = match m { Mode::Open => "open", Mode::Invite => "invite", Mode::Closed => "closed" };
            app.db.set_setting("registration", v)?;
            println!("Адмін: режим реєстрації → {v}");
        }
        if let Some(n) = req.user_invite_limit {
            app.db.set_setting("user_invite_limit", &n.min(1000).to_string())?;
        }
        Ok::<_, rusqlite::Error>(())
    })();
    r.map(|_| StatusCode::NO_CONTENT).unwrap_or_else(internal)
}

#[derive(serde::Serialize)]
struct AdminInvite {
    #[serde(flatten)]
    inv: db::Invite,
    /// Чинний зараз (не відкликаний, не прострочений, є невикористані).
    active: bool,
}

async fn admin_invites(State(app): State<Shared>, headers: HeaderMap) -> Result<Json<Vec<AdminInvite>>, StatusCode> {
    admin_guard(&app, &headers)?;
    let now = now_ms();
    let list = app.db.invites(500).map_err(internal)?;
    Ok(Json(list.into_iter().map(|inv| {
        let active = !inv.revoked && inv.uses < inv.max_uses && inv.expires.is_none_or(|e| e > now);
        AdminInvite { inv, active }
    }).collect()))
}

#[derive(serde::Deserialize)]
struct NewInvite {
    #[serde(default)]
    note: String,
    max_uses: u32,
    /// Скільки днів діє; None — безстроково.
    days: Option<u32>,
}

async fn admin_create_invite(State(app): State<Shared>, headers: HeaderMap, Json(req): Json<NewInvite>) -> Result<Json<db::Invite>, StatusCode> {
    admin_guard(&app, &headers)?;
    if req.max_uses == 0 || req.max_uses > 10_000 || req.note.chars().count() > 200 || req.days == Some(0) {
        return Err(StatusCode::BAD_REQUEST);
    }
    let now = now_ms();
    let inv = db::Invite {
        code: admin::new_invite_code(), created: now, creator: None, note: req.note, max_uses: req.max_uses,
        uses: 0, expires: req.days.map(|d| now + d as u64 * db::DAY_MS), revoked: false,
    };
    app.db.create_invite(&inv).map_err(internal)?;
    Ok(Json(inv))
}

async fn admin_revoke_invite(State(app): State<Shared>, headers: HeaderMap, Path(code): Path<String>) -> StatusCode {
    if let Err(s) = admin_guard(&app, &headers) {
        return s;
    }
    match app.db.revoke_invite(&code) {
        Ok(1..) => StatusCode::NO_CONTENT,
        Ok(_) => StatusCode::NOT_FOUND,
        Err(e) => internal(e),
    }
}

#[derive(serde::Serialize)]
struct AdminUser {
    #[serde(flatten)]
    acc: db::Account,
    /// «Мій ID», який користувач бачить у налаштуваннях.
    fingerprint: String,
}

#[derive(serde::Serialize)]
struct AdminBlocked {
    identity: String,
    fingerprint: String,
    at: u64,
}

#[derive(serde::Serialize)]
struct AdminUsers {
    users: Vec<AdminUser>,
    blocked: Vec<AdminBlocked>,
}

fn fingerprint_hex(identity: &str) -> String {
    hex::decode(identity).map(|b| shepit_core::wire::fingerprint(&b)).unwrap_or_default()
}

async fn admin_users(State(app): State<Shared>, headers: HeaderMap) -> Result<Json<AdminUsers>, StatusCode> {
    admin_guard(&app, &headers)?;
    let users = app.db.accounts().map_err(internal)?.into_iter()
        .map(|acc| AdminUser { fingerprint: fingerprint_hex(&acc.identity), acc })
        .collect();
    let blocked = app.db.blocked().map_err(internal)?.into_iter()
        .map(|(identity, at)| AdminBlocked { fingerprint: fingerprint_hex(&identity), identity, at })
        .collect();
    Ok(Json(AdminUsers { users, blocked }))
}

#[derive(serde::Deserialize)]
struct DeleteQuery {
    /// `1` / `true` — заборонити повторну реєстрацію.
    block: Option<String>,
}

async fn admin_delete_user(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path(identity): Path<String>,
    axum::extract::Query(q): axum::extract::Query<DeleteQuery>,
) -> StatusCode {
    if let Err(s) = admin_guard(&app, &headers) {
        return s;
    }
    let Ok(bytes) = hex::decode(&identity) else { return StatusCode::BAD_REQUEST };
    let mailbox = MailboxId::for_identity(&bytes);
    let block = matches!(q.block.as_deref(), Some("1" | "true"));
    match app.db.delete_account(&identity, &mailbox, block, now_ms()) {
        Ok(true) => {
            // Закрити відкриті потоки подій цього користувача.
            app.bells.lock().unwrap().remove(&mailbox);
            app.seen.lock().unwrap().remove(&identity);
            app.presence.forget(&identity);
            println!("Адмін: видалено акаунт {}…{}", &identity[..identity.len().min(10)], if block { " (заблоковано)" } else { "" });
            StatusCode::NO_CONTENT
        }
        Ok(false) => StatusCode::NOT_FOUND,
        Err(e) => internal(e),
    }
}

#[derive(serde::Deserialize)]
struct WipeReq {
    password: String,
}

/// «Почати з чистої сторінки» — видалити всіх користувачів. Потрібна сесія адміністратора
/// і ще раз пароль (щоб не натиснути випадково чи з чужого відкритого браузера).
async fn admin_wipe(State(app): State<Shared>, headers: HeaderMap, Json(req): Json<WipeReq>) -> axum::response::Response {
    use axum::response::IntoResponse;
    if let Err(s) = admin_guard(&app, &headers) {
        return s.into_response();
    }
    let ok = tokio::task::spawn_blocking(move || admin::stored_hash(&data_dir()).is_some_and(|h| admin::verify(&h, &req.password)))
        .await
        .unwrap_or(false);
    if !ok {
        // 403, не 401: сесія жива, просто пароль не той.
        return (StatusCode::FORBIDDEN, "wrong-password").into_response();
    }
    match app.db.wipe_users() {
        Ok(n) => {
            app.bells.lock().unwrap().clear();
            app.seen.lock().unwrap().clear();
            app.presence.clear();
            println!("Адмін: чиста сторінка — видалено акаунтів: {n}");
            Json(serde_json::json!({ "deleted": n })).into_response()
        }
        Err(e) => internal(e).into_response(),
    }
}

async fn admin_unblock(State(app): State<Shared>, headers: HeaderMap, Path(identity): Path<String>) -> StatusCode {
    if let Err(s) = admin_guard(&app, &headers) {
        return s;
    }
    match app.db.unblock(&identity) {
        Ok(1..) => StatusCode::NO_CONTENT,
        Ok(_) => StatusCode::NOT_FOUND,
        Err(e) => internal(e),
    }
}

/// Канали для адмінки: публічні — з описом, зашифровані — лише розмір і кількість.
async fn admin_channels(State(app): State<Shared>, headers: HeaderMap) -> Result<Json<Vec<db::Channel>>, StatusCode> {
    admin_guard(&app, &headers)?;
    let mut list = app.db.channels().map_err(internal)?;
    for c in &mut list {
        if !c.public {
            c.meta.clear(); // шифротекст адміну ні до чого
        }
    }
    Ok(Json(list))
}

async fn admin_delete_channel(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> StatusCode {
    if let Err(s) = admin_guard(&app, &headers) {
        return s;
    }
    match app.db.delete_channel(&id) {
        Ok(1..) => {
            println!("Адмін: видалено канал {id}");
            StatusCode::NO_CONTENT
        }
        Ok(_) => StatusCode::NOT_FOUND,
        Err(e) => internal(e),
    }
}

#[tokio::main]
async fn main() {
    if std::env::args().nth(1).as_deref() == Some("admin-password") {
        if let Err(e) = admin::set_password_cli(&data_dir()) {
            eprintln!("Помилка: {e}");
            std::process::exit(1);
        }
        return;
    }
    let addr: SocketAddr = std::env::var("SHEPIT_LISTEN")
        .unwrap_or_else(|_| "0.0.0.0:8443".into())
        .parse()
        .expect("SHEPIT_LISTEN: invalid address");
    let issuer = load_issuer();
    let db = db::Db::open(&data_dir().join("shepit.db")).expect("open shepit.db");
    println!("Корінь довіри: {}", issuer.trust_root_hex());
    println!(
        "Адмін-панель: {}",
        if admin::configured(&data_dir()) { "увімкнена (/admin/)" } else { "вимкнена — задай пароль: shepit-server admin-password" }
    );
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    println!("shepit-server listening on {addr}");
    let (tx, rx) = tokio::sync::watch::channel(false);
    axum::serve(listener, app(issuer, db, rx))
        .with_graceful_shutdown(shutdown_signal(tx))
        .await
        .unwrap();
}
