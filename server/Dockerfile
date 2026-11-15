# Образ сервера Шепіт разом із веб-клієнтом. Збирається в GitHub Actions (не на слабких серверах).
# Контекст збірки — корінь репозиторію:  docker build -f server/Dockerfile .

# 1) Rust: сервер + ядро для браузера (WASM)
FROM rust:1-bookworm AS rust
RUN apt-get update \
 && apt-get install -y --no-install-recommends protobuf-compiler \
 && rm -rf /var/lib/apt/lists/*
RUN rustup target add wasm32-unknown-unknown
# Версія мусить збігатися з wasm-bindgen у Cargo.lock
RUN cargo install --locked wasm-bindgen-cli --version 0.2.130
WORKDIR /src
COPY . .
RUN cargo build --release --locked -p shepit-server \
 && cargo build --release --locked -p shepit-web-core --target wasm32-unknown-unknown \
 && wasm-bindgen --target web --out-dir clients/web/src/wasm \
      target/wasm32-unknown-unknown/release/shepit_web_core.wasm

# 2) Веб-інтерфейс
FROM node:22-bookworm-slim AS web
WORKDIR /web
COPY clients/web/package.json clients/web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY clients/web/ ./
COPY --from=rust /src/clients/web/src/wasm ./src/wasm
RUN npm run build

# 3) Мінімальний образ для запуску
FROM debian:bookworm-slim
RUN useradd --system --uid 10001 --no-create-home shepit \
 && mkdir /data && chown shepit /data
COPY --from=rust /src/target/release/shepit-server /usr/local/bin/shepit-server
COPY --from=web /web/dist /web
USER shepit
ENV SHEPIT_DATA=/data \
    SHEPIT_WEB=/web \
    SHEPIT_LISTEN=0.0.0.0:8443
VOLUME /data
EXPOSE 8443
HEALTHCHECK --interval=30s --timeout=3s CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8443 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
CMD ["shepit-server"]
