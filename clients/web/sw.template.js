// Веб-версію чату закрито (ADR 0011). Цей service worker лише прибирає за старою версією:
// одразу стає активним, чистить кеші й перезавантажує відкриті вкладки — вони покажуть
// сторінку завантаження Android-застосунку. Запитів не перехоплює (усе йде в мережу);
// сама сторінка потім знімає його реєстрацію.
const VERSION = __VERSION__;
const FILES = __FILES__;
void VERSION;
void FILES;

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      for (const k of await caches.keys()) await caches.delete(k);
      await self.clients.claim();
      for (const c of await self.clients.matchAll({ type: 'window', includeUncontrolled: true })) {
        c.navigate(c.url).catch(() => {});
      }
    })(),
  );
});
