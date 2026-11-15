// Service worker Шепоту. Генерується під час збірки (vite.config.ts) — не редагувати dist/sw.js.
//
// Навіщо: код застосунку зберігається в кеші браузера і НЕ підмінюється сервером тихо
// при кожному відкритті. Нова версія чекає (waiting), доки користувач не погодиться
// оновитися в інтерфейсі. Обмеження браузерів: якщо закрити ВСІ вкладки Шепоту, браузер
// може активувати нову версію сам — тоді застосунок покаже, що версія змінилась.

const VERSION = __VERSION__;
const FILES = __FILES__;
const CACHE = `shepit-${VERSION}`;
const NO_CACHE = ['/v0/', '/health', '/sw.js', '/version.json'];

self.addEventListener('install', (e) => {
  // Без skipWaiting: нова версія не стає активною без згоди користувача.
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(FILES)));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('shepit-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('message', (e) => {
  if (e.data === 'SKIP_WAITING') self.skipWaiting();
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  if (NO_CACHE.some((p) => url.pathname.startsWith(p))) return; // API — завжди з мережі
  e.respondWith(
    caches.open(CACHE).then(async (c) => {
      const hit = await c.match(e.request, { ignoreSearch: true });
      if (hit) return hit;
      // Будь-яка сторінка застосунку (напр. /#add=…) — це index.html з кешу.
      if (e.request.mode === 'navigate') {
        const index = await c.match('/index.html');
        if (index) return index;
      }
      return fetch(e.request);
    }),
  );
});
