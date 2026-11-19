//! Шифрування для роботи без інтернету (mesh, див. docs/mesh-concept.md).
//!
//! Без сервера немає сертифіката відправника («запечатаний відправник» спирається на нього),
//! тому mesh-повідомлення — звичайні повідомлення Signal-сесії. Хто відправник, отримувач
//! дізнається з тегу конверта: тег може порахувати лише пара співрозмовників
//! (зі спільного секрету їхніх ключів ідентичності).

use libsignal_protocol::{
    message_decrypt_prekey, message_decrypt_signal, message_encrypt, CiphertextMessageType, PreKeySignalMessage,
    SignalMessage,
};
use sha2::{Digest, Sha256};

use super::*;

impl Account {
    /// Спільний секрет пари для mesh-тегів: X25519 ключів ідентичності, хешований разом
    /// з обома ключами (в однаковому порядку з обох боків). Однаковий у мене і в `peer`.
    pub fn mesh_secret(&self, peer: &str) -> Result<[u8; 32]> {
        let me = block_on(self.st.ids.get_identity_key_pair())?;
        let their = IdentityKey::decode(&unhex(peer, "identity")?)?;
        let shared = me.private_key().calculate_agreement(their.public_key()).map_err(|_| Error::Malformed("mesh agreement"))?;
        let mine = self.identity_hex();
        let (a, b) = if mine.as_str() < peer { (mine.as_str(), peer) } else { (peer, mine.as_str()) };
        let mut h = Sha256::new();
        h.update(b"shepit-mesh-v1");
        h.update(&shared);
        h.update(a.as_bytes());
        h.update(b.as_bytes());
        Ok(h.finalize().into())
    }

    /// Зашифрувати текст для `peer` без серверного сертифіката. Потрібна наявна сесія.
    /// Формат: 1 байт типу Signal-повідомлення + саме повідомлення.
    pub fn mesh_encrypt(&mut self, peer: &str, text: &str) -> Result<Vec<u8>> {
        let me = address(&self.identity_hex());
        let m = block_on(message_encrypt(
            text.as_bytes(),
            &address(peer),
            &me,
            &mut self.st.sessions,
            &mut self.st.ids,
            clock::now(),
            &mut rng(),
        ))?;
        let mut out = vec![m.message_type() as u8];
        out.extend_from_slice(m.serialize());
        Ok(out)
    }

    /// Розшифрувати mesh-повідомлення від `peer` (хто це — визначено за тегом конверта).
    pub fn mesh_decrypt(&mut self, peer: &str, data: &[u8]) -> Result<String> {
        let (&kind, body) = data.split_first().ok_or(Error::Malformed("mesh"))?;
        let me = address(&self.identity_hex());
        let from = address(peer);
        let plain = match CiphertextMessageType::try_from(kind).map_err(|_| Error::Malformed("mesh type"))? {
            CiphertextMessageType::PreKey => {
                let m = PreKeySignalMessage::try_from(body)?;
                // Адреса = ключ: перше повідомлення мусить бути підписане саме ключем peer.
                if hex::encode(m.identity_key().serialize()) != peer {
                    return Err(Error::SenderMismatch);
                }
                block_on(message_decrypt_prekey(
                    &m,
                    &from,
                    &me,
                    &mut self.st.sessions,
                    &mut self.st.ids,
                    &mut self.st.pre,
                    &self.st.signed,
                    &mut self.st.kyber,
                    &mut rng(),
                ))?
                .plaintext
            }
            CiphertextMessageType::Whisper => {
                let m = SignalMessage::try_from(body)?;
                block_on(message_decrypt_signal(&m, &from, &me, &mut self.st.sessions, &mut self.st.ids, &mut rng()))?
            }
            _ => return Err(Error::Malformed("mesh type")),
        };
        // Сесія з цією адресою — саме з цим ключем.
        let known = block_on(self.st.ids.get_identity(&from))?;
        if known.map(|k| hex::encode(k.serialize())).as_deref() != Some(peer) {
            return Err(Error::SenderMismatch);
        }
        String::from_utf8(plain).map_err(|_| Error::Malformed("utf-8"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::Issuer;

    fn account(issuer: &Issuer) -> (Account, KeyUpload) {
        let (mut acc, up) = Account::create(&issuer.trust_root_hex()).unwrap();
        let cert = issuer.issue(&acc.identity_hex(), now_ms()).unwrap();
        acc.set_certificate(&cert).unwrap();
        (acc, up)
    }

    #[test]
    fn secret_is_shared_and_pairwise() {
        let issuer = Issuer::generate();
        let (a, _) = account(&issuer);
        let (b, _) = account(&issuer);
        let (c, _) = account(&issuer);
        let ab = a.mesh_secret(&b.identity_hex()).unwrap();
        assert_eq!(ab, b.mesh_secret(&a.identity_hex()).unwrap());
        assert_ne!(ab, a.mesh_secret(&c.identity_hex()).unwrap());
        assert_ne!(ab, c.mesh_secret(&b.identity_hex()).unwrap());
    }

    #[test]
    fn conversation_without_server() {
        let issuer = Issuer::generate();
        let (mut alice, _) = account(&issuer);
        let (mut bob, mut bob_keys) = account(&issuer);
        let (mut mallory, _) = account(&issuer);
        let (a, b) = (alice.identity_hex(), bob.identity_hex());
        // Сесію встановлено раніше, коли інтернет був.
        alice.start_session(&bob_keys.take_bundle()).unwrap();

        // Перше повідомлення (PreKey) — без сертифіката, без сервера.
        let m1 = alice.mesh_encrypt(&b, "Ми в укритті, все добре").unwrap();
        assert_eq!(m1[0], CiphertextMessageType::PreKey as u8);
        assert!(!String::from_utf8_lossy(&m1).contains("укритті"));
        // Чужий не підробить «від Аліси».
        assert!(mallory.mesh_decrypt(&a, &m1).is_err());
        // Боб, якщо вирішить, що це від когось іншого, — відмова.
        let wrong = mallory.identity_hex();
        assert!(bob.mesh_decrypt(&wrong, &m1).is_err());
        assert_eq!(bob.mesh_decrypt(&a, &m1).unwrap(), "Ми в укритті, все добре");
        assert!(bob.mesh_decrypt(&a, &m1).is_err(), "повтор має відкидатись");

        // Відповідь і далі — звичайні повідомлення сесії.
        let m2 = bob.mesh_encrypt(&a, "Прийняв").unwrap();
        assert_eq!(m2[0], CiphertextMessageType::Whisper as u8);
        assert_eq!(alice.mesh_decrypt(&b, &m2).unwrap(), "Прийняв");

        // Mesh і сервер ділять одну сесію: після mesh звичайне повідомлення теж доходить.
        let s = alice.encrypt(&b, "Інтернет з'явився").unwrap();
        assert_eq!(bob.decrypt(&s).unwrap().1, "Інтернет з'явився");
        let m3 = bob.mesh_encrypt(&a, "Знову без інтернету").unwrap();
        assert_eq!(alice.mesh_decrypt(&b, &m3).unwrap(), "Знову без інтернету");
    }

    #[test]
    fn no_session_no_mesh() {
        let issuer = Issuer::generate();
        let (mut alice, _) = account(&issuer);
        let (bob, _) = account(&issuer);
        assert!(alice.mesh_encrypt(&bob.identity_hex(), "x").is_err());
    }
}
