//! Повідомлення всередині конверта.
//!
//! Етап 0: підписане, але НЕ зашифроване — сервер бачить текст.
//! Підпис уже гарантує, що повідомлення справді від власника ключа.

use ed25519_dalek::Signature;
use serde::{Deserialize, Serialize};

use crate::identity::{Identity, PublicIdentity};

#[derive(Debug, Serialize, Deserialize)]
struct Wire {
    from: String,
    text: String,
    sig: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Message {
    pub from: PublicIdentity,
    pub text: String,
}

#[derive(Debug, PartialEq, Eq)]
pub enum MessageError {
    Malformed,
    BadSignature,
}

fn signed_bytes(from: &[u8; 32], text: &str) -> Vec<u8> {
    let mut v = b"shepit-msg-v0".to_vec();
    v.extend_from_slice(from);
    v.extend_from_slice(text.as_bytes());
    v
}

/// Підписати текст і запакувати у байти для `Envelope::payload`.
pub fn seal(sender: &Identity, text: &str) -> Vec<u8> {
    let from = sender.public().to_bytes();
    let sig = sender.sign(&signed_bytes(&from, text));
    let wire = Wire {
        from: hex::encode(from),
        text: text.to_owned(),
        sig: hex::encode(sig.to_bytes()),
    };
    serde_json::to_vec(&wire).expect("serialize")
}

/// Розпакувати і перевірити підпис.
pub fn open(payload: &[u8]) -> Result<Message, MessageError> {
    let wire: Wire = serde_json::from_slice(payload).map_err(|_| MessageError::Malformed)?;
    let from: [u8; 32] = hex::decode(&wire.from)
        .ok()
        .and_then(|v| v.try_into().ok())
        .ok_or(MessageError::Malformed)?;
    let sig: [u8; 64] = hex::decode(&wire.sig)
        .ok()
        .and_then(|v| v.try_into().ok())
        .ok_or(MessageError::Malformed)?;
    let pk = PublicIdentity::from_bytes(&from).map_err(|_| MessageError::Malformed)?;
    if !pk.verify(&signed_bytes(&from, &wire.text), &Signature::from_bytes(&sig)) {
        return Err(MessageError::BadSignature);
    }
    Ok(Message { from: pk, text: wire.text })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seal_open_roundtrip() {
        let alice = Identity::generate();
        let m = open(&seal(&alice, "Привіт!")).unwrap();
        assert_eq!(m.from, alice.public());
        assert_eq!(m.text, "Привіт!");
    }

    #[test]
    fn tampered_text_rejected() {
        let alice = Identity::generate();
        let p = String::from_utf8(seal(&alice, "Привіт")).unwrap().replace("Привіт", "Бувай");
        assert_eq!(open(p.as_bytes()), Err(MessageError::BadSignature));
    }
}
