# Розгортання

```
Клієнт ──HTTPS──▶ VPS: nginx (Let's Encrypt) ──▶ 127.0.0.1:8443 shepit-server (Docker)
                  shepit.razom.isroot.in
```

Сервер працює в дата-центрі, щоб бути доступним незалежно від відключень світла.

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
- `data/shepit.db` — SQLite: опубліковані публічні ключі та недоставлені (зашифровані) конверти.
  Втрата не критична: клієнти самі перепублікують ключі, загубляться лише недоставлені повідомлення.

nginx: `/etc/nginx/sites-available/shepit`, `proxy_pass http://127.0.0.1:8443`,
сертифікат — certbot (`--nginx`, автопоновлення).

## Дзвінки (TURN)

Ретранслятор голосу — сервіс `turn` у тому ж `docker-compose.yml` (coturn, мережа хоста).
У `.env` на сервері:

```
COMPOSE_PROFILES=calls
SHEPIT_TURN_SECRET=<openssl rand -hex 32>
SHEPIT_TURN_URLS=turn:<IP>:3478?transport=udp,turn:<IP>:3478?transport=tcp
SHEPIT_TURN_IP=<публічна IP VPS>
```

`SHEPIT_TURN_IP` обов'язковий: на сервері з кількома інтерфейсами (Docker, VPN) coturn інакше
може віддати клієнтам недосяжну адресу ретранслятора, і дзвінок зависне на «З'єднання…».
У `SHEPIT_TURN_URLS` краще вказувати IP, а не домен: деякі браузери не можуть розв'язати
ім'я TURN-сервера (помилка 701 «host lookup»).

Порти: 3478/udp+tcp, 49160–49400/udp. На VPS увімкнений UFW (політика DROP), тож їх треба відкрити:

```bash
sudo ufw allow 3478/tcp comment 'shepit turn'
sudo ufw allow 3478/udp comment 'shepit turn'
sudo ufw allow 49160:49400/udp comment 'shepit turn relay'
```

Перевірка ззовні: `Test-NetConnection <IP> -Port 3478` (Windows) або «📞 Перевірити дзвінки» в застосунку.
Без TURN дзвінки йдуть напряму (IP видно співрозмовнику).

## Перевірка веб-клієнта

Код веб-клієнта завантажується з сервера, тож важливо, щоб сервер роздавав саме те, що зібрано
з відкритого коду. Для кожного коміту CI публікує **хеш збірки** у звіті (Actions → запуск → Summary).
Порівняти з тим, що роздає сервер зараз:

```bash
curl -s https://shepit.razom.isroot.in/version.json | jq '{commit, build}'
```

У `version.json` також є SHA-256 кожного файлу. Застосунок встановлюється як PWA: код береться
з кешу браузера, а нова версія вмикається лише після кнопки «Оновити» (або коли закриті всі вкладки —
тоді застосунок покаже, з якої версії на яку оновився, з посиланням на зміни).

## Образ

GitHub Actions (`.github/workflows/ci.yml`): тести на кожен push, на `main` —
`ghcr.io/ya77papa/shepit-server:latest` і `:<sha>`.
