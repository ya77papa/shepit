//! Статус «в мережі» / «востаннє…».
//!
//! Лише в пам'яті: точний час активності ніколи не пишеться на диск (у базі — тільки день,
//! див. `accounts.last_seen`). Після перезапуску сервера статуси невідомі, доки люди знову
//! не відкриють застосунок.

use serde::Serialize;
use std::collections::HashMap;
use std::sync::Mutex;

/// Скільки після останнього «я тут» людина вважається в мережі (клієнт шле раз на хвилину).
pub const ONLINE_MS: u64 = 90_000;
/// Скільки пам'ятати «востаннє».
const KEEP_MS: u64 = 30 * 24 * 60 * 60 * 1000;
const MINUTE: u64 = 60_000;

#[derive(Clone, Copy)]
struct Entry {
    at: u64,
    online: bool,
    share: bool,
}

/// Що бачить інший. `hidden` — людина (або той, хто питає) не ділиться часом: «востаннє нещодавно».
#[derive(Serialize, Debug, PartialEq)]
#[serde(untagged)]
pub enum Status {
    Online { on: bool },
    Seen { at: u64 },
    Hidden { hidden: bool },
}

#[derive(Default)]
pub struct Presence(Mutex<HashMap<String, Entry>>);

impl Presence {
    /// Клієнт повідомляє: застосунок на екрані (online) чи щойно згорнутий; і чи ділитися часом.
    pub fn beat(&self, who: &str, online: bool, share: bool, now: u64) {
        let mut m = self.0.lock().unwrap();
        if m.len() > 200_000 {
            m.retain(|_, e| now.saturating_sub(e.at) < KEEP_MS);
        }
        m.insert(who.to_owned(), Entry { at: now, online, share });
    }

    /// Статуси для ids з погляду viewer. Невідомих (давно не було / після перезапуску) — немає у відповіді.
    pub fn query(&self, viewer: &str, ids: &[String], now: u64) -> HashMap<String, Status> {
        let m = self.0.lock().unwrap();
        // Як у Telegram: приховав свій час — не бачиш чужого.
        let viewer_shares = m.get(viewer).map(|e| e.share).unwrap_or(true);
        ids.iter()
            .filter_map(|id| {
                let e = m.get(id)?;
                if now.saturating_sub(e.at) >= KEEP_MS {
                    return None;
                }
                let s = if !e.share || !viewer_shares {
                    Status::Hidden { hidden: true }
                } else if e.online && now.saturating_sub(e.at) < ONLINE_MS {
                    Status::Online { on: true }
                } else {
                    // До хвилини — точніше нікому не треба.
                    Status::Seen { at: e.at / MINUTE * MINUTE }
                };
                Some((id.clone(), s))
            })
            .collect()
    }

    pub fn clear(&self) {
        self.0.lock().unwrap().clear();
    }

    pub fn forget(&self, who: &str) {
        self.0.lock().unwrap().remove(who);
    }

    pub fn prune(&self, now: u64) {
        self.0.lock().unwrap().retain(|_, e| now.saturating_sub(e.at) < KEEP_MS);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn statuses() {
        let p = Presence::default();
        let ids = |v: &[&str]| v.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        p.beat("a", true, true, 1_000_000);
        p.beat("b", false, true, 1_000_000);
        p.beat("c", true, false, 1_000_000);
        let q = p.query("me", &ids(&["a", "b", "c", "z"]), 1_030_000);
        assert_eq!(q["a"], Status::Online { on: true });
        assert_eq!(q["b"], Status::Seen { at: 960_000 });
        assert_eq!(q["c"], Status::Hidden { hidden: true });
        assert!(!q.contains_key("z"));
        // Перестав слати «я тут» — уже не в мережі.
        assert_eq!(p.query("me", &ids(&["a"]), 1_000_000 + ONLINE_MS)["a"], Status::Seen { at: 960_000 });
        // Хто ховає свій час, не бачить чужого.
        p.beat("me", true, false, 1_000_000);
        assert_eq!(p.query("me", &ids(&["a"]), 1_030_000)["a"], Status::Hidden { hidden: true });
        // Через 30 днів забуваємо.
        assert!(p.query("x", &ids(&["b"]), 1_000_000 + KEEP_MS).is_empty());
    }
}
