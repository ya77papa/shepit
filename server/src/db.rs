//! Сховище сервера: SQLite у `$SHEPIT_DATA/shepit.db`.
//!
//! Тут лише те, що сервер і так бачить: опубліковані публічні ключі та
//! зашифровані конверти до моменту доставки. Доставлені конверти видаляються.

use std::{path::Path, sync::Mutex};

use rusqlite::{params, Connection, OptionalExtension};
use shepit_core::wire::{Envelope, KeyUpload, MailboxId, MailboxItem, PreKeyBundle};

pub struct Db(Mutex<Connection>);

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
             CREATE INDEX IF NOT EXISTS envelopes_mailbox ON envelopes(mailbox, id);",
        )?;
        Ok(Self(Mutex::new(c)))
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
        std::fs::remove_dir_all(dir).unwrap();
    }
}
