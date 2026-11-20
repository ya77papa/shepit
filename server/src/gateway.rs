//! Шлюз для роботи без інтернету (docs/mesh-concept.md, етап 4).
//!
//! Телефон без інтернету кладе в mesh-конверт ще й «пакет для шлюзу» — скриньку адресата
//! і сам конверт, зашифровані публічним ключем сервера. Будь-який телефон-ретранслятор,
//! що має інтернет, вивантажує пакет сюди; сервер розкриває його й кладе конверт у скриньку
//! адресата (з позначкою wake = "mesh"). Ретранслятор нічого не прочитає й не знає адресата.
//!
//! Формат пакета: ефемерний ключ P-256 (65 байт, без стиснення) ‖ IV (12) ‖ AES-256-GCM.
//! Ключ AES = SHA-256("shepit-gateway-v1" ‖ спільний секрет ECDH ‖ ефемерний ключ).

use aes_gcm::{aead::Aead, Aes256Gcm, KeyInit, Nonce};
use p256::{elliptic_curve::sec1::ToEncodedPoint, PublicKey, SecretKey};
use sha2::{Digest, Sha256};
use std::path::Path;

pub struct Gateway {
    secret: SecretKey,
}

impl Gateway {
    /// Ключ шлюзу: `$SHEPIT_DATA/gateway.key` (створюється при першому запуску).
    pub fn load(dir: &Path) -> Self {
        let path = dir.join("gateway.key");
        let secret = match std::fs::read(&path).ok().and_then(|b| SecretKey::from_slice(&b).ok()) {
            Some(s) => s,
            None => {
                let s = SecretKey::random(&mut p256::elliptic_curve::rand_core::OsRng);
                crate::write_private(&path, &s.to_bytes()).expect("write gateway.key");
                s
            }
        };
        Self { secret }
    }

    /// Публічний ключ (65 байт, hex) — його застосунки беруть, поки інтернет є.
    pub fn public_hex(&self) -> String {
        hex::encode(self.secret.public_key().to_encoded_point(false).as_bytes())
    }

    pub fn open(&self, blob: &[u8]) -> Option<Vec<u8>> {
        if blob.len() < 65 + 12 + 16 {
            return None;
        }
        let (eph, rest) = blob.split_at(65);
        let (iv, ct) = rest.split_at(12);
        let eph_key = PublicKey::from_sec1_bytes(eph).ok()?;
        let shared = p256::ecdh::diffie_hellman(self.secret.to_nonzero_scalar(), eph_key.as_affine());
        let mut h = Sha256::new();
        h.update(b"shepit-gateway-v1");
        h.update(shared.raw_secret_bytes());
        h.update(eph);
        let key: [u8; 32] = h.finalize().into();
        Aes256Gcm::new(&key.into()).decrypt(Nonce::from_slice(iv), ct).ok()
    }

    /// Зашифрувати так, як це робить застосунок (для тестів).
    #[cfg(test)]
    pub fn seal(public: &PublicKey, plain: &[u8]) -> Vec<u8> {
        let eph = SecretKey::random(&mut p256::elliptic_curve::rand_core::OsRng);
        let eph_pub = eph.public_key().to_encoded_point(false);
        let shared = p256::ecdh::diffie_hellman(eph.to_nonzero_scalar(), public.as_affine());
        let mut h = Sha256::new();
        h.update(b"shepit-gateway-v1");
        h.update(shared.raw_secret_bytes());
        h.update(eph_pub.as_bytes());
        let key: [u8; 32] = h.finalize().into();
        let iv = [7u8; 12];
        let ct = Aes256Gcm::new(&key.into()).encrypt(Nonce::from_slice(&iv), plain).unwrap();
        [eph_pub.as_bytes(), &iv, &ct].concat()
    }

    #[cfg(test)]
    pub fn public(&self) -> PublicKey {
        self.secret.public_key()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let dir = std::env::temp_dir().join(format!("shepit-gw-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let gw = Gateway::load(&dir);
        let blob = Gateway::seal(&gw.public(), b"{\"to\":\"x\"}");
        assert_eq!(gw.open(&blob).unwrap(), b"{\"to\":\"x\"}");
        // Перезапуск — той самий ключ.
        assert_eq!(Gateway::load(&dir).public_hex(), gw.public_hex());
        // Підробка — відмова.
        let mut bad = blob.clone();
        *bad.last_mut().unwrap() ^= 1;
        assert!(gw.open(&bad).is_none());
        assert!(gw.open(&blob[..50]).is_none());
        let _ = std::fs::remove_dir_all(dir);
    }
}
