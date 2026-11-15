// Встановлюваний застосунок (PWA) і контрольовані оновлення.
declare const __SHEPIT_COMMIT__: string;

export const COMMIT: string = __SHEPIT_COMMIT__;
export const SHORT = COMMIT === 'dev' ? 'dev' : COMMIT.slice(0, 7);
export const REPO = 'https://github.com/ya77papa/shepit';
export const commitUrl = (c: string) => (c === 'dev' ? REPO : `${REPO}/commit/${c}`);
export const compareUrl = (from: string, to: string) => `${REPO}/compare/${from}...${to}`;

const CHECK_MS = 30 * 60 * 1000;

/**
 * Зареєструвати service worker. Коли сервер має нову версію, вона завантажується
 * у фоні, але НЕ вмикається: викликається `onUpdate(apply)`, і лише `apply()`
 * (натиснута користувачем кнопка) перемикає на нову версію.
 */
export async function registerServiceWorker(onUpdate: (apply: () => void) => void) {
  if (!('serviceWorker' in navigator) || !import.meta.env.PROD) return;
  const reg = await navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' });
  let requested = false;
  const offer = (w: ServiceWorker) =>
    onUpdate(() => {
      requested = true;
      w.postMessage('SKIP_WAITING');
    });
  // Перезавантажуємось лише тоді, коли оновлення попросив сам користувач.
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (requested) location.reload();
  });
  if (reg.waiting && navigator.serviceWorker.controller) offer(reg.waiting);
  reg.addEventListener('updatefound', () => {
    const w = reg.installing;
    w?.addEventListener('statechange', () => {
      if (w.state === 'installed' && navigator.serviceWorker.controller) offer(w);
    });
  });
  setInterval(() => reg.update().catch(() => {}), CHECK_MS);
}
