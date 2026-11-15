//! Формати даних, які бачить транспорт (сервер, а згодом Bluetooth / Wi-Fi Direct).
//! Серіалізація поки JSON + hex; остаточний формат — ADR-0003 (кандидат Protobuf).

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const WIRE_VERSION: u8 = 0;

/// Публічний ключ ідентичності користувача (серіалізований ключ libsignal, 33 байти) у hex.
/// Це і є «адреса» людини в Шепоті — замість номера телефону.
pub type IdentityHex = String;

/// Непрозорий ID поштової скриньки отримувача.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct MailboxId(pub [u8; 32]);

impl MailboxId {
    /// Етап 0: похідна від ключа ідентичності. Згодом — ротовані ID, не пов'язані з ключем.
    pub fn for_identity(identity: &[u8]) -> Self {
        let mut h = Sha256::new();
        h.update(b"shepit-mailbox-v0");
        h.update(identity);
        Self(h.finalize().into())
    }

    pub fn to_hex(&self) -> String {
        hex::encode(self.0)
    }

    pub fn from_hex(s: &str) -> Option<Self> {
        Some(Self(hex::decode(s).ok()?.try_into().ok()?))
    }
}

impl Serialize for MailboxId {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.to_hex())
    }
}

impl<'de> Deserialize<'de> for MailboxId {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let s = String::deserialize(d)?;
        Self::from_hex(&s).ok_or_else(|| serde::de::Error::custom("expected 32-byte hex"))
    }
}

/// Конверт — одиниця передачі. Транспорт бачить лише скриньку і непрозорі байти.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Envelope {
    pub version: u8,
    pub to: MailboxId,
    /// hex від зашифрованого вмісту ([`crate::crypto`]).
    pub payload: String,
}

impl Envelope {
    pub fn new(to: MailboxId, payload: &[u8]) -> Self {
        Self { version: WIRE_VERSION, to, payload: hex::encode(payload) }
    }

    pub fn payload_bytes(&self) -> Option<Vec<u8>> {
        hex::decode(&self.payload).ok()
    }
}

/// Одноразовий ключ (EC pre-key).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct OneTimeKey {
    pub id: u32,
    pub key: String,
}

/// Те, що клієнт публікує на сервері, щоб інші могли почати з ним розмову офлайн.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct KeyUpload {
    pub identity: IdentityHex,
    pub registration_id: u32,
    pub device_id: u32,
    pub signed_prekey_id: u32,
    pub signed_prekey: String,
    pub signed_prekey_sig: String,
    /// Постквантовий ключ ML-KEM (Kyber1024). «Останній шанс» — використовується повторно.
    pub kyber_prekey_id: u32,
    pub kyber_prekey: String,
    pub kyber_prekey_sig: String,
    pub one_time: Vec<OneTimeKey>,
}

/// Пакет ключів, який сервер видає тому, хто хоче написати: базові ключі + один одноразовий.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PreKeyBundle {
    pub identity: IdentityHex,
    pub registration_id: u32,
    pub device_id: u32,
    pub signed_prekey_id: u32,
    pub signed_prekey: String,
    pub signed_prekey_sig: String,
    pub kyber_prekey_id: u32,
    pub kyber_prekey: String,
    pub kyber_prekey_sig: String,
    pub one_time: Option<OneTimeKey>,
}

impl KeyUpload {
    /// Видати пакет, забравши один одноразовий ключ (кожен використовується лише раз).
    pub fn take_bundle(&mut self) -> PreKeyBundle {
        PreKeyBundle {
            identity: self.identity.clone(),
            registration_id: self.registration_id,
            device_id: self.device_id,
            signed_prekey_id: self.signed_prekey_id,
            signed_prekey: self.signed_prekey.clone(),
            signed_prekey_sig: self.signed_prekey_sig.clone(),
            kyber_prekey_id: self.kyber_prekey_id,
            kyber_prekey: self.kyber_prekey.clone(),
            kyber_prekey_sig: self.kyber_prekey_sig.clone(),
            one_time: self.one_time.pop(),
        }
    }
}

/// Відбиток ключа для перевірки «наживо»: 12 груп по 5 цифр.
pub fn fingerprint(identity: &[u8]) -> String {
    let hash = Sha256::digest(identity);
    hash.chunks(2)
        .take(12)
        .map(|c| format!("{:05}", u16::from_be_bytes([c[0], c[1]])))
        .collect::<Vec<_>>()
        .join(" ")
}
