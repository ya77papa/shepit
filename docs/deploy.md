# Розгортання

```
Клієнт ──HTTPS──▶ VPS: nginx (Let's Encrypt) ──▶ 127.0.0.1:8443 shepit-server (Docker)
                  shepit.razom.isroot.in
```

Чому VPS, а не домашній SRV: сервер має працювати під час блекаутів.

- Контейнер слухає лише `127.0.0.1` — доступ ззовні тільки через nginx.
- nginx: логи доступу вимкнені (`access_log off`), `X-Forwarded-For` не передається —
  сервер Шепіт не знає IP клієнтів.
- Поверх TLS — E2EE + sealed sender: хостинг бачить лише шифротекст. Навіть з ключами сервера
  неможливо видати себе за іншу людину (клієнт перевіряє «адреса = ключ»).

## VPS (`/home/shepit/shepit-server`, користувач `shepit` у групі docker)

```bash
docker compose pull && docker compose up -d     # оновлення до свіжого образу з ghcr.io
docker logs shepit
```

- `docker-compose.yml` — з репозиторію (`server/docker-compose.yml`).
- `.env` — `SHEPIT_BIND=127.0.0.1`.
- `docker-compose.override.yml` — `user: "<uid>:<gid>"` користувача `shepit`.
- `data/issuer.json` — **приватні ключі сервера** (корінь довіри). Бекап обов'язковий.
  Резервна копія — на SRV у `/home/shepit/shepit-server/data/` (контейнер там зупинено).

nginx: `/etc/nginx/sites-available/shepit`, `proxy_pass http://127.0.0.1:8443`,
сертифікат — certbot (`--nginx`, автопоновлення).

## Образ

GitHub Actions (`.github/workflows/ci.yml`): тести на кожен push, на `main` —
`ghcr.io/ya77papa/shepit-server:latest` і `:<sha>`.
