//! Kotlin-інтерфейс (UniFFI) до `shepit-core` для Android.
//!
//! Повторює веб-обгортку (`clients/web-core`), щоб формати й поведінка збігались:
//! Android і веб спілкуються між собою.

use std::sync::{Arc, Mutex};

use serde::Serialize;
use shepit_core::{
    crypto::{self, Account},
    wire::{self, Envelope, MailboxId, PreKeyBundle},
};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("{0}")]
    Failed(String),
}

fn fail(e: impl std::fmt::Display) -> CoreError {
    CoreError::Failed(e.to_string())
}

fn identity_bytes(identity_hex: &str) -> Result<Vec<u8>, CoreError> {
    crypto::parse_identity(identity_hex).map_err(fail)?;
    hex::decode(identity_hex).map_err(fail)
}

#[derive(uniffi::Record, Serialize)]
pub struct Decrypted {
    pub from: String,
    pub text: String,
}

/// Акаунт. Усі методи потокобезпечні (внутрішній м'ютекс), але викликати їх варто з одного
/// фонового потоку по черзі — стан сесій змінюється при кожному повідомленні.
#[derive(uniffi::Object)]
pub struct Core {
    inner: Mutex<Account>,
    upload: Mutex<Option<String>>,
}

impl Core {
    fn acc(&self) -> std::sync::MutexGuard<'_, Account> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }
}

#[uniffi::export]
impl Core {
    /// Новий акаунт для сервера з коренем довіри `trust_root_hex`.
    #[uniffi::constructor]
    pub fn create(trust_root_hex: String) -> Result<Arc<Self>, CoreError> {
        let (inner, upload) = Account::create(&trust_root_hex).map_err(fail)?;
        let upload = serde_json::to_string(&upload).map_err(fail)?;
        Ok(Arc::new(Self { inner: Mutex::new(inner), upload: Mutex::new(Some(upload)) }))
    }

    /// Відновити зі стану, збереженого через [`Core::export`] (сумісно з веб-версією).
    #[uniffi::constructor]
    pub fn load(state: Vec<u8>) -> Result<Arc<Self>, CoreError> {
        Ok(Arc::new(Self { inner: Mutex::new(Account::from_bytes(&state).map_err(fail)?), upload: Mutex::new(None) }))
    }

    pub fn export(&self) -> Vec<u8> {
        self.acc().to_bytes()
    }

    pub fn key_upload_json(&self) -> Option<String> {
        self.upload.lock().unwrap().clone()
    }

    pub fn refresh_keys_json(&self) -> Result<String, CoreError> {
        let up = self.acc().refresh_keys().map_err(fail)?;
        serde_json::to_string(&up).map_err(fail)
    }

    pub fn identity(&self) -> String {
        self.acc().identity_hex()
    }

    pub fn fingerprint(&self) -> String {
        self.acc().fingerprint()
    }

    pub fn mailbox(&self) -> String {
        self.acc().mailbox().to_hex()
    }

    pub fn auth_header(&self, purpose: String, body: Vec<u8>) -> String {
        self.acc().auth_header(&purpose, &body)
    }

    pub fn needs_certificate(&self) -> bool {
        self.acc().needs_certificate()
    }

    pub fn set_certificate(&self, cert_hex: String) -> Result<(), CoreError> {
        let cert = hex::decode(cert_hex.trim()).map_err(fail)?;
        self.acc().set_certificate(&cert).map_err(fail)
    }

    pub fn has_session(&self, peer: String) -> bool {
        self.acc().has_session(&peer)
    }

    pub fn start_session(&self, bundle_json: String) -> Result<(), CoreError> {
        let b: PreKeyBundle = serde_json::from_str(&bundle_json).map_err(fail)?;
        self.acc().start_session(&b).map_err(fail)
    }

    pub fn safety_number(&self, peer: String) -> Result<String, CoreError> {
        self.acc().safety_number(&peer).map_err(fail)
    }

    /// Зашифрувати і запакувати в конверт (JSON для `POST /v0/send`).
    pub fn seal_envelope(&self, peer: String, text: String, wake: Option<String>) -> Result<String, CoreError> {
        let to = MailboxId::for_identity(&identity_bytes(&peer)?);
        let payload = self.acc().encrypt(&peer, &text).map_err(fail)?;
        let mut env = Envelope::new(to, &payload);
        env.wake = wake.filter(|w| w == "call");
        serde_json::to_string(&env).map_err(fail)
    }

    /// Розшифрувати конверт (поле `envelope` елемента скриньки).
    pub fn open_envelope(&self, envelope_json: String) -> Result<Decrypted, CoreError> {
        let env: Envelope = serde_json::from_str(&envelope_json).map_err(fail)?;
        let payload = env.payload_bytes().ok_or_else(|| fail("payload hex"))?;
        let (from, text) = self.acc().decrypt(&payload).map_err(fail)?;
        Ok(Decrypted { from, text })
    }
}

#[uniffi::export]
pub fn fingerprint_of(identity_hex: String) -> Result<String, CoreError> {
    Ok(wire::fingerprint(&identity_bytes(&identity_hex)?))
}

#[uniffi::export]
pub fn is_valid_identity(identity_hex: String) -> bool {
    crypto::parse_identity(&identity_hex).is_ok()
}
