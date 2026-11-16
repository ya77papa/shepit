//! Автентифікація запитів ключем ідентичності та видача сертифікатів відправника.
//!
//! Немає паролів, токенів і номерів телефону: запит підписується тим самим ключем,
//! який є «адресою» людини. Сервер перевіряє підпис і бачить лише публічний ключ.
//!
//! Заголовок: `x-shepit-auth: <identity-hex>.<time-ms>.<nonce-hex>.<signature-hex>`
//! Підписується: мета запиту (метод + шлях), час, nonce і SHA-256 тіла.

use libsignal_protocol::{
    IdentityKey, KeyPair, PrivateKey, PublicKey, SenderCertificate, ServerCertificate, Timestamp,
    DeviceId,
};
use rand::{rngs::OsRng, Rng, TryRngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const HEADER: &str = "x-shepit-auth";
/// Допустиме розходження годинників клієнта і сервера.
pub const MAX_SKEW_MS: u64 = 5 * 60 * 1000;
/// Скільки діє сертифікат відправника. Короткий строк: видалений адміністратором акаунт перестає надсилати повідомлення
/// щонайпізніше через 4 год (сервер не бачить відправника і не може відсіяти його інакше).
/// Клієнти оновлюють сертифікат за годину до кінця.
pub const CERT_VALIDITY_MS: u64 = 4 * 60 * 60 * 1000;

#[derive(Debug, PartialEq, Eq)]
pub enum AuthError {
    Malformed,
    Expired,
    BadSignature,
}

impl std::fmt::Display for AuthError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Malformed => "некоректний заголовок автентифікації",
            Self::Expired => "запит застарів або годинник розходиться",
            Self::BadSignature => "невірний підпис",
        })
    }
}

pub use crate::clock::now_ms;

fn signing_input(purpose: &str, time_ms: u64, nonce: &str, body: &[u8]) -> Vec<u8> {
    format!("shepit-auth-v0\n{purpose}\n{time_ms}\n{nonce}\n{}", hex::encode(Sha256::digest(body)))
        .into_bytes()
}

/// Розібраний заголовок автентифікації.
#[derive(Debug, Clone)]
pub struct Auth {
    pub identity: String,
    pub time_ms: u64,
    pub nonce: String,
    sig: String,
}

impl Auth {
    /// Підписати запит (клієнт).
    pub fn sign(identity_hex: &str, key: &PrivateKey, purpose: &str, body: &[u8]) -> String {
        let mut r = OsRng.unwrap_err();
        let nonce = hex::encode(r.random::<[u8; 16]>());
        let time_ms = now_ms();
        let sig = key
            .calculate_signature(&signing_input(purpose, time_ms, &nonce, body), &mut r)
            .expect("signing with own key");
        format!("{identity_hex}.{time_ms}.{nonce}.{}", hex::encode(sig))
    }

    pub fn parse(header: &str) -> Result<Self, AuthError> {
        let mut it = header.split('.');
        let (Some(identity), Some(time), Some(nonce), Some(sig), None) =
            (it.next(), it.next(), it.next(), it.next(), it.next())
        else {
            return Err(AuthError::Malformed);
        };
        if nonce.len() != 32 {
            return Err(AuthError::Malformed);
        }
        Ok(Self {
            identity: identity.to_owned(),
            time_ms: time.parse().map_err(|_| AuthError::Malformed)?,
            nonce: nonce.to_owned(),
            sig: sig.to_owned(),
        })
    }

    /// Перевірити підпис і час (сервер). Захист від повтору (nonce) — на боці сервера.
    pub fn verify(&self, purpose: &str, body: &[u8], now_ms: u64) -> Result<(), AuthError> {
        if self.time_ms.abs_diff(now_ms) > MAX_SKEW_MS {
            return Err(AuthError::Expired);
        }
        let id = hex::decode(&self.identity).map_err(|_| AuthError::Malformed)?;
        let key = IdentityKey::decode(&id).map_err(|_| AuthError::Malformed)?;
        let sig = hex::decode(&self.sig).map_err(|_| AuthError::Malformed)?;
        if key
            .public_key()
            .verify_signature(&signing_input(purpose, self.time_ms, &self.nonce, body), &sig)
        {
            Ok(())
        } else {
            Err(AuthError::BadSignature)
        }
    }
}

/// Ключі сервера для сертифікатів відправника (sealed sender).
///
/// Ланцюжок як у Signal: корінь довіри → сертифікат сервера → сертифікат відправника.
/// Клієнт закріплює корінь довіри при реєстрації.
#[derive(Serialize, Deserialize)]
pub struct Issuer {
    trust_root_private: String,
    server_private: String,
    server_certificate: String,
}

impl Issuer {
    pub fn generate() -> Self {
        let mut r = OsRng.unwrap_err();
        let root = KeyPair::generate(&mut r);
        let server = KeyPair::generate(&mut r);
        let cert = ServerCertificate::new(1, server.public_key, &root.private_key, &mut r)
            .expect("server certificate");
        Self {
            trust_root_private: hex::encode(root.private_key.serialize()),
            server_private: hex::encode(server.private_key.serialize()),
            server_certificate: hex::encode(cert.serialized().expect("serialize")),
        }
    }

    fn private(hex_key: &str) -> PrivateKey {
        PrivateKey::deserialize(&hex::decode(hex_key).expect("issuer key")).expect("issuer key")
    }

    /// Публічний корінь довіри — його клієнти закріплюють.
    pub fn trust_root_hex(&self) -> String {
        let pk: PublicKey = Self::private(&self.trust_root_private).public_key().expect("public");
        hex::encode(pk.serialize())
    }

    /// Видати сертифікат для перевіреної ідентичності. UUID відправника = його ключ (hex).
    pub fn issue(&self, identity_hex: &str, now_ms: u64) -> Result<Vec<u8>, AuthError> {
        let id = hex::decode(identity_hex).map_err(|_| AuthError::Malformed)?;
        let key = IdentityKey::decode(&id).map_err(|_| AuthError::Malformed)?;
        self.issue_raw(identity_hex, &key, now_ms)
    }

    /// Те саме, але UUID і ключ задаються окремо. Публічно — лише для тестів
    /// «нечесного сервера»; справжній сервер завжди викликає [`Issuer::issue`].
    #[doc(hidden)]
    pub fn issue_raw(&self, uuid: &str, key: &IdentityKey, now_ms: u64) -> Result<Vec<u8>, AuthError> {
        let identity_hex = uuid;
        let server_cert = ServerCertificate::deserialize(
            &hex::decode(&self.server_certificate).expect("cert"),
        )
        .expect("cert");
        let cert = SenderCertificate::new(
            identity_hex.to_owned(),
            None,
            *key.public_key(),
            DeviceId::try_from(1u8).expect("device"),
            Timestamp::from_epoch_millis(now_ms + CERT_VALIDITY_MS),
            server_cert,
            &Self::private(&self.server_private),
            &mut OsRng.unwrap_err(),
        )
        .map_err(|_| AuthError::Malformed)?;
        Ok(cert.serialized().expect("serialize").to_vec())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use libsignal_protocol::IdentityKeyPair;

    #[test]
    fn sign_verify_and_reject() {
        let ik = IdentityKeyPair::generate(&mut OsRng.unwrap_err());
        let id = hex::encode(ik.identity_key().serialize());
        let h = Auth::sign(&id, ik.private_key(), "GET /v0/x", b"body");
        let a = Auth::parse(&h).unwrap();
        assert_eq!(a.verify("GET /v0/x", b"body", now_ms()), Ok(()));
        assert_eq!(a.verify("GET /v0/y", b"body", now_ms()), Err(AuthError::BadSignature));
        assert_eq!(a.verify("GET /v0/x", b"other", now_ms()), Err(AuthError::BadSignature));
        assert_eq!(a.verify("GET /v0/x", b"body", now_ms() + MAX_SKEW_MS + 1), Err(AuthError::Expired));
    }
}
