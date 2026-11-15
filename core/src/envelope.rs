//! Конверт — одиниця передачі. Однаковий для сервера, Bluetooth і Wi-Fi Direct.
//!
//! Транспорт бачить лише ID скриньки та непрозорі байти; хто відправник —
//! усередині зашифрованого payload (sealed sender).
//! Формат серіалізації остаточно оберемо в ADR-0003 (кандидат — Protobuf).

use serde::{Deserialize, Serialize};

pub const ENVELOPE_VERSION: u8 = 0;

/// Непрозорий ID поштової скриньки отримувача (32 байти, hex у JSON).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct MailboxId(#[serde(with = "hex_32")] pub [u8; 32]);

impl MailboxId {
    pub fn to_hex(&self) -> String {
        hex::encode(self.0)
    }

    pub fn from_hex(s: &str) -> Option<Self> {
        let v = hex::decode(s).ok()?;
        Some(Self(v.try_into().ok()?))
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Envelope {
    pub version: u8,
    pub to: MailboxId,
    /// Зашифрований вміст. На етапі 0 — поки відкритий текст.
    #[serde(with = "hex_vec")]
    pub payload: Vec<u8>,
}

impl Envelope {
    pub fn new(to: MailboxId, payload: Vec<u8>) -> Self {
        Self { version: ENVELOPE_VERSION, to, payload }
    }
}

mod hex_32 {
    use serde::{Deserialize, Deserializer, Serializer};
    pub fn serialize<S: Serializer>(b: &[u8; 32], s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&hex::encode(b))
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<[u8; 32], D::Error> {
        let s = String::deserialize(d)?;
        let v = hex::decode(s).map_err(serde::de::Error::custom)?;
        v.try_into().map_err(|_| serde::de::Error::custom("expected 32 bytes"))
    }
}

mod hex_vec {
    use serde::{Deserialize, Deserializer, Serializer};
    pub fn serialize<S: Serializer>(b: &[u8], s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&hex::encode(b))
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<u8>, D::Error> {
        let s = String::deserialize(d)?;
        hex::decode(s).map_err(serde::de::Error::custom)
    }
}
