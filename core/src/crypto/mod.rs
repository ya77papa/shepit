//! Наскрізне шифрування Шепіт на базі libsignal.
//!
//! Решта коду працює лише з цим модулем, а не з libsignal напряму —
//! так оновлення чи заміна бібліотеки торкнеться одного місця (ADR-0001).
//!
//! Протокол: PQXDH (X25519 + ML-KEM/Kyber1024) для початку сесії, далі Double Ratchet.

mod store;

use std::{fs, path::Path, time::SystemTime};

use futures::executor::block_on;
use libsignal_protocol::{
    kem, message_decrypt, message_encrypt, process_prekey_bundle, CiphertextMessage,
    CiphertextMessageType, DeviceId, GenericSignedPreKey, IdentityKey, IdentityKeyPair,
    KeyPair, KyberPreKeyRecord, PreKeyRecord, PreKeySignalMessage, PublicKey,
    SessionStore, SessionUsabilityRequirements, SignalMessage, SignedPreKeyRecord, Timestamp,
};
use rand::{rngs::OsRng, Rng, TryRngCore};
use serde::{Deserialize, Serialize};
pub use libsignal_protocol::SignalProtocolError;

use crate::wire::{self, KeyUpload, MailboxId, OneTimeKey, PreKeyBundle};
use store::{Identities, KyberPreKeys, PreKeys, Sessions, SignedPreKeys, State};

/// Скільки одноразових ключів публікуємо на сервері.
pub const ONE_TIME_KEYS: u32 = 20;
const DEVICE: u8 = 1;

#[derive(Debug)]
pub enum Error {
    Signal(SignalProtocolError),
    Io(std::io::Error),
    Malformed(&'static str),
    /// Відправник у конверті не збігається з ключем у повідомленні — можлива підміна.
    SenderMismatch,
}

impl From<SignalProtocolError> for Error {
    fn from(e: SignalProtocolError) -> Self {
        Self::Signal(e)
    }
}
impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Signal(e) => write!(f, "libsignal: {e}"),
            Self::Io(e) => write!(f, "файл: {e}"),
            Self::Malformed(w) => write!(f, "некоректні дані: {w}"),
            Self::SenderMismatch => write!(f, "відправник не збігається з ключем повідомлення"),
        }
    }
}

pub type Result<T> = std::result::Result<T, Error>;

/// Вміст конверта після шифрування.
/// Етап 0: відправник видно серверу. Sealed sender — наступний крок.
#[derive(Serialize, Deserialize)]
struct Sealed {
    from: String,
    kind: u8,
    body: String,
}

fn rng() -> impl rand::CryptoRng {
    OsRng.unwrap_err()
}

fn device() -> DeviceId {
    DeviceId::try_from(DEVICE).expect("valid device id")
}

fn address(identity_hex: &str) -> libsignal_protocol::ProtocolAddress {
    libsignal_protocol::ProtocolAddress::new(identity_hex.to_owned(), device())
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn unhex(s: &str, what: &'static str) -> Result<Vec<u8>> {
    hex::decode(s).map_err(|_| Error::Malformed(what))
}

/// Локальний акаунт: ключі, сесії, стан. Зберігається у файл.
pub struct Account {
    st: State,
}

impl Account {
    /// Новий акаунт + те, що треба опублікувати на сервері.
    pub fn create() -> Result<(Self, KeyUpload)> {
        let mut r = rng();
        let ik = IdentityKeyPair::generate(&mut r);
        let registration_id = (r.random::<u16>() & 0x3fff) as u32;
        let mut acc = Self {
            st: State {
                ids: Identities {
                    key_pair: hex::encode(ik.serialize()),
                    registration_id,
                    known: Default::default(),
                },
                pre: PreKeys::default(),
                signed: SignedPreKeys::default(),
                kyber: KyberPreKeys::default(),
                sessions: Sessions::default(),
            },
        };
        let upload = block_on(acc.generate_keys(&ik))?;
        Ok((acc, upload))
    }

    async fn generate_keys(&mut self, ik: &IdentityKeyPair) -> Result<KeyUpload> {
        use libsignal_protocol::{KyberPreKeyStore, PreKeyStore, SignedPreKeyStore};
        let mut r = rng();
        let ts = Timestamp::from_epoch_millis(now_ms());

        let spk = KeyPair::generate(&mut r);
        let spk_sig = ik.private_key().calculate_signature(&spk.public_key.serialize(), &mut r).map_err(|_| Error::Malformed("sign"))?;
        self.st.signed.save_signed_pre_key(1.into(), &SignedPreKeyRecord::new(1.into(), ts, &spk, &spk_sig)).await?;

        let kpk = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut r);
        let kpk_sig = ik.private_key().calculate_signature(&kpk.public_key.serialize(), &mut r).map_err(|_| Error::Malformed("sign"))?;
        self.st.kyber.save_kyber_pre_key(1.into(), &KyberPreKeyRecord::new(1.into(), ts, &kpk, &kpk_sig)).await?;

        let mut one_time = Vec::new();
        for id in 1..=ONE_TIME_KEYS {
            let kp = KeyPair::generate(&mut r);
            self.st.pre.save_pre_key(id.into(), &PreKeyRecord::new(id.into(), &kp)).await?;
            one_time.push(OneTimeKey { id, key: hex::encode(kp.public_key.serialize()) });
        }

        Ok(KeyUpload {
            identity: self.identity_hex(),
            registration_id: self.st.ids.registration_id,
            device_id: DEVICE as u32,
            signed_prekey_id: 1,
            signed_prekey: hex::encode(spk.public_key.serialize()),
            signed_prekey_sig: hex::encode(&spk_sig),
            kyber_prekey_id: 1,
            kyber_prekey: hex::encode(kpk.public_key.serialize()),
            kyber_prekey_sig: hex::encode(&kpk_sig),
            one_time,
        })
    }

    pub fn load(path: &Path) -> Result<Self> {
        let st = serde_json::from_slice(&fs::read(path)?).map_err(|_| Error::Malformed("файл акаунта"))?;
        Ok(Self { st })
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        let json = serde_json::to_vec_pretty(&self.st).expect("serialize state");
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, json)?;
        fs::rename(tmp, path)?;
        Ok(())
    }

    fn identity_bytes(&self) -> Vec<u8> {
        let ik = IdentityKeyPair::try_from(hex::decode(&self.st.ids.key_pair).expect("own key").as_slice())
            .expect("own key");
        ik.identity_key().serialize().into_vec()
    }

    /// Публічний ключ — «адреса» цього акаунта.
    pub fn identity_hex(&self) -> String {
        hex::encode(self.identity_bytes())
    }

    pub fn fingerprint(&self) -> String {
        wire::fingerprint(&self.identity_bytes())
    }

    pub fn mailbox(&self) -> MailboxId {
        MailboxId::for_identity(&self.identity_bytes())
    }

    pub fn has_session(&self, peer: &str) -> bool {
        block_on(self.st.sessions.load_session(&address(peer)))
            .ok()
            .flatten()
            .is_some_and(|s| s.has_usable_sender_chain(SystemTime::now(), SessionUsabilityRequirements::EstablishedWithPqxdh).unwrap_or(false))
    }

    /// Почати сесію з людиною за її пакетом ключів (вона може бути офлайн).
    pub fn start_session(&mut self, b: &PreKeyBundle) -> Result<()> {
        let identity = IdentityKey::decode(&unhex(&b.identity, "identity")?)?;
        let pk = |s: &str, w| -> Result<PublicKey> { PublicKey::deserialize(&unhex(s, w)?).map_err(|_| Error::Malformed(w)) };
        let one_time = match &b.one_time {
            Some(k) => Some((k.id.into(), pk(&k.key, "one-time key")?)),
            None => None,
        };
        let bundle = libsignal_protocol::PreKeyBundle::new(
            b.registration_id,
            DeviceId::try_from(b.device_id).map_err(|_| Error::Malformed("device id"))?,
            one_time,
            b.signed_prekey_id.into(),
            pk(&b.signed_prekey, "signed prekey")?,
            unhex(&b.signed_prekey_sig, "signature")?,
            b.kyber_prekey_id.into(),
            kem::PublicKey::deserialize(&unhex(&b.kyber_prekey, "kyber prekey")?)?,
            unhex(&b.kyber_prekey_sig, "signature")?,
            identity,
        )?;
        let me = address(&self.identity_hex());
        block_on(process_prekey_bundle(
            &address(&b.identity),
            &me,
            &mut self.st.sessions,
            &mut self.st.ids,
            &bundle,
            SystemTime::now(),
            &mut rng(),
        ))?;
        Ok(())
    }

    /// Зашифрувати текст для `peer`. Повертає байти для `Envelope::payload`.
    pub fn encrypt(&mut self, peer: &str, text: &str) -> Result<Vec<u8>> {
        let me = self.identity_hex();
        let c = block_on(message_encrypt(
            text.as_bytes(),
            &address(peer),
            &address(&me),
            &mut self.st.sessions,
            &mut self.st.ids,
            SystemTime::now(),
            &mut rng(),
        ))?;
        let sealed = Sealed { from: me, kind: c.message_type() as u8, body: hex::encode(c.serialize()) };
        Ok(serde_json::to_vec(&sealed).expect("serialize"))
    }

    /// Розшифрувати вміст конверта. Повертає (ключ відправника, текст).
    pub fn decrypt(&mut self, payload: &[u8]) -> Result<(String, String)> {
        let s: Sealed = serde_json::from_slice(payload).map_err(|_| Error::Malformed("payload"))?;
        let body = unhex(&s.body, "body")?;
        let msg = if s.kind == CiphertextMessageType::PreKey as u8 {
            let m = PreKeySignalMessage::try_from(body.as_slice())?;
            // Захист від підміни поля `from`: ключ усередині повідомлення має збігатися.
            if hex::encode(m.identity_key().serialize()) != s.from {
                return Err(Error::SenderMismatch);
            }
            CiphertextMessage::PreKeySignalMessage(m)
        } else if s.kind == CiphertextMessageType::Whisper as u8 {
            CiphertextMessage::SignalMessage(SignalMessage::try_from(body.as_slice())?)
        } else {
            return Err(Error::Malformed("message type"));
        };
        let me = self.identity_hex();
        let plain = block_on(message_decrypt(
            &msg,
            &address(&s.from),
            &address(&me),
            &mut self.st.sessions,
            &mut self.st.ids,
            &mut self.st.pre,
            &self.st.signed,
            &mut self.st.kyber,
            &mut rng(),
        ))?;
        let text = String::from_utf8(plain).map_err(|_| Error::Malformed("utf-8"))?;
        Ok((s.from, text))
    }
}

/// Перевірка, що ключ ідентичності валідний.
pub fn parse_identity(hex_key: &str) -> Result<()> {
    IdentityKey::decode(&unhex(hex_key, "identity")?)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn full_conversation_with_persistence() {
        let dir = std::env::temp_dir().join(format!("shepit-test-{}", now_ms()));
        fs::create_dir_all(&dir).unwrap();
        let (alice, _) = Account::create().unwrap();
        let (bob, mut bob_keys) = Account::create().unwrap();
        alice.save(&dir.join("a")).unwrap();
        bob.save(&dir.join("b")).unwrap();

        // Аліса пише Бобу, поки він офлайн.
        let mut alice = Account::load(&dir.join("a")).unwrap();
        alice.start_session(&bob_keys.take_bundle()).unwrap();
        let c1 = alice.encrypt(&bob.identity_hex(), "Привіт, Бобе!").unwrap();
        alice.save(&dir.join("a")).unwrap();
        assert!(!String::from_utf8_lossy(&c1).contains("Привіт"));

        let mut bob = Account::load(&dir.join("b")).unwrap();
        let (from, text) = bob.decrypt(&c1).unwrap();
        assert_eq!((from.as_str(), text.as_str()), (alice.identity_hex().as_str(), "Привіт, Бобе!"));
        // Повтор того самого першого повідомлення має бути відкинутий.
        assert!(bob.decrypt(&c1).is_err());
        let c2 = bob.encrypt(&alice.identity_hex(), "Привіт, Алісо!").unwrap();
        bob.save(&dir.join("b")).unwrap();

        let mut alice = Account::load(&dir.join("a")).unwrap();
        assert_eq!(alice.decrypt(&c2).unwrap().1, "Привіт, Алісо!");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn spoofed_sender_rejected() {
        let (mut alice, _) = Account::create().unwrap();
        let (bob, mut bob_keys) = Account::create().unwrap();
        let (mallory, _) = Account::create().unwrap();
        alice.start_session(&bob_keys.take_bundle()).unwrap();
        let c = alice.encrypt(&bob.identity_hex(), "секрет").unwrap();
        let mut s: Sealed = serde_json::from_slice(&c).unwrap();
        s.from = mallory.identity_hex();
        let mut bob = bob;
        assert!(matches!(bob.decrypt(&serde_json::to_vec(&s).unwrap()), Err(Error::SenderMismatch)));
    }
}
