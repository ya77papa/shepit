// Клієнт Шепіт: криптографія — у WASM-ядрі, тут лише мережа і збереження стану.
import init, { WebAccount, fingerprintOf, isValidIdentity } from '../wasm/shepit_web_core.js';
import { kv } from './db';

export { fingerprintOf, isValidIdentity };

const API = ''; // той самий домен, що й веб-клієнт
const AUTH = 'x-shepit-auth';
const MIN_ONE_TIME = 5;

let ready: Promise<unknown> | null = null;
export const loadWasm = () => (ready ??= init());

// Усі операції з акаунтом — строго по черзі: опитування скриньки і надсилання
// змінюють стан сесій, паралельно цього робити не можна.
let queue: Promise<unknown> = Promise.resolve();
function serial<T>(f: () => Promise<T>): Promise<T> {
  const p = queue.then(f, f);
  queue = p.catch(() => {});
  return p;
}

async function http(method: string, path: string, init: RequestInit = {}): Promise<Response> {
  const r = await fetch(API + path, { method, cache: 'no-store', ...init });
  if (r.status === 401) throw new Error('Сервер відхилив підпис — перевір час на пристрої');
  if (!r.ok) throw new Error(`${method} ${path}: ${r.status}`);
  return r;
}

// Усі вкладки цього браузера ділять одне сховище. Якщо акаунт змінився в одній —
// інші перезавантажуються, щоб не працювати зі старими ключами в пам'яті.
const tabs = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('shepit-account') : null;
export function announceAccountChange() {
  tabs?.postMessage('changed');
}
export function onAccountChangedElsewhere(f: () => void) {
  if (tabs) tabs.onmessage = () => f();
}

export class Shepit {
  private constructor(private acc: WebAccount) {}

  get identity() { return this.acc.identity(); }
  get fingerprint() { return this.acc.fingerprint(); }

  /**
   * Потік подій з сервера: викликає `onMail` щоразу, коли в скриньці нова пошта.
   * Перепідключається сам. Повертає функцію зупинки.
   */
  listen(onMail: () => void, onState: (connected: boolean) => void): () => void {
    let stopped = false;
    let ctrl: AbortController | null = null;
    let delay = 1000;
    const loop = async () => {
      while (!stopped) {
        ctrl = new AbortController();
        try {
          const r = await fetch(`${API}/v0/events`, {
            cache: 'no-store',
            signal: ctrl.signal,
            headers: { [AUTH]: this.acc.authHeader('GET /v0/events', new Uint8Array()) },
          });
          if (!r.ok || !r.body) throw new Error(`events: ${r.status}`);
          onState(true);
          delay = 1000;
          const reader = r.body.pipeThrough(new TextDecoderStream()).getReader();
          let buf = '';
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buf += value;
            let i;
            while ((i = buf.indexOf('\n\n')) >= 0) {
              const ev = buf.slice(0, i);
              buf = buf.slice(i + 2);
              if (ev.split('\n').some((l) => l === 'data: mail' || l === 'data:mail')) onMail();
            }
          }
        } catch (e) {
          if (stopped) break;
          console.warn('потік подій:', e);
        }
        onState(false);
        if (stopped) break;
        await new Promise((res) => setTimeout(res, delay));
        delay = Math.min(delay * 2, 30_000);
      }
    };
    loop();
    return () => {
      stopped = true;
      ctrl?.abort();
    };
  }

  private turnCache: { at: number; ice: RTCIceServer[] | null; ttl: number } | null = null;

  /** Сервери для дзвінків. `null` — ретранслятор не налаштований на сервері. */
  async iceServers(): Promise<RTCIceServer[] | null> {
    const now = Date.now();
    if (this.turnCache && now - this.turnCache.at < (this.turnCache.ttl * 1000) / 2) return this.turnCache.ice;
    const r = await fetch(`${API}/v0/turn`, {
      cache: 'no-store',
      headers: { [AUTH]: this.acc.authHeader('GET /v0/turn', new Uint8Array()) },
    });
    if (r.status === 404) {
      this.turnCache = { at: now, ice: null, ttl: 600 };
      return null;
    }
    if (!r.ok) throw new Error(`ретранслятор: ${r.status}`);
    const t = await r.json();
    this.turnCache = { at: now, ice: [{ urls: t.urls, username: t.username, credential: t.credential }], ttl: t.ttl };
    return this.turnCache.ice;
  }

  /** Спільний з `peer` код безпеки (однаковий на обох пристроях). */
  safetyNumber(peer: string): string { return this.acc.safetyNumber(peer); }

  private async save() {
    await kv.set('account', this.acc.export());
  }

  /** Завантажити акаунт з IndexedDB (або null, якщо його ще немає). */
  static async restore(): Promise<Shepit | null> {
    await loadWasm();
    const state = await kv.get<Uint8Array>('account');
    return state ? new Shepit(WebAccount.load(state)) : null;
  }

  /** Створити акаунт: ключі генеруються тут, у браузері. Сервер бачить лише публічні. */
  static async register(): Promise<Shepit> {
    await loadWasm();
    // Акаунт уже створено в іншій вкладці цього браузера — не перезаписувати його!
    const existing = await Shepit.restore();
    if (existing) return existing;
    const root = (await (await http('GET', '/v0/trust-root')).text()).trim();
    const acc = WebAccount.create(root);
    const s = new Shepit(acc);
    await s.publishKeys(acc.keyUploadJson()!);
    await s.ensureCertificate();
    await s.save();
    announceAccountChange();
    return s;
  }

  private async publishKeys(uploadJson: string) {
    const body = new TextEncoder().encode(uploadJson);
    await http('PUT', '/v0/keys', {
      body,
      headers: { 'content-type': 'application/json', [AUTH]: this.acc.authHeader('PUT /v0/keys', body) },
    });
  }

  /** Перевірити, що сервер має мої ключі і їх вистачає; інакше опублікувати нові. */
  ensureKeys(): Promise<void> {
    return serial(async () => {
      const r = await fetch(`${API}/v0/keys/status`, {
        cache: 'no-store',
        headers: { [AUTH]: this.acc.authHeader('GET /v0/keys/status', new Uint8Array()) },
      });
      if (r.ok && (await r.json()).one_time >= MIN_ONE_TIME) return;
      if (!r.ok && r.status !== 404) throw new Error(`статус ключів: ${r.status}`);
      const upload = this.acc.refreshKeysJson();
      await this.save(); // нові приватні ключі — до публікації
      await this.publishKeys(upload);
    });
  }

  private async ensureCertificate() {
    if (!this.acc.needsCertificate()) return;
    const r = await http('GET', '/v0/certificate', {
      headers: { [AUTH]: this.acc.authHeader('GET /v0/certificate', new Uint8Array()) },
    });
    this.acc.setCertificate(await r.text());
  }

  send(peer: string, text: string): Promise<void> {
    return serial(async () => {
      await this.ensureCertificate();
      if (!this.acc.hasSession(peer)) {
        const r = await fetch(`${API}/v0/keys/${peer}`, { cache: 'no-store' });
        if (r.status === 404) throw new Error('Цей ключ не зареєстрований на сервері');
        if (!r.ok) throw new Error(`ключі отримувача: ${r.status}`);
        this.acc.startSession(await r.text());
      }
      const envelope = this.acc.sealEnvelope(peer, text);
      await this.save(); // зберегти стан ратчета ДО відправки
      await http('POST', '/v0/send', { body: envelope, headers: { 'content-type': 'application/json' } });
    });
  }

  /**
   * Забрати вхідні. Для кожного розшифрованого повідомлення викликає `deliver`
   * (там його треба зберегти). Сервер видаляє конверти лише ПІСЛЯ того, як і стан
   * ключів, і самі повідомлення збережено — закрита вкладка нічого не загубить.
   * Повертає кількість нових повідомлень.
   */
  receive(deliver: (m: { from: string; text: string }) => Promise<void>): Promise<number> {
    return serial(async () => {
      const mb = this.acc.mailbox();
      const r = await http('GET', `/v0/mailbox/${mb}`, {
        headers: { [AUTH]: this.acc.authHeader(`GET /v0/mailbox/${mb}`, new Uint8Array()) },
      });
      const items: { id: number; envelope: unknown }[] = await r.json();
      if (!items.length) return 0;
      const opened: { from: string; text: string }[] = [];
      for (const it of items) {
        try {
          opened.push(JSON.parse(this.acc.openEnvelope(JSON.stringify(it.envelope))));
        } catch (err) {
          // Пошкоджене або вже оброблене (повтор після збою) — підтверджуємо, щоб не застрягло.
          console.warn('Відкинуто повідомлення:', err);
        }
      }
      await this.save();
      for (const m of opened) await deliver(m);
      const body = new TextEncoder().encode(JSON.stringify({ upto: items[items.length - 1].id }));
      await http('POST', `/v0/mailbox/${mb}/ack`, {
        body,
        headers: { 'content-type': 'application/json', [AUTH]: this.acc.authHeader(`POST /v0/mailbox/${mb}/ack`, body) },
      });
      return opened.length;
    });
  }
}
