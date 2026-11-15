//! Годинник, що працює і нативно, і в браузері.
//!
//! У браузері (`wasm32-unknown-unknown`) `SystemTime::now()` панікує, тож беремо час з `Date.now()`.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[cfg(all(target_arch = "wasm32", target_os = "unknown"))]
pub fn now_ms() -> u64 {
    js_sys::Date::now() as u64
}

#[cfg(not(all(target_arch = "wasm32", target_os = "unknown")))]
pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

pub fn now() -> SystemTime {
    UNIX_EPOCH + Duration::from_millis(now_ms())
}
