//! Ядро месенджера Шепіт.
//!
//! - [`wire`] — формати, спільні для клієнта й сервера (конверт, пакет ключів). Без криптографії.
//! - [`crypto`] — наскрізне шифрування на libsignal (PQXDH + Double Ratchet). Тільки клієнт.

pub mod wire;

#[cfg(feature = "crypto")]
pub mod crypto;
