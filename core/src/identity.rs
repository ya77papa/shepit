//! Ідентичність користувача = пара ключів Ed25519. Без номера телефону та email.

use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand_core::OsRng;
use sha2::{Digest, Sha256};

use crate::envelope::MailboxId;

/// Локальна ідентичність (містить приватний ключ — ніколи не покидає пристрій).
pub struct Identity {
    signing: SigningKey,
}

impl Identity {
    /// Створити нову випадкову ідентичність.
    pub fn generate() -> Self {
        Self { signing: SigningKey::generate(&mut OsRng) }
    }

    /// Відновити з 32 байтів секрету.
    pub fn from_secret(secret: &[u8; 32]) -> Self {
        Self { signing: SigningKey::from_bytes(secret) }
    }

    pub fn secret_bytes(&self) -> [u8; 32] {
        self.signing.to_bytes()
    }

    pub fn public(&self) -> PublicIdentity {
        PublicIdentity(self.signing.verifying_key())
    }

    pub fn sign(&self, msg: &[u8]) -> Signature {
        self.signing.sign(msg)
    }
}

/// Публічна частина — те, чим користувачі обмінюються (QR-код, посилання).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PublicIdentity(VerifyingKey);

impl PublicIdentity {
    pub fn from_bytes(bytes: &[u8; 32]) -> Result<Self, ed25519_dalek::SignatureError> {
        VerifyingKey::from_bytes(bytes).map(Self)
    }

    pub fn to_bytes(&self) -> [u8; 32] {
        self.0.to_bytes()
    }

    pub fn verify(&self, msg: &[u8], sig: &Signature) -> bool {
        self.0.verify(msg, sig).is_ok()
    }

    /// Скринька, куди надходять повідомлення для цього ключа.
    /// Етап 0: SHA-256 від публічного ключа. Згодом — ротовані ID, не пов'язані з ключем.
    pub fn mailbox(&self) -> MailboxId {
        let mut h = Sha256::new();
        h.update(b"shepit-mailbox-v0");
        h.update(self.0.as_bytes());
        MailboxId(h.finalize().into())
    }

    /// Короткий відбиток для порівняння «наживо» (safety number).
    /// 12 груп по 5 цифр, як у Signal.
    pub fn fingerprint(&self) -> String {
        let hash = Sha256::digest(self.0.as_bytes());
        hash.chunks(2)
            .take(12)
            .map(|c| format!("{:05}", u16::from_be_bytes([c[0], c[1]]) as u32 % 100_000))
            .collect::<Vec<_>>()
            .join(" ")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sign_and_verify() {
        let id = Identity::generate();
        let sig = id.sign(b"pryvit");
        assert!(id.public().verify(b"pryvit", &sig));
        assert!(!id.public().verify(b"inshe", &sig));
    }

    #[test]
    fn roundtrip_secret() {
        let id = Identity::generate();
        let restored = Identity::from_secret(&id.secret_bytes());
        assert_eq!(id.public(), restored.public());
    }

    #[test]
    fn fingerprint_is_stable() {
        let id = Identity::generate();
        assert_eq!(id.public().fingerprint(), id.public().fingerprint());
        assert_eq!(id.public().fingerprint().split(' ').count(), 12);
    }
}
