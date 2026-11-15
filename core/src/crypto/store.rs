//! Сховища libsignal, які зберігаються у файл (JSON).
//!
//! Етап 0: файл НЕ зашифрований. На етапі 1 замінимо на SQLCipher (ADR-0001).

use std::collections::HashMap;

use async_trait::async_trait;
use libsignal_protocol::{
    CiphertextMessageType, Direction, GenericSignedPreKey, IdentityChange, IdentityKey,
    IdentityKeyPair, IdentityKeyStore, KyberPreKeyId, KyberPreKeyRecord, KyberPreKeyStore,
    PreKeyId, PreKeyRecord, PreKeyStore, ProtocolAddress, PublicKey, SessionRecord, SessionStore,
    SignalProtocolError, SignedPreKeyId, SignedPreKeyRecord, SignedPreKeyStore,
};
use serde::{Deserialize, Serialize};

type Result<T> = std::result::Result<T, SignalProtocolError>;

fn h(b: &[u8]) -> String {
    hex::encode(b)
}
fn unh(s: &str) -> Result<Vec<u8>> {
    hex::decode(s).map_err(|_| SignalProtocolError::InvalidProtobufEncoding)
}

#[derive(Serialize, Deserialize)]
pub struct Identities {
    pub key_pair: String,
    pub registration_id: u32,
    /// Довіра при першому контакті (TOFU): адреса → ключ, який ми бачили першим.
    pub known: HashMap<String, String>,
}

#[async_trait(?Send)]
impl IdentityKeyStore for Identities {
    async fn get_identity_key_pair(&self) -> Result<IdentityKeyPair> {
        IdentityKeyPair::try_from(unh(&self.key_pair)?.as_slice())
    }
    async fn get_local_registration_id(&self) -> Result<u32> {
        Ok(self.registration_id)
    }
    async fn save_identity(&mut self, a: &ProtocolAddress, k: &IdentityKey) -> Result<IdentityChange> {
        let new = h(&k.serialize());
        Ok(match self.known.insert(a.to_string(), new.clone()) {
            Some(old) if old != new => IdentityChange::ReplacedExisting,
            _ => IdentityChange::NewOrUnchanged,
        })
    }
    async fn is_trusted_identity(&self, a: &ProtocolAddress, k: &IdentityKey, _: Direction) -> Result<bool> {
        Ok(self.known.get(&a.to_string()).is_none_or(|v| *v == h(&k.serialize())))
    }
    async fn get_identity(&self, a: &ProtocolAddress) -> Result<Option<IdentityKey>> {
        self.known.get(&a.to_string()).map(|v| IdentityKey::decode(&unh(v)?)).transpose()
    }
}

#[derive(Serialize, Deserialize, Default)]
pub struct PreKeys(pub HashMap<u32, String>);

#[async_trait(?Send)]
impl PreKeyStore for PreKeys {
    async fn get_pre_key(&self, id: PreKeyId) -> Result<PreKeyRecord> {
        let v = self.0.get(&id.into()).ok_or(SignalProtocolError::InvalidPreKeyId)?;
        PreKeyRecord::deserialize(&unh(v)?)
    }
    async fn save_pre_key(&mut self, id: PreKeyId, r: &PreKeyRecord) -> Result<()> {
        self.0.insert(id.into(), h(&r.serialize()?));
        Ok(())
    }
    async fn remove_pre_key(&mut self, id: PreKeyId) -> Result<()> {
        self.0.remove(&id.into());
        Ok(())
    }
}

#[derive(Serialize, Deserialize, Default)]
pub struct SignedPreKeys(pub HashMap<u32, String>);

#[async_trait(?Send)]
impl SignedPreKeyStore for SignedPreKeys {
    async fn get_signed_pre_key(&self, id: SignedPreKeyId) -> Result<SignedPreKeyRecord> {
        let v = self.0.get(&id.into()).ok_or(SignalProtocolError::InvalidSignedPreKeyId)?;
        SignedPreKeyRecord::deserialize(&unh(v)?)
    }
    async fn save_signed_pre_key(&mut self, id: SignedPreKeyId, r: &SignedPreKeyRecord) -> Result<()> {
        self.0.insert(id.into(), h(&r.serialize()?));
        Ok(())
    }
}

#[derive(Serialize, Deserialize, Default)]
pub struct KyberPreKeys {
    pub keys: HashMap<u32, String>,
    /// Захист від повтору першого повідомлення: які base key вже бачили.
    pub seen: Vec<String>,
}

#[async_trait(?Send)]
impl KyberPreKeyStore for KyberPreKeys {
    async fn get_kyber_pre_key(&self, id: KyberPreKeyId) -> Result<KyberPreKeyRecord> {
        let v = self.keys.get(&id.into()).ok_or(SignalProtocolError::InvalidKyberPreKeyId)?;
        KyberPreKeyRecord::deserialize(&unh(v)?)
    }
    async fn save_kyber_pre_key(&mut self, id: KyberPreKeyId, r: &KyberPreKeyRecord) -> Result<()> {
        self.keys.insert(id.into(), h(&r.serialize()?));
        Ok(())
    }
    async fn mark_kyber_pre_key_used(&mut self, k: KyberPreKeyId, s: SignedPreKeyId, base: &PublicKey) -> Result<()> {
        let tag = format!("{}:{}:{}", u32::from(k), u32::from(s), h(&base.serialize()));
        if self.seen.contains(&tag) {
            return Err(SignalProtocolError::InvalidMessage(
                CiphertextMessageType::PreKey,
                "reused base key".to_owned(),
            ));
        }
        self.seen.push(tag);
        Ok(())
    }
}

#[derive(Serialize, Deserialize, Default)]
pub struct Sessions(pub HashMap<String, String>);

#[async_trait(?Send)]
impl SessionStore for Sessions {
    async fn load_session(&self, a: &ProtocolAddress) -> Result<Option<SessionRecord>> {
        self.0.get(&a.to_string()).map(|v| SessionRecord::deserialize(&unh(v)?)).transpose()
    }
    async fn store_session(&mut self, a: &ProtocolAddress, r: &SessionRecord) -> Result<()> {
        self.0.insert(a.to_string(), h(&r.serialize()?));
        Ok(())
    }
}

/// Увесь локальний стан акаунта.
#[derive(Serialize, Deserialize)]
pub struct State {
    pub ids: Identities,
    pub pre: PreKeys,
    pub signed: SignedPreKeys,
    pub kyber: KyberPreKeys,
    pub sessions: Sessions,
    /// Закріплений корінь довіри сервера (hex). Задається при реєстрації.
    pub trust_root: String,
    /// Поточний сертифікат відправника (hex), видається сервером.
    #[serde(default)]
    pub sender_cert: Option<String>,
    /// Наступний вільний ID для pre-key (0 = старий акаунт, де вже зайняті 1..=20).
    #[serde(default)]
    pub next_key_id: u32,
}
