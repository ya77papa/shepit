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

export class Shepit {
  private constructor(private acc: WebAccount) {}

  get identity() { return this.acc.identity(); }
  get fingerprint() { return this.acc.fingerprint(); }

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
    const root = (await (await http('GET', '/v0/trust-root')).text()).trim();
    const acc = WebAccount.create(root);
    const s = new Shepit(acc);
    await s.publishKeys(acc.keyUploadJson()!);
    await s.ensureCertificate();
    await s.save();
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

  /** Забрати вхідні. Повертає розшифровані повідомлення; биті — пропускає. */
  receive(): Promise<{ from: string; text: string }[]> {
    return serial(async () => {
      const mb = this.acc.mailbox();
      const r = await http('GET', `/v0/mailbox/${mb}`, {
        headers: { [AUTH]: this.acc.authHeader(`GET /v0/mailbox/${mb}`, new Uint8Array()) },
      });
      const envelopes: unknown[] = await r.json();
      const out: { from: string; text: string }[] = [];
      for (const e of envelopes) {
        try {
          out.push(JSON.parse(this.acc.openEnvelope(JSON.stringify(e))));
        } catch (err) {
          console.warn('Відкинуто повідомлення:', err);
        }
      }
      if (envelopes.length) await this.save();
      return out;
    });
  }
}
