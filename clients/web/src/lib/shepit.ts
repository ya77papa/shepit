// Клієнт Шепіт: криптографія — у WASM-ядрі, тут лише мережа і збереження стану.
import init, { WebAccount, fingerprintOf, isValidIdentity } from '../wasm/shepit_web_core.js';
import { kv } from './db';

export { fingerprintOf, isValidIdentity };

const API = ''; // той самий домен, що й веб-клієнт

/**
 * Різниця між годинником сервера і цього пристрою (мс). Позначки часу дзвінків рахуємо
 * за годинником сервера, щоб розбіжність годинників телефона й комп'ютера не ламала дзвінки.
 */
let clockOffset = 0;
export function noteDate(r: Response) {
  const d = Date.parse(r.headers.get('date') ?? '');
  if (!Number.isNaN(d)) clockOffset = d + 500 - Date.now();
}
export const serverNow = () => Date.now() + clockOffset;
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

/** Пояснення відмов сервера при реєстрації. */
const REFUSALS: Record<string, string> = {
  'registration-closed': 'Реєстрацію нових користувачів на цьому сервері зараз закрито.',
  'invite-required': 'На цьому сервері реєстрація лише за запрошенням. Попроси посилання в того, хто вже користується Шепотом, або введи код запрошення.',
  'invite-invalid': 'Код запрошення недійсний: можливо, його вже використано, відкликано або минув термін дії.',
  'account-blocked': 'Адміністратор сервера видалив цей акаунт. Надсилати повідомлення з нього більше не можна.',
  'not-registered': 'Акаунт не зареєстровано на сервері (можливо, його видалив адміністратор).',
};

export type RegistrationMode = 'open' | 'invite' | 'closed';

/** Режим реєстрації на сервері (без входу). */
export async function registrationMode(): Promise<RegistrationMode> {
  try {
    const r = await fetch(`${API}/v0/registration`, { cache: 'no-store' });
    if (!r.ok) return 'open'; // старий сервер без режимів
    return (await r.json()).mode as RegistrationMode;
  } catch {
    return 'open';
  }
}

/** Привести код запрошення до вигляду XXXX-XXXX-XXXX (або null, якщо не схоже на код). */
export function normalizeInviteCode(input: string): string | null {
  const raw = input.toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (!/^[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{12}$/.test(raw)) return null;
  return `${raw.slice(0, 4)}-${raw.slice(4, 8)}-${raw.slice(8)}`;
}

async function http(method: string, path: string, init: RequestInit = {}): Promise<Response> {
  const r = await fetch(API + path, { method, cache: 'no-store', ...init });
  noteDate(r);
  if (r.status === 401) throw new Error('Сервер відхилив підпис — перевір час на пристрої');
  if (r.status === 403) {
    const why = REFUSALS[(await r.text().catch(() => '')).trim()];
    if (why) throw new Error(why);
  }
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

  /** Підписати цей браузер на сповіщення (дзвінки й нові повідомлення, коли застосунок закритий). */
  async subscribePush(sub: PushSubscription): Promise<void> {
    const body = new TextEncoder().encode(JSON.stringify(sub.toJSON()));
    await http('PUT', '/v0/push', {
      body,
      headers: { 'content-type': 'application/json', [AUTH]: this.acc.authHeader('PUT /v0/push', body) },
    });
  }

  async unsubscribePush(endpoint: string): Promise<void> {
    const body = new TextEncoder().encode(JSON.stringify({ endpoint }));
    await http('DELETE', '/v0/push', {
      body,
      headers: { 'content-type': 'application/json', [AUTH]: this.acc.authHeader('DELETE /v0/push', body) },
    });
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
  static async register(invite?: string | null): Promise<Shepit> {
    await loadWasm();
    // Акаунт уже створено в іншій вкладці цього браузера — не перезаписувати його!
    const existing = await Shepit.restore();
    if (existing) return existing;
    const root = (await (await http('GET', '/v0/trust-root')).text()).trim();
    const acc = WebAccount.create(root);
    const s = new Shepit(acc);
    // Якщо сервер відмовить (закрито / немає запрошення) — нічого не збережеться.
    await s.publishKeys(acc.keyUploadJson()!, invite);
    await s.ensureCertificate();
    await s.save();
    announceAccountChange();
    return s;
  }

  private async publishKeys(uploadJson: string, invite?: string | null) {
    const body = new TextEncoder().encode(uploadJson);
    const headers: Record<string, string> = { 'content-type': 'application/json', [AUTH]: this.acc.authHeader('PUT /v0/keys', body) };
    if (invite) headers['x-shepit-invite'] = invite;
    await http('PUT', '/v0/keys', { body, headers });
  }

  /** Підпис запиту ключем акаунта (напр. для створення каналу). */
  signRequest(purpose: string, body: Uint8Array): string {
    return this.acc.authHeader(purpose, body);
  }

  /**
   * Код запрошення для мого посилання (коли сервер працює «за запрошенням»).
   * Сервер видає той самий код, поки ним не скористались, і новий — у межах ліміту.
   */
  async myInvite(): Promise<{ code: string | null; mode: RegistrationMode; left: number }> {
    const r = await fetch(`${API}/v0/invites/mine`, {
      method: 'POST',
      cache: 'no-store',
      headers: { [AUTH]: this.acc.authHeader('POST /v0/invites/mine', new Uint8Array()) },
    });
    if (r.status === 404 || r.status === 405) return { code: null, mode: 'open', left: 0 }; // старий сервер
    if (!r.ok) throw new Error(`запрошення: ${r.status}`);
    return r.json();
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

  send(peer: string, text: string, wake?: 'call' | 'quiet'): Promise<void> {
    return serial(async () => {
      await this.ensureCertificate();
      if (!this.acc.hasSession(peer)) {
        const r = await fetch(`${API}/v0/keys/${peer}`, { cache: 'no-store' });
        if (r.status === 404) throw new Error('Цей ключ не зареєстрований на сервері');
        if (!r.ok) throw new Error(`ключі отримувача: ${r.status}`);
        this.acc.startSession(await r.text());
      }
      const envelope = this.acc.sealEnvelope(peer, text, wake);
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
