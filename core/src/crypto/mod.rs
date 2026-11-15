//! Наскрізне шифрування Шепіт на базі libsignal.
//!
//! Решта коду працює лише з цим модулем, а не з libsignal напряму —
//! так оновлення чи заміна бібліотеки торкнеться одного місця (ADR-0001).
//!
//! - Початок сесії: PQXDH (X25519 + ML-KEM/Kyber1024), далі Double Ratchet.
//! - Sealed sender: відправник схований усередині шифротексту, сервер його не бачить.

mod store;

use std::{fs, path::Path};

use futures::executor::block_on;
use libsignal_protocol::{
    kem, process_prekey_bundle, sealed_sender_decrypt, sealed_sender_decrypt_to_usmc,
    sealed_sender_encrypt, DeviceId,
    GenericSignedPreKey, IdentityKey, IdentityKeyPair, IdentityKeyStore, KeyPair,
    KyberPreKeyRecord, PreKeyRecord, PublicKey, SenderCertificate, SessionStore,
    SessionUsabilityRequirements, SignedPreKeyRecord, Timestamp,
};
pub use libsignal_protocol::SignalProtocolError;
use rand::{rngs::OsRng, Rng, TryRngCore};

use crate::{
    auth::{now_ms, Auth},
    clock,
    wire::{self, KeyUpload, MailboxId, OneTimeKey, PreKeyBundle},
};
use store::{Identities, KyberPreKeys, PreKeys, Sessions, SignedPreKeys, State};

/// Скільки одноразових ключів публікуємо на сервері.
pub const ONE_TIME_KEYS: u32 = 20;
/// Оновлювати сертифікат, якщо до кінця дії лишилось менше години.
const CERT_REFRESH_MS: u64 = 60 * 60 * 1000;
const DEVICE: u8 = 1;

#[derive(Debug)]
pub enum Error {
    Signal(SignalProtocolError),
    Io(std::io::Error),
    Malformed(&'static str),
    /// Сертифікат відправника не підходить (чужий, прострочений або не від нашого сервера).
    BadCertificate,
    /// Ключ ідентичності не збігається з тим, від чийого імені надійшло повідомлення.
    SenderMismatch,
    NoCertificate,
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
            Self::BadCertificate => write!(f, "недійсний сертифікат відправника"),
            Self::SenderMismatch => write!(f, "відправник не збігається з ключем повідомлення"),
            Self::NoCertificate => write!(f, "немає сертифіката відправника"),
        }
    }
}

pub type Result<T> = std::result::Result<T, Error>;

fn rng() -> impl rand::CryptoRng {
    OsRng.unwrap_err()
}

fn device() -> DeviceId {
    DeviceId::try_from(DEVICE).expect("valid device id")
}

fn address(identity_hex: &str) -> libsignal_protocol::ProtocolAddress {
    libsignal_protocol::ProtocolAddress::new(identity_hex.to_owned(), device())
}

fn unhex(s: &str, what: &'static str) -> Result<Vec<u8>> {
    hex::decode(s).map_err(|_| Error::Malformed(what))
}

fn public_key(s: &str, what: &'static str) -> Result<PublicKey> {
    PublicKey::deserialize(&unhex(s, what)?).map_err(|_| Error::Malformed(what))
}

/// Локальний акаунт: ключі, сесії, стан. Зберігається у файл.
pub struct Account {
    st: State,
}

impl Account {
    /// Новий акаунт, прив'язаний до сервера з коренем довіри `trust_root_hex`,
    /// + те, що треба опублікувати на сервері.
    pub fn create(trust_root_hex: &str) -> Result<(Self, KeyUpload)> {
        public_key(trust_root_hex, "trust root")?;
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
                trust_root: trust_root_hex.to_owned(),
                sender_cert: None,
            },
        };
        let upload = block_on(acc.generate_keys(&ik))?;
        Ok((acc, upload))
    }

    async fn generate_keys(&mut self, ik: &IdentityKeyPair) -> Result<KeyUpload> {
        use libsignal_protocol::{KyberPreKeyStore, PreKeyStore, SignedPreKeyStore};
        let mut r = rng();
        let ts = Timestamp::from_epoch_millis(now_ms());
        let sign = |data: &[u8], r: &mut _| {
            ik.private_key().calculate_signature(data, r).map_err(|_| Error::Malformed("sign"))
        };

        let spk = KeyPair::generate(&mut r);
        let spk_sig = sign(&spk.public_key.serialize(), &mut r)?;
        self.st.signed.save_signed_pre_key(1.into(), &SignedPreKeyRecord::new(1.into(), ts, &spk, &spk_sig)).await?;

        let kpk = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut r);
        let kpk_sig = sign(&kpk.public_key.serialize(), &mut r)?;
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

    /// Відновити з байтів, отриманих через [`Account::to_bytes`] (браузер: IndexedDB).
    pub fn from_bytes(data: &[u8]) -> Result<Self> {
        let st = serde_json::from_slice(data).map_err(|_| Error::Malformed("стан акаунта"))?;
        Ok(Self { st })
    }

    /// Увесь стан акаунта, включно з секретними ключами.
    pub fn to_bytes(&self) -> Vec<u8> {
        serde_json::to_vec(&self.st).expect("serialize state")
    }

    pub fn load(path: &Path) -> Result<Self> {
        Self::from_bytes(&fs::read(path)?)
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, self.to_bytes())?;
        fs::rename(tmp, path)?;
        Ok(())
    }

    fn key_pair(&self) -> IdentityKeyPair {
        IdentityKeyPair::try_from(hex::decode(&self.st.ids.key_pair).expect("own key").as_slice())
            .expect("own key")
    }

    fn identity_bytes(&self) -> Vec<u8> {
        self.key_pair().identity_key().serialize().into_vec()
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

    /// Заголовок `x-shepit-auth` для запиту `purpose` (напр. "GET /v0/certificate") з тілом `body`.
    pub fn auth_header(&self, purpose: &str, body: &[u8]) -> String {
        Auth::sign(&self.identity_hex(), self.key_pair().private_key(), purpose, body)
    }

    fn trust_root(&self) -> Result<PublicKey> {
        public_key(&self.st.trust_root, "trust root")
    }

    fn certificate(&self) -> Option<SenderCertificate> {
        let c = self.st.sender_cert.as_ref()?;
        SenderCertificate::deserialize(&hex::decode(c).ok()?).ok()
    }

    /// Чи треба отримати новий сертифікат відправника.
    pub fn needs_certificate(&self) -> bool {
        self.certificate()
            .and_then(|c| c.expiration().ok())
            .is_none_or(|exp| exp.epoch_millis() < now_ms() + CERT_REFRESH_MS)
    }

    /// Прийняти сертифікат від сервера, перевіривши, що він наш і підписаний закріпленим коренем.
    pub fn set_certificate(&mut self, cert: &[u8]) -> Result<()> {
        let c = SenderCertificate::deserialize(cert)?;
        let ours = c.key()?.serialize() == self.key_pair().identity_key().public_key().serialize()
            && c.sender_uuid()? == self.identity_hex();
        if !ours || !c.validate(&self.trust_root()?, Timestamp::from_epoch_millis(now_ms()))? {
            return Err(Error::BadCertificate);
        }
        self.st.sender_cert = Some(hex::encode(cert));
        Ok(())
    }

    pub fn has_session(&self, peer: &str) -> bool {
        block_on(self.st.sessions.load_session(&address(peer)))
            .ok()
            .flatten()
            .is_some_and(|s| {
                s.has_usable_sender_chain(clock::now(), SessionUsabilityRequirements::EstablishedWithPqxdh)
                    .unwrap_or(false)
            })
    }

    /// Почати сесію з людиною за її пакетом ключів (вона може бути офлайн).
    pub fn start_session(&mut self, b: &PreKeyBundle) -> Result<()> {
        let identity = IdentityKey::decode(&unhex(&b.identity, "identity")?)?;
        let one_time = match &b.one_time {
            Some(k) => Some((k.id.into(), public_key(&k.key, "one-time key")?)),
            None => None,
        };
        let bundle = libsignal_protocol::PreKeyBundle::new(
            b.registration_id,
            DeviceId::try_from(b.device_id).map_err(|_| Error::Malformed("device id"))?,
            one_time,
            b.signed_prekey_id.into(),
            public_key(&b.signed_prekey, "signed prekey")?,
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
            clock::now(),
            &mut rng(),
        ))?;
        Ok(())
    }

    /// Зашифрувати текст для `peer` (sealed sender). Повертає байти для `Envelope::payload`.
    pub fn encrypt(&mut self, peer: &str, text: &str) -> Result<Vec<u8>> {
        let cert = self.certificate().ok_or(Error::NoCertificate)?;
        Ok(block_on(sealed_sender_encrypt(
            &address(peer),
            &cert,
            text.as_bytes(),
            &mut self.st.sessions,
            &mut self.st.ids,
            clock::now(),
            &mut rng(),
        ))?)
    }

    /// Розшифрувати вміст конверта. Повертає (ключ відправника, текст).
    pub fn decrypt(&mut self, payload: &[u8]) -> Result<(String, String)> {
        // У Шепоті адреса = ключ. Сертифікат «від імені X» з чужим ключем означає,
        // що сервер (чи хтось із його ключами) намагається видати себе за X.
        // Перевіряємо ДО розшифрування, щоб не зіпсувати збережену сесію з X.
        let usmc = block_on(sealed_sender_decrypt_to_usmc(payload, &self.st.ids))?;
        let cert = usmc.sender()?;
        if hex::encode(cert.key()?.serialize()) != cert.sender_uuid()? {
            return Err(Error::SenderMismatch);
        }
        let me = self.identity_hex();
        let res = block_on(sealed_sender_decrypt(
            payload,
            &self.trust_root()?,
            Timestamp::from_epoch_millis(now_ms()),
            None,
            me,
            device(),
            &mut self.st.ids,
            &mut self.st.sessions,
            &mut self.st.pre,
            &self.st.signed,
            &mut self.st.kyber,
        ))?;
        let from = res.sender_uuid;
        // Друга лінія захисту: ключ, з яким встановлено сесію, збігається з адресою.
        let known = block_on(self.st.ids.get_identity(&address(&from)))?;
        if known.map(|k| hex::encode(k.serialize())) != Some(from.clone()) {
            return Err(Error::SenderMismatch);
        }
        let text = String::from_utf8(res.message).map_err(|_| Error::Malformed("utf-8"))?;
        Ok((from, text))
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
    use crate::auth::Issuer;

    fn account(issuer: &Issuer) -> (Account, KeyUpload) {
        let (mut acc, up) = Account::create(&issuer.trust_root_hex()).unwrap();
        let cert = issuer.issue(&acc.identity_hex(), now_ms()).unwrap();
        acc.set_certificate(&cert).unwrap();
        (acc, up)
    }

    #[test]
    fn full_conversation_with_persistence() {
        let issuer = Issuer::generate();
        let dir = std::env::temp_dir().join(format!("shepit-test-{}", now_ms()));
        fs::create_dir_all(&dir).unwrap();
        let (alice, _) = account(&issuer);
        let (bob, mut bob_keys) = account(&issuer);
        alice.save(&dir.join("a")).unwrap();
        bob.save(&dir.join("b")).unwrap();

        let mut alice = Account::load(&dir.join("a")).unwrap();
        alice.start_session(&bob_keys.take_bundle()).unwrap();
        let c1 = alice.encrypt(&bob.identity_hex(), "Привіт, Бобе!").unwrap();
        alice.save(&dir.join("a")).unwrap();
        // Сервер не бачить ні тексту, ні відправника.
        let c1_hex = hex::encode(&c1);
        assert!(!c1_hex.contains(&alice.identity_hex()[2..]));
        assert!(!String::from_utf8_lossy(&c1).contains("Привіт"));

        let mut bob = Account::load(&dir.join("b")).unwrap();
        let (from, text) = bob.decrypt(&c1).unwrap();
        assert_eq!((from.as_str(), text.as_str()), (alice.identity_hex().as_str(), "Привіт, Бобе!"));
        assert!(bob.decrypt(&c1).is_err(), "повтор першого повідомлення має відкидатись");
        let c2 = bob.encrypt(&alice.identity_hex(), "Привіт, Алісо!").unwrap();
        bob.save(&dir.join("b")).unwrap();

        let mut alice = Account::load(&dir.join("a")).unwrap();
        assert_eq!(alice.decrypt(&c2).unwrap().1, "Привіт, Алісо!");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn foreign_certificate_rejected() {
        let issuer = Issuer::generate();
        let (mut alice, _) = account(&issuer);
        let (bob, _) = account(&issuer);
        // Сервер видав Алісі сертифікат Боба — Аліса має відмовитись.
        let cert = issuer.issue(&bob.identity_hex(), now_ms()).unwrap();
        assert!(matches!(alice.set_certificate(&cert), Err(Error::BadCertificate)));
        // Сертифікат від іншого (підробленого) сервера.
        let rogue = Issuer::generate().issue(&alice.identity_hex(), now_ms()).unwrap();
        assert!(matches!(alice.set_certificate(&rogue), Err(Error::BadCertificate)));
    }

    #[test]
    fn rogue_server_cannot_impersonate() {
        // Нечесний сервер видає Мелорі сертифікат з UUID Аліси, але з ключем Мелорі.
        let issuer = Issuer::generate();
        let (mut alice, _) = account(&issuer);
        let (mut bob, mut bob_keys) = account(&issuer);
        let (mut mallory, _) = Account::create(&issuer.trust_root_hex()).unwrap();
        let forged = issuer
            .issue_raw(&alice.identity_hex(), mallory.key_pair().identity_key(), now_ms())
            .unwrap();
        mallory.st.sender_cert = Some(hex::encode(forged));
        mallory.start_session(&bob_keys.take_bundle()).unwrap();
        let c = mallory.encrypt(&bob.identity_hex(), "я Аліса, чесно").unwrap();
        assert!(matches!(bob.decrypt(&c), Err(Error::SenderMismatch)));

        // І справжня Аліса після цього спокійно пише Бобу: сесію не зіпсовано.
        alice.start_session(&bob_keys.take_bundle()).unwrap();
        let c = alice.encrypt(&bob.identity_hex(), "це справді я").unwrap();
        assert_eq!(bob.decrypt(&c).unwrap(), (alice.identity_hex(), "це справді я".into()));
    }
}
