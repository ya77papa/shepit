# Розгортання

```
Клієнт ──HTTPS──▶ VPS (nginx, Let's Encrypt) ──Tailscale──▶ SRV (Docker: shepit-server)
                  shepit.razom.isroot.in          100.81.235.117:8443
```

- **SRV** не відкритий в інтернет: ні проброшених портів, ні публічної IP.
- **VPS** лише проксює. Логи доступу вимкнені (`access_log off`), `X-Forwarded-For` не передається —
  сервер Шепіт не знає IP клієнтів.
- Трафік VPS → SRV іде через Tailscale (WireGuard), тобто зашифрований.
- Поверх цього все одно E2EE + sealed sender: навіть VPS і SRV бачать лише шифротекст.

## SRV (`/home/shepit/shepit-server`, користувач `shepit` у групі docker)

```bash
docker compose pull && docker compose up -d     # оновлення до свіжого образу з ghcr.io
docker logs shepit
```

- `docker-compose.yml` — з репозиторію (`server/docker-compose.yml`).
- `docker-compose.override.yml` — локальний: `user: "<uid>:<gid>"` користувача `shepit`.
- `data/issuer.json` — **приватні ключі сервера** (корінь довіри). Бекап обов'язковий.

## VPS (`/etc/nginx/sites-available/shepit`)

nginx `proxy_pass http://100.81.235.117:8443`, сертифікат — certbot (`--nginx`, автопоновлення).

## Образ

GitHub Actions (`.github/workflows/ci.yml`): тести на кожен push, на `main` —
`ghcr.io/ya77papa/shepit-server:latest` і `:<sha>`.
