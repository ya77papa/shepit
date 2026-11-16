//! JavaScript-інтерфейс до `shepit-core`.
//!
//! Уся криптографія — у Rust/WASM. JavaScript бачить лише непрозорий стан акаунта
//! (зберігає його в IndexedDB), рядки й байти.

use serde::Serialize;
use shepit_core::{
    crypto::{self, Account},
    wire::{self, Envelope, MailboxId, PreKeyBundle},
};
use wasm_bindgen::prelude::*;

fn js_err(e: impl std::fmt::Display) -> JsError {
    JsError::new(&e.to_string())
}

fn identity_bytes(identity_hex: &str) -> Result<Vec<u8>, JsError> {
    crypto::parse_identity(identity_hex).map_err(js_err)?;
    hex::decode(identity_hex).map_err(js_err)
}

#[wasm_bindgen]
pub struct WebAccount {
    inner: Account,
    upload: Option<String>,
}

#[derive(Serialize)]
struct Decrypted {
    from: String,
    text: String,
}

#[wasm_bindgen]
impl WebAccount {
    /// Новий акаунт для сервера з коренем довіри `trust_root_hex`.
    /// Пакет ключів для публікації — у [`WebAccount::key_upload_json`].
    pub fn create(trust_root_hex: &str) -> Result<WebAccount, JsError> {
        let (inner, upload) = Account::create(trust_root_hex).map_err(js_err)?;
        Ok(Self { inner, upload: Some(serde_json::to_string(&upload).map_err(js_err)?) })
    }

    /// Відновити з байтів, збережених через [`WebAccount::export`].
    pub fn load(state: &[u8]) -> Result<WebAccount, JsError> {
        Ok(Self { inner: Account::from_bytes(state).map_err(js_err)?, upload: None })
    }

    /// Увесь стан (із секретними ключами) для збереження в IndexedDB.
    pub fn export(&self) -> Vec<u8> {
        self.inner.to_bytes()
    }

    /// JSON для `PUT /v0/keys` (лише одразу після створення).
    #[wasm_bindgen(js_name = keyUploadJson)]
    pub fn key_upload_json(&self) -> Option<String> {
        self.upload.clone()
    }

    /// Новий набір ключів для `PUT /v0/keys` (коли сервер їх втратив або одноразові закінчуються).
    #[wasm_bindgen(js_name = refreshKeysJson)]
    pub fn refresh_keys_json(&mut self) -> Result<String, JsError> {
        let up = self.inner.refresh_keys().map_err(js_err)?;
        serde_json::to_string(&up).map_err(js_err)
    }

    pub fn identity(&self) -> String {
        self.inner.identity_hex()
    }

    pub fn fingerprint(&self) -> String {
        self.inner.fingerprint()
    }

    pub fn mailbox(&self) -> String {
        self.inner.mailbox().to_hex()
    }

    /// Значення заголовка `x-shepit-auth` для запиту `purpose` (напр. "GET /v0/certificate").
    #[wasm_bindgen(js_name = authHeader)]
    pub fn auth_header(&self, purpose: &str, body: &[u8]) -> String {
        self.inner.auth_header(purpose, body)
    }

    #[wasm_bindgen(js_name = needsCertificate)]
    pub fn needs_certificate(&self) -> bool {
        self.inner.needs_certificate()
    }

    #[wasm_bindgen(js_name = setCertificate)]
    pub fn set_certificate(&mut self, cert_hex: &str) -> Result<(), JsError> {
        let cert = hex::decode(cert_hex.trim()).map_err(js_err)?;
        self.inner.set_certificate(&cert).map_err(js_err)
    }

    /// Спільний код безпеки з `peer` (однаковий на обох пристроях).
    #[wasm_bindgen(js_name = safetyNumber)]
    pub fn safety_number(&self, peer: &str) -> Result<String, JsError> {
        self.inner.safety_number(peer).map_err(js_err)
    }

    #[wasm_bindgen(js_name = hasSession)]
    pub fn has_session(&self, peer: &str) -> bool {
        self.inner.has_session(peer)
    }

    /// Почати сесію за пакетом ключів (`GET /v0/keys/{peer}`).
    #[wasm_bindgen(js_name = startSession)]
    pub fn start_session(&mut self, bundle_json: &str) -> Result<(), JsError> {
        let b: PreKeyBundle = serde_json::from_str(bundle_json).map_err(js_err)?;
        self.inner.start_session(&b).map_err(js_err)
    }

    /// Зашифрувати і запакувати в конверт. Повертає JSON для `POST /v0/send`.
    /// `wake = "call"` — попросити сервер терміново розбудити отримувача (вхідний дзвінок).
    #[wasm_bindgen(js_name = sealEnvelope)]
    pub fn seal_envelope(&mut self, peer: &str, text: &str, wake: Option<String>) -> Result<String, JsError> {
        let to = MailboxId::for_identity(&identity_bytes(peer)?);
        let payload = self.inner.encrypt(peer, text).map_err(js_err)?;
        let mut env = Envelope::new(to, &payload);
        env.wake = wake.filter(|w| w == "call");
        serde_json::to_string(&env).map_err(js_err)
    }

    /// Розшифрувати конверт (поле `envelope` елемента з `GET /v0/mailbox/{id}`).
    /// Повертає JSON `{ "from": ключ-відправника, "text": текст }`.
    #[wasm_bindgen(js_name = openEnvelope)]
    pub fn open_envelope(&mut self, envelope_json: &str) -> Result<String, JsError> {
        let env: Envelope = serde_json::from_str(envelope_json).map_err(js_err)?;
        let payload = env.payload_bytes().ok_or_else(|| js_err("payload hex"))?;
        let (from, text) = self.inner.decrypt(&payload).map_err(js_err)?;
        serde_json::to_string(&Decrypted { from, text }).map_err(js_err)
    }
}

/// Відбиток чужого ключа (12 груп по 5 цифр).
#[wasm_bindgen(js_name = fingerprintOf)]
pub fn fingerprint_of(identity_hex: &str) -> Result<String, JsError> {
    Ok(wire::fingerprint(&identity_bytes(identity_hex)?))
}

/// Чи це валідний ключ ідентичності Шепіт.
#[wasm_bindgen(js_name = isValidIdentity)]
pub fn is_valid_identity(identity_hex: &str) -> bool {
    crypto::parse_identity(identity_hex).is_ok()
}
