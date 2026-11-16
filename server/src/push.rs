//! Web Push: розбудити браузер, коли застосунок закритий.
//!
//! Що бачить сервіс сповіщень браузера (Google/Mozilla/Apple): лише те, що комусь щось прийшло.
//! Вміст сповіщення зашифрований (RFC 8291) і містить тільки тип: `call` або `mail`.

use std::path::Path;

use base64::Engine;
use web_push::{
    request_builder, ContentEncoding, SubscriptionInfo, Urgency, VapidSignatureBuilder, WebPushMessageBuilder,
};

use crate::db::PushSub;

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::URL_SAFE_NO_PAD;

pub struct Pusher {
    private_b64: String,
    pub public_b64: String,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Kind {
    Call,
    Mail,
}

pub enum Outcome {
    Sent,
    /// Підписка більше не дійсна — видалити.
    Gone,
    Failed(String),
}

impl Pusher {
    /// Ключ VAPID: `$SHEPIT_DATA/vapid.key` (створюється при першому запуску).
    pub fn load(dir: &Path) -> Self {
        let path = dir.join("vapid.key");
        let private_b64 = match std::fs::read_to_string(&path) {
            Ok(s) => s.trim().to_owned(),
            Err(_) => {
                let sk = p256::SecretKey::random(&mut p256::elliptic_curve::rand_core::OsRng);
                let s = B64.encode(sk.to_bytes());
                crate::write_private(&path, s.as_bytes()).expect("write vapid.key");
                s
            }
        };
        let partial = VapidSignatureBuilder::from_base64_no_sub(&private_b64).expect("vapid.key");
        let public_b64 = B64.encode(partial.get_public_key());
        Self { private_b64, public_b64 }
    }

    /// Надіслати одне сповіщення (блокуюче — викликати в spawn_blocking).
    pub fn send(&self, sub: &PushSub, kind: Kind) -> Outcome {
        let info = SubscriptionInfo::new(sub.endpoint.clone(), sub.p256dh.clone(), sub.auth.clone());
        let mut sig = match VapidSignatureBuilder::from_base64(&self.private_b64, &info) {
            Ok(s) => s,
            Err(e) => return Outcome::Failed(e.to_string()),
        };
        sig.add_claim("sub", "https://github.com/ya77papa/shepit");
        let sig = match sig.build() {
            Ok(s) => s,
            Err(e) => return Outcome::Failed(e.to_string()),
        };
        let payload: &[u8] = match kind {
            Kind::Call => br#"{"t":"call"}"#,
            Kind::Mail => br#"{"t":"mail"}"#,
        };
        let mut b = WebPushMessageBuilder::new(&info);
        b.set_payload(ContentEncoding::Aes128Gcm, payload);
        b.set_vapid_signature(sig);
        match kind {
            Kind::Call => {
                b.set_ttl(60);
                b.set_urgency(Urgency::High);
            }
            Kind::Mail => {
                b.set_ttl(24 * 3600);
                b.set_urgency(Urgency::Normal);
                b.set_topic("mail".into()); // нові «є пошта» замінюють старі в черзі сервісу
            }
        }
        let msg = match b.build() {
            Ok(m) => m,
            Err(e) => return Outcome::Failed(e.to_string()),
        };
        let req: http::Request<Vec<u8>> = request_builder::build_request(msg);
        let (parts, body) = req.into_parts();
        let mut r = ureq::post(parts.uri.to_string());
        for (k, v) in parts.headers.iter() {
            if let Ok(v) = v.to_str() {
                r = r.header(k.as_str(), v);
            }
        }
        match r.config().http_status_as_error(false).build().send(&body[..]) {
            Ok(resp) if resp.status().is_success() => Outcome::Sent,
            Ok(resp) if matches!(resp.status().as_u16(), 404 | 410) => Outcome::Gone,
            Ok(resp) => Outcome::Failed(format!("HTTP {}", resp.status())),
            Err(e) => Outcome::Failed(e.to_string()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Read, Write};

    /// Справжнє шифрування RFC 8291 і VAPID: «браузер» розшифровує те, що надіслав сервер.
    #[test]
    fn push_is_encrypted_and_decryptable() {
        let dir = std::env::temp_dir().join(format!("shepit-push-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let pusher = Pusher::load(&dir);
        assert_eq!(Pusher::load(&dir).public_b64, pusher.public_b64, "ключ VAPID зберігається");

        // «Браузер»: ключі підписки.
        let (kp, auth) = ece::generate_keypair_and_auth_secret().unwrap();
        let p256dh = B64.encode(kp.pub_as_raw().unwrap());

        // «Сервіс сповіщень»: локальний HTTP, що ловить запит.
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        let h = std::thread::spawn(move || {
            let (s, _) = l.accept().unwrap();
            let mut r = BufReader::new(s);
            let (mut len, mut headers) = (0usize, Vec::new());
            loop {
                let mut line = String::new();
                r.read_line(&mut line).unwrap();
                if line == "\r\n" {
                    break;
                }
                if let Some(v) = line.to_ascii_lowercase().strip_prefix("content-length:") {
                    len = v.trim().parse().unwrap();
                }
                headers.push(line.trim().to_owned());
            }
            let mut body = vec![0; len];
            r.read_exact(&mut body).unwrap();
            r.get_mut().write_all(b"HTTP/1.1 201 Created\r\ncontent-length: 0\r\n\r\n").unwrap();
            (headers, body)
        });

        let sub = PushSub { endpoint: format!("http://127.0.0.1:{port}/push/x"), p256dh, auth: B64.encode(auth) };
        assert!(matches!(pusher.send(&sub, Kind::Call), Outcome::Sent));
        let (headers, body) = h.join().unwrap();
        let has = |p: &str| headers.iter().any(|h| h.to_ascii_lowercase().starts_with(p));
        assert!(has("authorization: vapid "), "VAPID: {headers:?}");
        assert!(has("urgency: high") && has("ttl: 60") && has("content-encoding: aes128gcm"));
        assert!(!body.windows(4).any(|w| w == b"call"), "вміст зашифрований");
        let plain = ece::decrypt(&kp.raw_components().unwrap(), &auth, &body).unwrap();
        assert_eq!(plain, br#"{"t":"call"}"#);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
