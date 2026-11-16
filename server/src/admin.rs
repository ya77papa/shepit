//! Адміністративна панель: вхід за паролем, сесії, обмеження спроб.
//!
//! Пароль задається лише на сервері командою `shepit-server admin-password`
//! і зберігається як хеш Argon2id у `$SHEPIT_DATA/admin.json` (0600).
//! Без цього файлу адмінка вимкнена.
//!
//! Сесія — випадковий токен у cookie `HttpOnly; Secure; SameSite=Strict`,
//! живе 30 хв без дій і не довше 12 год. Кожен запит до адмін-API має ще й
//! заголовок `x-shepit-admin: 1` (захист від підробки запитів з інших сайтів).

use std::{collections::HashMap, path::Path, sync::Mutex};

use argon2::{
    password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use axum::http::{header, HeaderMap, StatusCode};
use rand::Rng;

pub const COOKIE: &str = "shepit_admin";
pub const CSRF_HEADER: &str = "x-shepit-admin";
const IDLE_MS: u64 = 30 * 60 * 1000;
const MAX_MS: u64 = 12 * 60 * 60 * 1000;
/// Після стількох невдалих спроб за вікно — пауза.
const MAX_FAILURES: usize = 10;
const FAIL_WINDOW_MS: u64 = 15 * 60 * 1000;
pub const MIN_PASSWORD: usize = 12;

struct Session {
    started: u64,
    last: u64,
    /// Хеш пароля, з яким увійшли: зміна пароля обриває старі сесії.
    hash: String,
}

#[derive(Default)]
pub struct Admin {
    sessions: Mutex<HashMap<String, Session>>,
    failures: Mutex<Vec<u64>>,
}

#[derive(serde::Serialize, serde::Deserialize)]
struct AdminFile {
    hash: String,
}

fn file(dir: &Path) -> std::path::PathBuf {
    dir.join("admin.json")
}

/// Поточний хеш пароля (None — адмінку не налаштовано).
pub fn stored_hash(dir: &Path) -> Option<String> {
    let raw = std::fs::read(file(dir)).ok()?;
    serde_json::from_slice::<AdminFile>(&raw).ok().map(|f| f.hash)
}

pub fn configured(dir: &Path) -> bool {
    stored_hash(dir).is_some()
}

pub fn hash_password(password: &str) -> String {
    let salt = SaltString::encode_b64(&rand::rng().random::<[u8; 16]>()).expect("salt");
    Argon2::default().hash_password(password.as_bytes(), &salt).expect("argon2").to_string()
}

pub fn verify(hash: &str, password: &str) -> bool {
    PasswordHash::new(hash).is_ok_and(|h| Argon2::default().verify_password(password.as_bytes(), &h).is_ok())
}

/// `shepit-server admin-password`: задати або змінити пароль (усі сесії буде завершено).
pub fn set_password_cli(dir: &Path) -> Result<(), String> {
    let p1 = rpassword::prompt_password("Новий пароль адміністратора: ").map_err(|e| e.to_string())?;
    if p1.chars().count() < MIN_PASSWORD {
        return Err(format!("пароль має бути щонайменше {MIN_PASSWORD} символів"));
    }
    let p2 = rpassword::prompt_password("Повтори пароль: ").map_err(|e| e.to_string())?;
    if p1 != p2 {
        return Err("паролі не збігаються".into());
    }
    write_hash(dir, &hash_password(&p1)).map_err(|e| e.to_string())?;
    println!("Пароль збережено у {}. Відкрий https://<твій домен>/admin/", file(dir).display());
    Ok(())
}

pub fn write_hash(dir: &Path, hash: &str) -> std::io::Result<()> {
    use std::io::Write;
    std::fs::create_dir_all(dir)?;
    let tmp = dir.join("admin.json.tmp");
    let _ = std::fs::remove_file(&tmp);
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut opts, 0o600);
    opts.open(&tmp)?.write_all(&serde_json::to_vec(&AdminFile { hash: hash.into() }).unwrap())?;
    std::fs::rename(tmp, file(dir))
}

pub enum Login {
    Ok(String),
    Wrong,
    Locked(u64),
    NotConfigured,
}

impl Admin {
    /// Перевірити пароль (повільно — викликати з spawn_blocking).
    pub fn login(&self, dir: &Path, password: &str, now: u64) -> Login {
        {
            let mut f = self.failures.lock().unwrap();
            f.retain(|t| now.saturating_sub(*t) < FAIL_WINDOW_MS);
            if f.len() >= MAX_FAILURES {
                let wait = FAIL_WINDOW_MS - now.saturating_sub(f[0]);
                return Login::Locked(wait.div_ceil(1000));
            }
        }
        let Some(hash) = stored_hash(dir) else { return Login::NotConfigured };
        if !verify(&hash, password) {
            self.failures.lock().unwrap().push(now);
            return Login::Wrong;
        }
        self.failures.lock().unwrap().clear();
        let token = hex::encode(rand::rng().random::<[u8; 32]>());
        let mut s = self.sessions.lock().unwrap();
        s.retain(|_, v| now.saturating_sub(v.last) < IDLE_MS && now.saturating_sub(v.started) < MAX_MS);
        s.insert(token.clone(), Session { started: now, last: now, hash });
        Login::Ok(token)
    }

    /// Чинна сесія з заголовків запиту (cookie + захисний заголовок).
    pub fn check(&self, dir: &Path, headers: &HeaderMap, now: u64) -> Result<(), StatusCode> {
        if headers.get(CSRF_HEADER).and_then(|v| v.to_str().ok()) != Some("1") {
            return Err(StatusCode::FORBIDDEN);
        }
        let token = session_token(headers).ok_or(StatusCode::UNAUTHORIZED)?;
        let current = stored_hash(dir);
        let mut s = self.sessions.lock().unwrap();
        let ok = match s.get_mut(&token) {
            Some(v) if now.saturating_sub(v.last) < IDLE_MS
                && now.saturating_sub(v.started) < MAX_MS
                && current.as_deref() == Some(v.hash.as_str()) => {
                v.last = now;
                true
            }
            _ => false,
        };
        if !ok {
            s.remove(&token);
            return Err(StatusCode::UNAUTHORIZED);
        }
        Ok(())
    }

    pub fn logout(&self, headers: &HeaderMap) {
        if let Some(t) = session_token(headers) {
            self.sessions.lock().unwrap().remove(&t);
        }
    }
}

fn session_token(headers: &HeaderMap) -> Option<String> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .filter_map(|kv| kv.trim().split_once('='))
        .find(|(k, _)| *k == COOKIE)
        .map(|(_, v)| v.to_owned())
        .filter(|v| v.len() == 64 && v.bytes().all(|b| b.is_ascii_hexdigit()))
}

pub fn set_cookie(token: &str) -> String {
    format!("{COOKIE}={token}; Path=/v0/admin; HttpOnly; Secure; SameSite=Strict; Max-Age={}", MAX_MS / 1000)
}

pub fn clear_cookie() -> String {
    format!("{COOKIE}=; Path=/v0/admin; HttpOnly; Secure; SameSite=Strict; Max-Age=0")
}

/// Код запрошення: 12 знаків без схожих (0/O, 1/I/L) — ≈59 біт, у вигляді XXXX-XXXX-XXXX.
const ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";

pub fn new_invite_code() -> String {
    let mut r = rand::rng();
    let raw: String = (0..12).map(|_| ALPHABET[r.random_range(0..ALPHABET.len())] as char).collect();
    format!("{}-{}-{}", &raw[..4], &raw[4..8], &raw[8..])
}

/// Привести введений код до канонічного вигляду (регістр, пробіли, дефіси неважливі).
pub fn normalize_code(input: &str) -> Option<String> {
    let raw: String = input.chars().filter(|c| c.is_ascii_alphanumeric()).map(|c| c.to_ascii_uppercase()).collect();
    (raw.len() == 12 && raw.bytes().all(|b| ALPHABET.contains(&b)))
        .then(|| format!("{}-{}-{}", &raw[..4], &raw[4..8], &raw[8..]))
}

/// Вільне й загальне місце на диску з даними (байти).
pub fn disk_space(dir: &Path) -> Option<(u64, u64)> {
    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStrExt;
        let c = std::ffi::CString::new(dir.as_os_str().as_bytes()).ok()?;
        let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
        // SAFETY: c — коректний C-рядок, st — валідний вихідний буфер.
        if unsafe { libc::statvfs(c.as_ptr(), &mut st) } != 0 {
            return None;
        }
        let bs = st.f_frsize as u64;
        Some((st.f_bavail as u64 * bs, st.f_blocks as u64 * bs))
    }
    #[cfg(not(unix))]
    {
        let _ = dir;
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes() {
        let c = new_invite_code();
        assert_eq!(c.len(), 14);
        assert_eq!(normalize_code(&c.to_lowercase().replace('-', " ")), Some(c));
        assert_eq!(normalize_code("ABCD-EFGH-JKM0"), None, "0 немає в алфавіті");
        assert_eq!(normalize_code("short"), None);
    }

    #[test]
    fn login_flow() {
        let dir = std::env::temp_dir().join(format!("shepit-admin-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let a = Admin::default();
        assert!(matches!(a.login(&dir, "x", 0), Login::NotConfigured));
        write_hash(&dir, &hash_password("correct horse battery")).unwrap();
        assert!(matches!(a.login(&dir, "wrong", 0), Login::Wrong));
        let Login::Ok(token) = a.login(&dir, "correct horse battery", 1) else { panic!() };
        let mut h = HeaderMap::new();
        h.insert(header::COOKIE, format!("other=1; {COOKIE}={token}").parse().unwrap());
        assert_eq!(a.check(&dir, &h, 2), Err(StatusCode::FORBIDDEN), "без захисного заголовка");
        h.insert(CSRF_HEADER, "1".parse().unwrap());
        assert_eq!(a.check(&dir, &h, 2), Ok(()));
        assert_eq!(a.check(&dir, &h, 2 + IDLE_MS + 1), Err(StatusCode::UNAUTHORIZED), "простій");
        // зміна пароля обриває сесії
        let Login::Ok(t2) = a.login(&dir, "correct horse battery", 10) else { panic!() };
        h.insert(header::COOKIE, format!("{COOKIE}={t2}").parse().unwrap());
        write_hash(&dir, &hash_password("another long password")).unwrap();
        assert_eq!(a.check(&dir, &h, 11), Err(StatusCode::UNAUTHORIZED));
        // перебір
        for _ in 0..MAX_FAILURES {
            a.login(&dir, "guess", 100);
        }
        assert!(matches!(a.login(&dir, "another long password", 101), Login::Locked(_)));
        std::fs::remove_dir_all(dir).unwrap();
    }
}
