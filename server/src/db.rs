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
}

/// Скільки зберігати недоставлені конверти.
const RETENTION_MS: u64 = 30 * 24 * 60 * 60 * 1000;

impl Db {
    pub fn open(path: &Path) -> rusqlite::Result<Self> {
        let c = Connection::open(path)?;
        c.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA synchronous = NORMAL;
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
        let n: usize = c.query_row("SELECT COUNT(*) FROM envelopes WHERE mailbox = ?1", [&mb], |r| r.get(0))?;
        if n >= max_queue {
            return Ok(false);
        }
        c.execute(
            "INSERT INTO envelopes(mailbox, envelope, created) VALUES (?1, ?2, ?3)",
            params![mb, serde_json::to_string(env).expect("serialize"), now_ms as i64],
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
        Ok(rows
            .filter_map(|r| r.ok())
            .filter_map(|(id, j)| Some(MailboxItem { id, envelope: serde_json::from_str(&j).ok()? }))
            .collect())
    }

    /// Клієнт отримав і зберіг усе до `upto` — видалити.
    pub fn ack(&self, mailbox: &MailboxId, upto: i64) -> rusqlite::Result<usize> {
        self.0
            .lock()
            .unwrap()
            .execute("DELETE FROM envelopes WHERE mailbox = ?1 AND id <= ?2", params![mailbox.to_hex(), upto])
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

    /// Видалити недоставлені конверти, старші за 30 днів.
    pub fn prune(&self, now_ms: u64) -> rusqlite::Result<usize> {
        let cutoff = now_ms.saturating_sub(RETENTION_MS) as i64;
        self.0.lock().unwrap().execute("DELETE FROM envelopes WHERE created < ?1", [cutoff])
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
        std::fs::remove_dir_all(dir).unwrap();
    }
}
