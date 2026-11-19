//! Сховище сервера: SQLite у `$SHEPIT_DATA/shepit.db`.
//!
//! Тут лише те, що сервер і так бачить: опубліковані публічні ключі та
//! зашифровані конверти до моменту доставки. Доставлені конверти видаляються.

use std::{path::Path, sync::Mutex};

use rusqlite::{params, Connection, OptionalExtension};
use shepit_core::wire::{Envelope, KeyUpload, MailboxId, MailboxItem, PreKeyBundle};

pub struct Db(Mutex<Connection>);

#[derive(Clone, Debug)]
pub struct PushSub {
    pub endpoint: String,
    pub p256dh: String,
    pub auth: String,
}

pub const DAY_MS: u64 = 24 * 60 * 60 * 1000;

#[derive(Debug, PartialEq, Eq)]
pub enum Register {
    Ok,
    BadInvite,
    Blocked,
}

#[derive(Clone, Debug, serde::Serialize)]
pub struct Account {
    pub identity: String,
    pub created: u64,
    /// День останньої активності (мс початку дня); 0 — невідомо.
    pub last_seen: u64,
    pub invite: Option<String>,
    /// Хто створив запрошення: None — адмін або без запрошення.
    pub invited_by: Option<String>,
}

#[derive(Clone, Debug, serde::Serialize)]
pub struct Invite {
    pub code: String,
    pub created: u64,
    /// `None` — створив адміністратор. Для запрошень користувачів — його ідентичність.
    pub creator: Option<String>,
    pub note: String,
    pub max_uses: u32,
    pub uses: u32,
    pub expires: Option<u64>,
    pub revoked: bool,
}

#[derive(Debug, serde::Serialize)]
pub struct Stats {
    pub users: u64,
    pub new_7d: u64,
    pub active_1d: u64,
    pub active_7d: u64,
    pub active_30d: u64,
    pub queued: u64,
    pub queued_bytes: u64,
    pub mailboxes_waiting: u64,
    pub push_subs: u64,
    pub invites_active: u64,
    pub channels: u64,
    pub channels_public: u64,
    pub channel_posts: u64,
}

#[derive(Clone, Debug, serde::Serialize)]
pub struct Channel {
    pub id: String,
    #[serde(rename = "pub")]
    pub pub_key: String,
    pub public: bool,
    pub meta: String,
    pub created: u64,
    pub updated: u64,
    pub posts: u64,
    pub last_seq: i64,
    pub bytes: u64,
}

#[derive(Clone, Debug, serde::Serialize)]
pub struct Post {
    pub seq: i64,
    pub at: u64,
    pub data: String,
}

const CHANNEL_SELECT: &str = "SELECT c.id, c.pub, c.public, c.meta, c.created, c.updated,
        (SELECT COUNT(*) FROM channel_posts p WHERE p.channel = c.id),
        (SELECT COALESCE(MAX(seq), 0) FROM channel_posts p WHERE p.channel = c.id),
        (SELECT COALESCE(SUM(LENGTH(data)), 0) FROM channel_posts p WHERE p.channel = c.id)
     FROM channels c";

fn channel_row(r: &rusqlite::Row) -> rusqlite::Result<Channel> {
    Ok(Channel {
        id: r.get(0)?,
        pub_key: r.get(1)?,
        public: r.get::<_, i64>(2)? != 0,
        meta: r.get(3)?,
        created: r.get::<_, i64>(4)? as u64,
        updated: r.get::<_, i64>(5)? as u64,
        posts: r.get::<_, i64>(6)? as u64,
        last_seq: r.get(7)?,
        bytes: r.get::<_, i64>(8)? as u64 + r.get::<_, String>(3)?.len() as u64,
    })
}

/// Скільки зберігати недоставлені конверти.
const RETENTION_MS: u64 = 30 * 24 * 60 * 60 * 1000;
/// Великі конверти (фото, голосові) — лише транзит: максимум 3 дні, потім зникають.
const MEDIA_RETENTION_MS: u64 = 3 * 24 * 60 * 60 * 1000;
/// Розмір збереженого конверта (JSON), після якого він вважається медіа.
pub const MEDIA_BYTES: i64 = 32 * 1024;
/// Скільки байтів конвертів може одночасно чекати в одній скриньці.
const MAX_MAILBOX_BYTES: i64 = 64 * 1024 * 1024;
/// Скільки байтів віддавати за один запит скриньки.
const MAX_LIST_BYTES: usize = 6 * 1024 * 1024;

impl Db {
    pub fn open(path: &Path) -> rusqlite::Result<Self> {
        let c = Connection::open(path)?;
        c.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA synchronous = NORMAL;
             PRAGMA secure_delete = ON;
             PRAGMA journal_size_limit = 1048576;
             CREATE TABLE IF NOT EXISTS keys (
                 identity TEXT PRIMARY KEY,
                 upload   TEXT NOT NULL
             );
             CREATE TABLE IF NOT EXISTS envelopes (
                 id       INTEGER PRIMARY KEY AUTOINCREMENT,
                 mailbox  TEXT NOT NULL,
                 envelope TEXT NOT NULL,
                 created  INTEGER NOT NULL
             );
             CREATE INDEX IF NOT EXISTS envelopes_mailbox ON envelopes(mailbox, id);
             CREATE TABLE IF NOT EXISTS push_subs (
                 endpoint TEXT PRIMARY KEY,
                 mailbox  TEXT NOT NULL,
                 p256dh   TEXT NOT NULL,
                 auth     TEXT NOT NULL
             );
             CREATE INDEX IF NOT EXISTS push_subs_mailbox ON push_subs(mailbox);
             -- Акаунт = ідентичність, якій сервер дозволив публікувати ключі.
             -- last_seen — лише день (не точний час), щоб не накопичувати зайвих метаданих.
             CREATE TABLE IF NOT EXISTS accounts (
                 identity  TEXT PRIMARY KEY,
                 created   INTEGER NOT NULL,
                 invite    TEXT,
                 last_seen INTEGER NOT NULL DEFAULT 0
             );
             -- Існуючі до появи акаунтів ключі — вже зареєстровані користувачі.
             INSERT OR IGNORE INTO accounts(identity, created) SELECT identity, 0 FROM keys;
             CREATE TABLE IF NOT EXISTS invites (
                 code     TEXT PRIMARY KEY,
                 created  INTEGER NOT NULL,
                 creator  TEXT,            -- NULL = адміністратор
                 note     TEXT NOT NULL DEFAULT '',
                 max_uses INTEGER NOT NULL,
                 uses     INTEGER NOT NULL DEFAULT 0,
                 expires  INTEGER,         -- мс; NULL = безстроково
                 revoked  INTEGER NOT NULL DEFAULT 0
             );
             CREATE INDEX IF NOT EXISTS invites_creator ON invites(creator);
             -- Видалені адміністратором ключі: повторна реєстрація заборонена.
             CREATE TABLE IF NOT EXISTS blocked (
                 identity TEXT PRIMARY KEY,
                 at       INTEGER NOT NULL
             );
             -- Канали: опис і дописи — шифротекст (зашифровані) або JSON (публічні).
             CREATE TABLE IF NOT EXISTS channels (
                 id      TEXT PRIMARY KEY,
                 pub     TEXT NOT NULL,
                 public  INTEGER NOT NULL,
                 meta    TEXT NOT NULL,
                 created INTEGER NOT NULL,
                 updated INTEGER NOT NULL
             );
             CREATE TABLE IF NOT EXISTS channel_posts (
                 seq     INTEGER PRIMARY KEY AUTOINCREMENT,
                 channel TEXT NOT NULL,
                 at      INTEGER NOT NULL,
                 data    TEXT NOT NULL
             );
             CREATE INDEX IF NOT EXISTS channel_posts_channel ON channel_posts(channel, seq);
             CREATE TABLE IF NOT EXISTS settings (
                 key   TEXT PRIMARY KEY,
                 value TEXT NOT NULL
             );",
        )?;
        Ok(Self(Mutex::new(c)))
    }

    // ---------- акаунти й реєстрація ----------

    pub fn is_account(&self, identity: &str) -> rusqlite::Result<bool> {
        Ok(self.0.lock().unwrap()
            .query_row("SELECT 1 FROM accounts WHERE identity = ?1", [identity], |_| Ok(()))
            .optional()?
            .is_some())
    }

    /// Зареєструвати нову ідентичність. З кодом — атомарно перевірити й погасити запрошення.
    pub fn register(&self, identity: &str, invite: Option<&str>, now_ms: u64) -> rusqlite::Result<Register> {
        let mut c = self.0.lock().unwrap();
        let tx = c.transaction()?;
        if tx.query_row("SELECT 1 FROM accounts WHERE identity = ?1", [identity], |_| Ok(())).optional()?.is_some() {
            return Ok(Register::Ok);
        }
        if tx.query_row("SELECT 1 FROM blocked WHERE identity = ?1", [identity], |_| Ok(())).optional()?.is_some() {
            return Ok(Register::Blocked);
        }
        if let Some(code) = invite {
            let n = tx.execute(
                "UPDATE invites SET uses = uses + 1
                 WHERE code = ?1 AND revoked = 0 AND uses < max_uses AND (expires IS NULL OR expires > ?2)",
                params![code, now_ms as i64],
            )?;
            if n == 0 {
                return Ok(Register::BadInvite);
            }
        }
        tx.execute(
            "INSERT INTO accounts(identity, created, invite, last_seen) VALUES (?1, ?2, ?3, ?4)",
            params![identity, now_ms as i64, invite, (now_ms / DAY_MS) as i64],
        )?;
        tx.commit()?;
        Ok(Register::Ok)
    }

    /// Позначити, що акаунт був активний сьогодні.
    pub fn touch(&self, identity: &str, day: u64) -> rusqlite::Result<()> {
        self.0.lock().unwrap().execute(
            "UPDATE accounts SET last_seen = ?2 WHERE identity = ?1 AND last_seen < ?2",
            params![identity, day as i64],
        )?;
        Ok(())
    }

    pub fn setting(&self, key: &str) -> rusqlite::Result<Option<String>> {
        self.0.lock().unwrap().query_row("SELECT value FROM settings WHERE key = ?1", [key], |r| r.get(0)).optional()
    }

    pub fn set_setting(&self, key: &str, value: &str) -> rusqlite::Result<()> {
        self.0.lock().unwrap().execute(
            "INSERT INTO settings(key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    pub fn accounts(&self) -> rusqlite::Result<Vec<Account>> {
        let c = self.0.lock().unwrap();
        let mut st = c.prepare(
            "SELECT a.identity, a.created, a.last_seen, a.invite, i.creator
             FROM accounts a LEFT JOIN invites i ON i.code = a.invite ORDER BY a.created DESC",
        )?;
        let rows = st.query_map([], |r| {
            Ok(Account {
                identity: r.get(0)?,
                created: r.get::<_, i64>(1)? as u64,
                last_seen: r.get::<_, i64>(2)? as u64 * DAY_MS,
                invite: r.get(3)?,
                invited_by: r.get(4)?,
            })
        })?;
        rows.collect()
    }

    /// Видалити акаунт з усіма даними на сервері: ключі, недоставлена пошта, підписки,
    /// невикористані запрошення. З `block` — заборонити повторну реєстрацію цим ключем.
    pub fn delete_account(&self, identity: &str, mailbox: &MailboxId, block: bool, now_ms: u64) -> rusqlite::Result<bool> {
        let mut c = self.0.lock().unwrap();
        let tx = c.transaction()?;
        let n = tx.execute("DELETE FROM accounts WHERE identity = ?1", [identity])?;
        tx.execute("DELETE FROM keys WHERE identity = ?1", [identity])?;
        tx.execute("DELETE FROM envelopes WHERE mailbox = ?1", [mailbox.to_hex()])?;
        tx.execute("DELETE FROM push_subs WHERE mailbox = ?1", [mailbox.to_hex()])?;
        tx.execute("UPDATE invites SET revoked = 1 WHERE creator = ?1", [identity])?;
        if block {
            tx.execute("INSERT OR REPLACE INTO blocked(identity, at) VALUES (?1, ?2)", params![identity, now_ms as i64])?;
        }
        tx.commit()?;
        Ok(n > 0 || block)
    }

    /// «Чиста сторінка»: усі користувачі з усіма їхніми даними, канали, запрошення, блокування.
    /// Налаштування (режим реєстрації, ліміти) лишаються. Повертає, скільки акаунтів було.
    pub fn wipe_users(&self) -> rusqlite::Result<usize> {
        let mut c = self.0.lock().unwrap();
        let tx = c.transaction()?;
        let n = tx.execute("DELETE FROM accounts", [])?;
        for t in ["keys", "envelopes", "push_subs", "invites", "blocked", "channel_posts", "channels"] {
            tx.execute(&format!("DELETE FROM {t}"), [])?;
        }
        tx.commit()?;
        let _ = c.execute_batch("PRAGMA wal_checkpoint(TRUNCATE); VACUUM;");
        Ok(n)
    }

    pub fn is_blocked(&self, identity: &str) -> rusqlite::Result<bool> {
        Ok(self.0.lock().unwrap()
            .query_row("SELECT 1 FROM blocked WHERE identity = ?1", [identity], |_| Ok(()))
            .optional()?
            .is_some())
    }

    pub fn blocked(&self) -> rusqlite::Result<Vec<(String, u64)>> {
        let c = self.0.lock().unwrap();
        let mut st = c.prepare("SELECT identity, at FROM blocked ORDER BY at DESC")?;
        let rows = st.query_map([], |r| Ok((r.get(0)?, r.get::<_, i64>(1)? as u64)))?;
        rows.collect()
    }

    pub fn unblock(&self, identity: &str) -> rusqlite::Result<usize> {
        self.0.lock().unwrap().execute("DELETE FROM blocked WHERE identity = ?1", [identity])
    }

    // ---------- канали ----------

    pub fn create_channel(&self, id: &str, pub_key: &str, public: bool, meta: &str, now_ms: u64) -> rusqlite::Result<()> {
        self.0.lock().unwrap().execute(
            "INSERT INTO channels(id, pub, public, meta, created, updated) VALUES (?1, ?2, ?3, ?4, ?5, ?5)",
            params![id, pub_key, public as i64, meta, now_ms as i64],
        )?;
        Ok(())
    }

    pub fn update_channel_meta(&self, id: &str, meta: &str, now_ms: u64) -> rusqlite::Result<()> {
        self.0.lock().unwrap().execute(
            "UPDATE channels SET meta = ?2, updated = ?3 WHERE id = ?1",
            params![id, meta, now_ms as i64],
        )?;
        Ok(())
    }

    pub fn channel(&self, id: &str) -> rusqlite::Result<Option<Channel>> {
        self.0.lock().unwrap().query_row(&format!("{CHANNEL_SELECT} WHERE c.id = ?1"), [id], channel_row).optional()
    }

    pub fn channels(&self) -> rusqlite::Result<Vec<Channel>> {
        let c = self.0.lock().unwrap();
        let mut st = c.prepare(&format!("{CHANNEL_SELECT} ORDER BY c.updated DESC"))?;
        let rows = st.query_map([], channel_row)?;
        rows.collect()
    }

    /// Пошук публічних каналів за назвою й описом (без урахування регістру, і для кирилиці).
    pub fn search_public_channels(&self, q: &str, limit: usize) -> rusqlite::Result<Vec<Channel>> {
        let all = {
            let c = self.0.lock().unwrap();
            let mut st = c.prepare(&format!("{CHANNEL_SELECT} WHERE c.public = 1 ORDER BY c.updated DESC"))?;
            let rows = st.query_map([], channel_row)?;
            rows.collect::<rusqlite::Result<Vec<_>>>()?
        };
        let q = q.to_lowercase();
        Ok(all
            .into_iter()
            .filter(|c| {
                let m: serde_json::Value = serde_json::from_str(&c.meta).unwrap_or_default();
                let text = format!("{} {}", m["name"].as_str().unwrap_or(""), m["about"].as_str().unwrap_or("")).to_lowercase();
                text.contains(&q)
            })
            .take(limit)
            .collect())
    }

    pub fn delete_channel(&self, id: &str) -> rusqlite::Result<usize> {
        let mut c = self.0.lock().unwrap();
        let tx = c.transaction()?;
        tx.execute("DELETE FROM channel_posts WHERE channel = ?1", [id])?;
        let n = tx.execute("DELETE FROM channels WHERE id = ?1", [id])?;
        tx.commit()?;
        Ok(n)
    }

    pub fn add_post(&self, channel: &str, data: &str, now_ms: u64) -> rusqlite::Result<i64> {
        let c = self.0.lock().unwrap();
        c.execute(
            "INSERT INTO channel_posts(channel, at, data) VALUES (?1, ?2, ?3)",
            params![channel, now_ms as i64, data],
        )?;
        c.execute("UPDATE channels SET updated = ?2 WHERE id = ?1", params![channel, now_ms as i64])?;
        Ok(c.last_insert_rowid())
    }

    pub fn delete_post(&self, channel: &str, seq: i64) -> rusqlite::Result<usize> {
        self.0.lock().unwrap().execute("DELETE FROM channel_posts WHERE channel = ?1 AND seq = ?2", params![channel, seq])
    }

    /// Дописи за зростанням номера: після `after`, або останні `limit` до `before`.
    pub fn posts(&self, channel: &str, after: Option<i64>, before: Option<i64>, limit: usize) -> rusqlite::Result<Vec<Post>> {
        let c = self.0.lock().unwrap();
        let row = |r: &rusqlite::Row| Ok(Post { seq: r.get(0)?, at: r.get::<_, i64>(1)? as u64, data: r.get(2)? });
        let mut out: Vec<Post> = if let Some(a) = after {
            let mut st = c.prepare("SELECT seq, at, data FROM channel_posts WHERE channel = ?1 AND seq > ?2 ORDER BY seq LIMIT ?3")?;
            let rows = st.query_map(params![channel, a, limit as i64], row)?;
            rows.collect::<rusqlite::Result<_>>()?
        } else {
            let mut st = c.prepare("SELECT seq, at, data FROM channel_posts WHERE channel = ?1 AND seq < ?2 ORDER BY seq DESC LIMIT ?3")?;
            let rows = st.query_map(params![channel, before.unwrap_or(i64::MAX), limit as i64], row)?;
            rows.collect::<rusqlite::Result<_>>()?
        };
        if after.is_none() {
            out.reverse();
        }
        Ok(out)
    }

    // ---------- запрошення ----------

    pub fn create_invite(&self, inv: &Invite) -> rusqlite::Result<()> {
        self.0.lock().unwrap().execute(
            "INSERT INTO invites(code, created, creator, note, max_uses, uses, expires, revoked) VALUES (?1, ?2, ?3, ?4, ?5, 0, ?6, 0)",
            params![inv.code, inv.created as i64, inv.creator, inv.note, inv.max_uses as i64, inv.expires.map(|e| e as i64)],
        )?;
        Ok(())
    }

    /// Чинне (не використане до кінця, не відкликане, не прострочене) запрошення користувача
    /// і скільки всього він їх створив.
    pub fn user_invite(&self, creator: &str, now_ms: u64) -> rusqlite::Result<(Option<String>, usize)> {
        let c = self.0.lock().unwrap();
        let active = c.query_row(
            "SELECT code FROM invites WHERE creator = ?1 AND revoked = 0 AND uses < max_uses
               AND (expires IS NULL OR expires > ?2) ORDER BY created LIMIT 1",
            params![creator, now_ms as i64],
            |r| r.get(0),
        ).optional()?;
        let total: i64 = c.query_row("SELECT COUNT(*) FROM invites WHERE creator = ?1", [creator], |r| r.get(0))?;
        Ok((active, total as usize))
    }

    pub fn invites(&self, limit: usize) -> rusqlite::Result<Vec<Invite>> {
        let c = self.0.lock().unwrap();
        let mut st = c.prepare(
            "SELECT code, created, creator, note, max_uses, uses, expires, revoked FROM invites ORDER BY created DESC LIMIT ?1",
        )?;
        let rows = st.query_map([limit as i64], |r| {
            Ok(Invite {
                code: r.get(0)?,
                created: r.get::<_, i64>(1)? as u64,
                creator: r.get(2)?,
                note: r.get(3)?,
                max_uses: r.get::<_, i64>(4)? as u32,
                uses: r.get::<_, i64>(5)? as u32,
                expires: r.get::<_, Option<i64>>(6)?.map(|e| e as u64),
                revoked: r.get::<_, i64>(7)? != 0,
            })
        })?;
        rows.collect()
    }

    pub fn revoke_invite(&self, code: &str) -> rusqlite::Result<usize> {
        self.0.lock().unwrap().execute("UPDATE invites SET revoked = 1 WHERE code = ?1", [code])
    }

    // ---------- статистика ----------

    pub fn stats(&self, now_ms: u64) -> rusqlite::Result<Stats> {
        let c = self.0.lock().unwrap();
        let today = (now_ms / DAY_MS) as i64;
        let one = |sql: &str, p: i64| -> rusqlite::Result<i64> { c.query_row(sql, [p], |r| r.get(0)) };
        let active = |days: i64| one("SELECT COUNT(*) FROM accounts WHERE last_seen > ?1", today - days);
        let (queued, queued_bytes): (i64, i64) = c.query_row(
            "SELECT COUNT(*), COALESCE(SUM(LENGTH(envelope)), 0) FROM envelopes", [], |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        Ok(Stats {
            users: one("SELECT COUNT(*) FROM accounts WHERE ?1 = ?1", 0)? as u64,
            new_7d: one("SELECT COUNT(*) FROM accounts WHERE created > ?1", now_ms as i64 - 7 * DAY_MS as i64)? as u64,
            active_1d: active(1)? as u64,
            active_7d: active(7)? as u64,
            active_30d: active(30)? as u64,
            queued: queued as u64,
            queued_bytes: queued_bytes as u64,
            mailboxes_waiting: one("SELECT COUNT(DISTINCT mailbox) FROM envelopes WHERE ?1 = ?1", 0)? as u64,
            push_subs: one("SELECT COUNT(*) FROM push_subs WHERE ?1 = ?1", 0)? as u64,
            channels: one("SELECT COUNT(*) FROM channels WHERE ?1 = ?1", 0)? as u64,
            channels_public: one("SELECT COUNT(*) FROM channels WHERE public = ?1", 1)? as u64,
            channel_posts: one("SELECT COUNT(*) FROM channel_posts WHERE ?1 = ?1", 0)? as u64,
            invites_active: c.query_row(
                "SELECT COUNT(*) FROM invites WHERE revoked = 0 AND uses < max_uses AND (expires IS NULL OR expires > ?1)",
                [now_ms as i64], |r| r.get::<_, i64>(0),
            )? as u64,
        })
    }

    pub fn put_keys(&self, up: &KeyUpload) -> rusqlite::Result<()> {
        let json = serde_json::to_string(up).expect("serialize");
        self.0.lock().unwrap().execute(
            "INSERT INTO keys(identity, upload) VALUES (?1, ?2)
             ON CONFLICT(identity) DO UPDATE SET upload = excluded.upload",
            params![up.identity, json],
        )?;
        Ok(())
    }

    fn load_keys(c: &Connection, identity: &str) -> rusqlite::Result<Option<KeyUpload>> {
        let json: Option<String> = c
            .query_row("SELECT upload FROM keys WHERE identity = ?1", [identity], |r| r.get(0))
            .optional()?;
        Ok(json.and_then(|j| serde_json::from_str(&j).ok()))
    }

    /// Видати пакет ключів, забравши один одноразовий ключ.
    pub fn take_bundle(&self, identity: &str) -> rusqlite::Result<Option<PreKeyBundle>> {
        let mut c = self.0.lock().unwrap();
        let tx = c.transaction()?;
        let Some(mut up) = Self::load_keys(&tx, identity)? else { return Ok(None) };
        let bundle = up.take_bundle();
        tx.execute(
            "UPDATE keys SET upload = ?2 WHERE identity = ?1",
            params![identity, serde_json::to_string(&up).expect("serialize")],
        )?;
        tx.commit()?;
        Ok(Some(bundle))
    }

    /// Скільки одноразових ключів лишилось (None — ключів немає зовсім).
    pub fn one_time_left(&self, identity: &str) -> rusqlite::Result<Option<usize>> {
        Ok(Self::load_keys(&self.0.lock().unwrap(), identity)?.map(|u| u.one_time.len()))
    }

    /// Покласти конверт. `false` — скринька переповнена.
    pub fn push(&self, env: &Envelope, max_queue: usize, now_ms: u64) -> rusqlite::Result<bool> {
        let c = self.0.lock().unwrap();
        let mb = env.to.to_hex();
        let (n, bytes): (usize, i64) = c.query_row(
            "SELECT COUNT(*), COALESCE(SUM(LENGTH(envelope)), 0) FROM envelopes WHERE mailbox = ?1",
            [&mb],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        let json = serde_json::to_string(env).expect("serialize");
        if n >= max_queue || bytes + json.len() as i64 > MAX_MAILBOX_BYTES {
            return Ok(false);
        }
        c.execute(
            "INSERT INTO envelopes(mailbox, envelope, created) VALUES (?1, ?2, ?3)",
            params![mb, json, now_ms as i64],
        )?;
        Ok(true)
    }

    /// Переглянути конверти у скриньці (до `limit`). НЕ видаляє — це робить [`Db::ack`].
    pub fn list(&self, mailbox: &MailboxId, limit: usize) -> rusqlite::Result<Vec<MailboxItem>> {
        let c = self.0.lock().unwrap();
        let mut st = c.prepare("SELECT id, envelope FROM envelopes WHERE mailbox = ?1 ORDER BY id LIMIT ?2")?;
        let rows = st.query_map(params![mailbox.to_hex(), limit as i64], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?))
        })?;
        // Не більше MAX_LIST_BYTES за раз (але хоча б один конверт), решту — наступним запитом.
        let mut total = 0usize;
        let mut out = Vec::new();
        for (id, j) in rows.filter_map(|r| r.ok()) {
            total += j.len();
            if total > MAX_LIST_BYTES && !out.is_empty() {
                break;
            }
            if let Ok(envelope) = serde_json::from_str(&j) {
                out.push(MailboxItem { id, envelope });
            }
        }
        Ok(out)
    }

    /// Клієнт отримав і зберіг усе до `upto` — видалити.
    pub fn ack(&self, mailbox: &MailboxId, upto: i64) -> rusqlite::Result<usize> {
        let c = self.0.lock().unwrap();
        let mb = mailbox.to_hex();
        let media: i64 = c.query_row(
            "SELECT COUNT(*) FROM envelopes WHERE mailbox = ?1 AND id <= ?2 AND LENGTH(envelope) > ?3",
            params![mb, upto, MEDIA_BYTES],
            |r| r.get(0),
        )?;
        let n = c.execute("DELETE FROM envelopes WHERE mailbox = ?1 AND id <= ?2", params![mb, upto])?;
        if media > 0 {
            // Фото доставлене: secure_delete вже затер його сторінки; скинути й журнал.
            let _ = c.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);");
        }
        Ok(n)
    }

    /// Підписка браузера на сповіщення (Web Push) для скриньки.
    pub fn put_push(&self, mailbox: &MailboxId, s: &PushSub) -> rusqlite::Result<()> {
        self.0.lock().unwrap().execute(
            "INSERT INTO push_subs(endpoint, mailbox, p256dh, auth) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(endpoint) DO UPDATE SET mailbox = excluded.mailbox, p256dh = excluded.p256dh, auth = excluded.auth",
            params![s.endpoint, mailbox.to_hex(), s.p256dh, s.auth],
        )?;
        Ok(())
    }

    pub fn delete_push(&self, endpoint: &str) -> rusqlite::Result<usize> {
        self.0.lock().unwrap().execute("DELETE FROM push_subs WHERE endpoint = ?1", [endpoint])
    }

    #[cfg_attr(not(feature = "push"), allow(dead_code))]
    pub fn push_subs(&self, mailbox: &MailboxId) -> rusqlite::Result<Vec<PushSub>> {
        let c = self.0.lock().unwrap();
        let mut st = c.prepare("SELECT endpoint, p256dh, auth FROM push_subs WHERE mailbox = ?1")?;
        let rows = st.query_map([mailbox.to_hex()], |r| Ok(PushSub { endpoint: r.get(0)?, p256dh: r.get(1)?, auth: r.get(2)? }))?;
        rows.collect()
    }

    /// Видалити недоставлені конверти: звичайні — старші за 30 днів, медіа — за 3 дні.
    pub fn prune(&self, now_ms: u64) -> rusqlite::Result<usize> {
        let cutoff = now_ms.saturating_sub(RETENTION_MS) as i64;
        let media = now_ms.saturating_sub(MEDIA_RETENTION_MS) as i64;
        let c = self.0.lock().unwrap();
        let n = c.execute(
            "DELETE FROM envelopes WHERE created < ?1 OR (created < ?2 AND LENGTH(envelope) > ?3)",
            params![cutoff, media, MEDIA_BYTES],
        )?;
        if n > 0 {
            // Перенести видалене з журналу в основний файл і обнулити журнал.
            let _ = c.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);");
        }
        Ok(n)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use shepit_core::wire::OneTimeKey;

    fn upload(id: &str, n: u32) -> KeyUpload {
        KeyUpload {
            identity: id.into(), registration_id: 1, device_id: 1,
            signed_prekey_id: 1, signed_prekey: "aa".into(), signed_prekey_sig: "bb".into(),
            kyber_prekey_id: 1, kyber_prekey: "cc".into(), kyber_prekey_sig: "dd".into(),
            one_time: (1..=n).map(|i| OneTimeKey { id: i, key: format!("{i:02x}") }).collect(),
        }
    }

    #[test]
    fn media_is_transit_only() {
        let dir = std::env::temp_dir().join(format!("shepit-media-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("m.db");
        let _ = std::fs::remove_file(&path);
        let db = Db::open(&path).unwrap();
        let mb = MailboxId([9; 32]);
        let day = 24 * 60 * 60 * 1000;
        let big = vec![0x5a_u8; 40 * 1024];
        assert!(db.push(&Envelope::new(mb, b"text"), 10, 0).unwrap());
        assert!(db.push(&Envelope::new(mb, &big), 10, 0).unwrap());
        // Через 2 дні все ще чекає.
        assert_eq!(db.prune(2 * day).unwrap(), 0);
        // Через 3+ дні велике зникло, текст лишився.
        assert_eq!(db.prune(3 * day + 1).unwrap(), 1);
        assert_eq!(db.list(&mb, 100).unwrap().len(), 1);
        // Після доставки на диску не лишається вмісту.
        assert!(db.push(&Envelope::new(mb, &big), 10, 3 * day).unwrap());
        let last = db.list(&mb, 100).unwrap().last().unwrap().id;
        db.ack(&mb, last).unwrap();
        drop(db);
        let mut raw = std::fs::read(&path).unwrap();
        if let Ok(w) = std::fs::read(dir.join("m.db-wal")) { raw.extend(w); }
        let needle = hex::encode(&big[..64]);
        assert!(!raw.windows(needle.len()).any(|w| w == needle.as_bytes()), "фото лишилось на диску");
    }

    #[test]
    fn survives_reopen() {
        let dir = std::env::temp_dir().join(format!("shepit-db-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.db");
        let _ = std::fs::remove_file(&path);
        let mb = MailboxId([7; 32]);
        {
            let db = Db::open(&path).unwrap();
            db.put_keys(&upload("05ab", 2)).unwrap();
            assert!(db.push(&Envelope::new(mb, b"x"), 10, 1).unwrap());
        }
        let db = Db::open(&path).unwrap();
        assert_eq!(db.one_time_left("05ab").unwrap(), Some(2));
        assert!(db.take_bundle("05ab").unwrap().unwrap().one_time.is_some());
        assert_eq!(db.one_time_left("05ab").unwrap(), Some(1));
        let items = db.list(&mb, 100).unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(db.list(&mb, 100).unwrap().len(), 1, "без ack нічого не зникає");
        assert_eq!(db.ack(&mb, items[0].id).unwrap(), 1);
        assert_eq!(db.list(&mb, 100).unwrap().len(), 0);
        assert_eq!(db.one_time_left("nobody").unwrap(), None);
        drop(db);
        // Ключі, що були до появи таблиці акаунтів, стають акаунтами.
        let db = Db::open(&path).unwrap();
        assert!(db.is_account("05ab").unwrap());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn channels() {
        let dir = std::env::temp_dir().join(format!("shepit-ch-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = Db::open(&dir.join("t.db")).unwrap();
        db.create_channel("a1", "PUB", true, r#"{"name":"Новини 100%"}"#, 1).unwrap();
        db.create_channel("b2", "PUB2", false, "ciphertext", 1).unwrap();
        for i in 0..5 {
            db.add_post("a1", &format!("p{i}"), 10 + i).unwrap();
        }
        let c = db.channel("a1").unwrap().unwrap();
        assert_eq!((c.posts, c.public), (5, true));
        let last3 = db.posts("a1", None, None, 3).unwrap();
        assert_eq!(last3.iter().map(|p| p.data.as_str()).collect::<Vec<_>>(), ["p2", "p3", "p4"]);
        let older = db.posts("a1", None, Some(last3[0].seq), 10).unwrap();
        assert_eq!(older.len(), 2);
        assert_eq!(db.posts("a1", Some(last3[1].seq), None, 10).unwrap().len(), 1);
        assert_eq!(db.delete_post("a1", last3[2].seq).unwrap(), 1);
        assert_eq!(db.delete_post("b2", last3[1].seq).unwrap(), 0, "чужий канал");
        assert_eq!(db.search_public_channels("новини", 10).unwrap().len(), 1, "без урахування регістру");
        assert_eq!(db.search_public_channels("", 10).unwrap().len(), 1, "порожній запит — усі публічні");
        assert_eq!(db.search_public_channels("100%", 10).unwrap().len(), 1);
        assert_eq!(db.search_public_channels("cipher", 10).unwrap().len(), 0, "зашифровані не шукаються");
        assert_eq!(db.stats(100).unwrap().channels, 2);
        db.delete_channel("a1").unwrap();
        assert!(db.channel("a1").unwrap().is_none());
        assert!(db.posts("a1", None, None, 10).unwrap().is_empty());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn invites() {
        let dir = std::env::temp_dir().join(format!("shepit-inv-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = Db::open(&dir.join("t.db")).unwrap();
        let inv = |code: &str, creator: Option<&str>, max: u32, expires: Option<u64>| Invite {
            code: code.into(), created: 1, creator: creator.map(Into::into), note: String::new(),
            max_uses: max, uses: 0, expires, revoked: false,
        };
        db.create_invite(&inv("A", None, 2, None)).unwrap();
        db.create_invite(&inv("OLD", None, 5, Some(100))).unwrap();
        db.create_invite(&inv("U1", Some("05u"), 1, None)).unwrap();
        assert_eq!(db.register("05x", Some("NOPE"), 1000).unwrap(), Register::BadInvite);
        assert!(!db.is_account("05x").unwrap());
        assert_eq!(db.register("05x", Some("OLD"), 1000).unwrap(), Register::BadInvite, "прострочений");
        assert_eq!(db.register("05x", Some("A"), 1000).unwrap(), Register::Ok);
        assert_eq!(db.register("05x", Some("A"), 1000).unwrap(), Register::Ok, "повторна публікація ключів не витрачає код");
        assert_eq!(db.register("05y", Some("A"), 1000).unwrap(), Register::Ok);
        assert_eq!(db.register("05z", Some("A"), 1000).unwrap(), Register::BadInvite, "використано до кінця");
        assert_eq!(db.user_invite("05u", 1000).unwrap(), (Some("U1".into()), 1));
        assert_eq!(db.register("05z", Some("U1"), 1000).unwrap(), Register::Ok);
        assert_eq!(db.user_invite("05u", 1000).unwrap(), (None, 1));
        db.create_invite(&inv("B", None, 9, None)).unwrap();
        db.revoke_invite("B").unwrap();
        assert_eq!(db.register("05w", Some("B"), 1000).unwrap(), Register::BadInvite, "відкликаний");
        let s = db.stats(1000).unwrap();
        assert_eq!(s.users, 3);
        assert_eq!(s.invites_active, 0);
        // видалення з блокуванням
        let list = db.accounts().unwrap();
        assert_eq!(list.iter().find(|a| a.identity == "05z").unwrap().invited_by.as_deref(), Some("05u"));
        db.put_keys(&upload("05x", 1)).unwrap();
        let mb = MailboxId([9; 32]);
        db.push(&Envelope::new(mb, b"x"), 10, 1).unwrap();
        db.create_invite(&inv("X1", Some("05x"), 1, None)).unwrap();
        assert!(db.delete_account("05x", &mb, true, 2000).unwrap());
        assert!(!db.is_account("05x").unwrap());
        assert_eq!(db.one_time_left("05x").unwrap(), None);
        assert!(db.list(&mb, 10).unwrap().is_empty());
        assert_eq!(db.register("05q", Some("X1"), 3000).unwrap(), Register::BadInvite, "запрошення видаленого відкликано");
        assert_eq!(db.register("05x", None, 3000).unwrap(), Register::Blocked);
        assert_eq!(db.blocked().unwrap().len(), 1);
        db.unblock("05x").unwrap();
        assert_eq!(db.register("05x", None, 3000).unwrap(), Register::Ok);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
