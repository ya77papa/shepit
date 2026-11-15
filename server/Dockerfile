# Образ сервера Шепіт. Збирається в GitHub Actions (не на SRV — там слабкий процесор).
# Контекст збірки — корінь репозиторію:  docker build -f server/Dockerfile .

FROM rust:1-bookworm AS build
RUN apt-get update \
 && apt-get install -y --no-install-recommends protobuf-compiler \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
RUN cargo build --release --locked -p shepit-server

FROM debian:bookworm-slim
RUN useradd --system --uid 10001 --no-create-home shepit \
 && mkdir /data && chown shepit /data
COPY --from=build /src/target/release/shepit-server /usr/local/bin/shepit-server
USER shepit
ENV SHEPIT_DATA=/data \
    SHEPIT_LISTEN=0.0.0.0:8443
VOLUME /data
EXPOSE 8443
HEALTHCHECK --interval=30s --timeout=3s CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8443 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
CMD ["shepit-server"]
