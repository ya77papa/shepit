#!/usr/bin/env bash
# Збирає Rust-ядро для Android і генерує Kotlin-біндинги (UniFFI).
# Потрібно: Android NDK (ANDROID_NDK_HOME), cargo-ndk, rustup-цілі для Android.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=clients/android/app/build/generated
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 26 -o "$OUT/jniLibs" build --release --locked -p shepit-android-core
cargo build --locked -p shepit-android-core
cargo run --locked -q -p shepit-android-core --features bindgen --bin uniffi-bindgen -- \
  generate --library target/debug/libshepit_ffi.so --language kotlin --no-format --out-dir "$OUT/uniffi"
echo "Ядро зібрано: $OUT"
