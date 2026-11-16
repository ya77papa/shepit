// Канали: один пише — багато читають.
//
// Ключ каналу — пара ECDSA P-256 (WebCrypto): публічний ключ визначає ідентифікатор,
// приватним автор підписує зміни. Зашифрований канал має ще ключ AES-256-GCM, який
// живе лише в посиланні-запрошенні (після «#», на сервер не потрапляє).
// Формат сумісний з Android-застосунком і сервером (server/src/channels.rs).

import type { Channel } from './db';

export type ChannelMeta = { name: string; about: string; avatar?: string };
export type PostBody = { v: 1; t: 'text'; text: string };
export type Post = { seq: number; at: number; body: PostBody | null };
export type ServerChannel = { id: string; pub: string; public: boolean; meta: string; created: number; updated: number; posts: number; last_seq: number };

const enc = new TextEncoder();
const dec = new TextDecoder();

export function b64u(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
export function unb64u(s: string): Uint8Array {
  const p = s.replace(/-/g, '+').replace(/_/g, '/');
  return Uint8Array.from(atob(p + '='.repeat((4 - (p.length % 4)) % 4)), (c) => c.charCodeAt(0));
}
const hex = (b: ArrayBuffer) => Array.from(new Uint8Array(b), (x) => x.toString(16).padStart(2, '0')).join('');

export async function channelId(pubRaw: Uint8Array): Promise<string> {
  return hex(await crypto.subtle.digest('SHA-256', pubRaw)).slice(0, 32);
}

/** Новий канал: ключі підпису (і шифрування — для зашифрованого). */
export async function newChannelKeys(isPublic: boolean) {
  const kp = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const pubRaw = new Uint8Array(await crypto.subtle.exportKey('raw', kp.publicKey));
  const jwk = await crypto.subtle.exportKey('jwk', kp.privateKey);
  return {
    id: await channelId(pubRaw),
    pub: b64u(pubRaw),
    priv: jwk.d!, // скаляр d (base64url) — сумісно з Android
    key: isPublic ? undefined : b64u(crypto.getRandomValues(new Uint8Array(32))),
  };
}

async function signingKey(pub: string, priv: string) {
  const raw = unb64u(pub); // 0x04 || x || y
  const jwk: JsonWebKey = { kty: 'EC', crv: 'P-256', d: priv, x: b64u(raw.slice(1, 33)), y: b64u(raw.slice(33, 65)), ext: true };
  return crypto.subtle.importKey('jwk', jwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
}

/** Заголовок підпису ключем каналу: «час.nonce.підпис». */
async function channelAuth(c: Pick<Channel, 'pub' | 'priv'>, method: string, path: string, body: Uint8Array): Promise<string> {
  const t = Date.now();
  const nonce = b64u(crypto.getRandomValues(new Uint8Array(12)));
  const msg = `${method} ${path}\n${t}\n${nonce}\n${hex(await crypto.subtle.digest('SHA-256', body))}`;
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, await signingKey(c.pub, c.priv!), enc.encode(msg));
  return `${t}.${nonce}.${b64u(new Uint8Array(sig))}`;
}

async function aes(key: string) {
  return crypto.subtle.importKey('raw', unb64u(key), 'AES-GCM', false, ['encrypt', 'decrypt']);
}

/** Зашифрувати (зашифрований канал) або лишити відкритим (публічний). */
export async function seal(c: Pick<Channel, 'key'>, plain: string): Promise<string> {
  if (!c.key) return plain;
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await aes(c.key), enc.encode(plain)));
  const out = new Uint8Array(12 + ct.length);
  out.set(iv);
  out.set(ct, 12);
  return b64u(out);
}

/** Розшифрувати; null — не вдалося (не той ключ або пошкоджено). */
export async function open(c: Pick<Channel, 'key'>, data: string): Promise<string | null> {
  if (!c.key) return data;
  try {
    const raw = unb64u(data);
    return dec.decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: raw.slice(0, 12) }, await aes(c.key), raw.slice(12)));
  } catch {
    return null;
  }
}

export function parseMeta(json: string | null): ChannelMeta | null {
  if (!json) return null;
  try {
    const m = JSON.parse(json);
    if (typeof m?.name !== 'string') return null;
    return { name: m.name.slice(0, 80), about: typeof m.about === 'string' ? m.about.slice(0, 500) : '', avatar: typeof m.avatar === 'string' ? m.avatar : undefined };
  } catch {
    return null;
  }
}

function parsePost(json: string | null): PostBody | null {
  if (!json) return null;
  try {
    const p = JSON.parse(json);
    return p?.v === 1 && p.t === 'text' && typeof p.text === 'string' ? { v: 1, t: 'text', text: p.text } : null;
  } catch {
    return null;
  }
}

// ---------- сервер ----------

export async function fetchChannel(id: string): Promise<ServerChannel | null> {
  const r = await fetch(`/v0/channels/${id}`, { cache: 'no-store' });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`канал: ${r.status}`);
  return r.json();
}

/** Опублікувати опис. Для нового каналу потрібен ще підпис акаунта (authHeader). */
export async function putMeta(c: Channel, meta: ChannelMeta, authHeader?: (purpose: string, body: Uint8Array) => string) {
  const path = `/v0/channels/${c.id}`;
  const body = enc.encode(JSON.stringify({ pub: c.pub, public: c.public, meta: await seal(c, JSON.stringify(meta)) }));
  const headers: Record<string, string> = { 'content-type': 'application/json', 'x-shepit-channel': await channelAuth(c, 'PUT', path, body) };
  if (authHeader) headers['x-shepit-auth'] = authHeader(`PUT ${path}`, body);
  const r = await fetch(path, { method: 'PUT', body, headers });
  if (r.status === 403) throw new Error('Створювати канали можуть лише зареєстровані користувачі');
  if (!r.ok) throw new Error(`канал: ${r.status}`);
}

export async function publish(c: Channel, text: string): Promise<number> {
  const path = `/v0/channels/${c.id}/posts`;
  const post: PostBody = { v: 1, t: 'text', text };
  const body = enc.encode(JSON.stringify({ data: await seal(c, JSON.stringify(post)) }));
  const r = await fetch(path, { method: 'POST', body, headers: { 'content-type': 'application/json', 'x-shepit-channel': await channelAuth(c, 'POST', path, body) } });
  if (!r.ok) throw new Error(r.status === 413 ? 'Допис завеликий' : `допис: ${r.status}`);
  return (await r.json()).seq;
}

export async function deletePost(c: Channel, seq: number) {
  const path = `/v0/channels/${c.id}/posts/${seq}`;
  const r = await fetch(path, { method: 'DELETE', headers: { 'x-shepit-channel': await channelAuth(c, 'DELETE', path, new Uint8Array()) } });
  if (!r.ok && r.status !== 404) throw new Error(`видалення: ${r.status}`);
}

export async function deleteChannel(c: Channel) {
  const path = `/v0/channels/${c.id}`;
  const r = await fetch(path, { method: 'DELETE', headers: { 'x-shepit-channel': await channelAuth(c, 'DELETE', path, new Uint8Array()) } });
  if (!r.ok && r.status !== 404) throw new Error(`видалення каналу: ${r.status}`);
}

/** Дописи (за зростанням): після `after` або останні перед `before`. */
export async function fetchPosts(c: Pick<Channel, 'id' | 'key'>, q: { after?: number; before?: number; limit?: number } = {}): Promise<Post[]> {
  const p = new URLSearchParams();
  if (q.after !== undefined) p.set('after', String(q.after));
  if (q.before !== undefined) p.set('before', String(q.before));
  p.set('limit', String(q.limit ?? 50));
  const r = await fetch(`/v0/channels/${c.id}/posts?${p}`, { cache: 'no-store' });
  if (!r.ok) throw new Error(`дописи: ${r.status}`);
  const list: { seq: number; at: number; data: string }[] = await r.json();
  return Promise.all(list.map(async (x) => ({ seq: x.seq, at: x.at, body: parsePost(await open(c, x.data)) })));
}

export async function searchPublic(q: string): Promise<(ServerChannel & { parsed: ChannelMeta })[]> {
  const r = await fetch(`/v0/channels?q=${encodeURIComponent(q)}`, { cache: 'no-store' });
  if (!r.ok) throw new Error(`пошук: ${r.status}`);
  const list: ServerChannel[] = await r.json();
  return list.flatMap((c) => {
    const parsed = parseMeta(c.meta);
    return parsed ? [{ ...c, parsed }] : [];
  });
}

/** Посилання на канал: для зашифрованого — з ключем після «#». */
export const channelLink = (c: Pick<Channel, 'id' | 'key'>) => `${location.origin}/#c=${c.id}${c.key ? `&k=${c.key}` : ''}`;

export function parseChannelLink(hash: string): { id: string; key?: string } | null {
  const p = new URLSearchParams(hash.replace(/^#/, ''));
  const id = p.get('c') ?? '';
  if (!/^[0-9a-f]{32}$/.test(id)) return null;
  const key = p.get('k') ?? undefined;
  if (key !== undefined && unb64uSafe(key)?.length !== 32) return null;
  return { id, key };
}

function unb64uSafe(s: string) {
  try {
    return unb64u(s);
  } catch {
    return null;
  }
}

/** Зібрати запис каналу з даних сервера (перевіривши, що ідентифікатор відповідає ключу). */
export async function channelFromServer(sc: ServerChannel, key?: string): Promise<{ rec: Channel; meta: ChannelMeta } | null> {
  if ((await channelId(unb64u(sc.pub))) !== sc.id) return null;
  if (!sc.public && !key) return null;
  const meta = parseMeta(await open({ key: sc.public ? undefined : key }, sc.meta));
  if (!meta) return null;
  return {
    rec: {
      id: sc.id, public: sc.public, key: sc.public ? undefined : key, pub: sc.pub,
      name: meta.name, about: meta.about, avatar: meta.avatar, lastSeq: sc.last_seq, readSeq: sc.last_seq, joined: Date.now(),
    },
    meta,
  };
}
