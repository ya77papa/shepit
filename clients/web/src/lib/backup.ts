// Зашифрована резервна копія акаунта: ключі + контакти + історія.
// Шифрування — стандартний WebCrypto браузера: PBKDF2-SHA256 (600 000 ітерацій) → AES-256-GCM.
// Без пароля файл марний; пароль ніде не зберігається і нікуди не надсилається.

import { channels as channelsDb, contacts as contactsDb, groups as groupsDb, kv, messages as messagesDb, type Channel, type Contact, type Group, type Message } from './db';

const ITERATIONS = 600_000;
export const MIN_PASSWORD = 10;

type StoredMessage = Omit<Message, 'voice' | 'image'> & {
  voice?: { data: string; mime: string; dur: number; ptt?: boolean };
  image?: { data: string; mime: string; w: number; h: number };
};
// groups, channels, myAvatar — з'явились пізніше; старі копії їх не мають.
type Payload = {
  account: string; contacts: Contact[]; messages: StoredMessage[]; myName: string; exportedAt: number;
  groups?: Group[]; channels?: Channel[]; myAvatar?: string;
};
type BackupFile = { shepit: 'backup'; v: 1; kdf: 'PBKDF2-SHA256'; iter: number; salt: string; iv: string; data: string };

const b64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
// btoa(...spread) падає на великих масивах — кодуємо шматками.
function b64big(b: Uint8Array) {
  let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(s);
}

async function deriveKey(password: string, salt: Uint8Array, iter: number) {
  const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

/** Створити зашифрований файл резервної копії. */
export async function exportBackup(password: string): Promise<Blob> {
  if (password.length < MIN_PASSWORD) throw new Error(`Пароль має бути щонайменше ${MIN_PASSWORD} символів`);
  const account = await kv.get<Uint8Array>('account');
  if (!account) throw new Error('Акаунта немає');
  const payload: Payload = {
    account: b64big(account),
    contacts: await contactsDb.all(),
    messages: await Promise.all(
      (await messagesDb.all()).map(async (m) => {
        const bytes = async (b: Blob) => b64big(new Uint8Array(await b.arrayBuffer()));
        const out = { ...m } as unknown as StoredMessage;
        if (m.voice) out.voice = { mime: m.voice.mime, dur: m.voice.dur, ptt: m.voice.ptt, data: await bytes(m.voice.blob) };
        if (m.image) out.image = { mime: m.image.mime, w: m.image.w, h: m.image.h, data: await bytes(m.image.blob) };
        return out;
      }),
    ),
    myName: (await kv.get<string>('myName')) ?? '',
    exportedAt: Date.now(),
    groups: await groupsDb.all(),
    // Містить ключі моїх каналів — без них писати в канал неможливо.
    channels: await channelsDb.all(),
    myAvatar: (await kv.get<string>('myAvatar')) ?? '',
  };
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(password, salt, ITERATIONS);
  const data = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(JSON.stringify(payload))),
  );
  const file: BackupFile = { shepit: 'backup', v: 1, kdf: 'PBKDF2-SHA256', iter: ITERATIONS, salt: b64(salt), iv: b64(iv), data: b64big(data) };
  return new Blob([JSON.stringify(file)], { type: 'application/json' });
}

/** Розшифрувати файл і записати все в IndexedDB цього браузера. */
export async function importBackup(file: File, password: string): Promise<void> {
  let f: BackupFile;
  try {
    f = JSON.parse(await file.text());
  } catch {
    throw new Error('Це не файл резервної копії Шепоту');
  }
  if (f.shepit !== 'backup' || f.v !== 1) throw new Error('Невідомий формат резервної копії');
  const key = await deriveKey(password, unb64(f.salt), f.iter);
  let plain: ArrayBuffer;
  try {
    plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(f.iv) }, key, unb64(f.data));
  } catch {
    throw new Error('Невірний пароль або пошкоджений файл');
  }
  const p: Payload = JSON.parse(new TextDecoder().decode(plain));
  await kv.set('account', unb64(p.account));
  await kv.set('myName', p.myName);
  await kv.set('myAvatar', p.myAvatar ?? '');
  for (const g of p.groups ?? []) await groupsDb.put(g);
  for (const c of p.channels ?? []) await channelsDb.put(c);
  for (const c of p.contacts) await contactsDb.put(c);
  for (const { id: _id, voice, image, ...m } of p.messages) {
    // Відновлена історія — вже переглянута: підтвердження за минуле не розсилаємо.
    const msg: Message = { ...m, seen: true, ...(m.status === 'sending' ? { status: 'failed' as const } : {}) };
    if (voice) msg.voice = { blob: new Blob([unb64(voice.data)], { type: voice.mime }), mime: voice.mime, dur: voice.dur, ptt: voice.ptt };
    if (image) msg.image = { blob: new Blob([unb64(image.data)], { type: image.mime }), mime: image.mime, w: image.w, h: image.h };
    await messagesDb.add(msg);
  }
}
