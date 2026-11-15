//! Ядро месенджера Шепіт.
//!
//! - [`wire`] — формати, спільні для клієнта й сервера (конверт, пакет ключів).
//! - [`auth`] — підписи запитів ключем ідентичності і сертифікати відправника (sealed sender).
//! - [`crypto`] — наскрізне шифрування на libsignal (PQXDH + Double Ratchet + sealed sender).
//!   Тільки клієнт.

pub mod wire;

#[cfg(feature = "auth")]
pub mod auth;

#[cfg(feature = "crypto")]
pub mod crypto;
