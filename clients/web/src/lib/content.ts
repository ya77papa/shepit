// Що всередині зашифрованого повідомлення. Сервер цього не бачить — для нього це шифротекст.
// Формат: JSON {"v":1,"t":...}. Старі повідомлення (звичайний текст) теж розуміємо.

export type CallOp = 'offer' | 'answer' | 'end' | 'reject' | 'busy';

// id — випадковий ідентифікатор повідомлення: за ним відправник може видалити його «у всіх».
// g — ідентифікатор групи: повідомлення адресоване групі (кожному учаснику окремо).
export type GroupMember = { id: string; name: string };
export type Body =
  | { t: 'text'; text: string; id?: string; g?: string }
  | { t: 'hello'; name: string }
  | { t: 'voice'; mime: string; dur: number; data: string; ptt?: boolean; id?: string; g?: string }
  | { t: 'call'; op: CallOp; id: string; at: number; sdp?: string }
  // Видалити мої повідомлення з цими id у співрозмовника (або в групі g).
  | { t: 'delete'; ids: string[]; g?: string }
  // Очистити всю нашу бесіду у співрозмовника.
  | { t: 'clear' }
  // Мій профіль: ім'я і фото (JPEG base64) — для контактів.
  | { t: 'profile'; name: string; avatar?: string }
  // Стан групи від адміністратора: назва, фото, склад, адміни, версія стану (ver; «v» —
  // версія формату повідомлення, вона завжди 1).
  | { t: 'group'; g: string; name: string; avatar?: string; members: GroupMember[]; admins: string[]; ver: number }
  // Я виходжу з групи.
  | { t: 'leave'; g: string };

const HEX32 = /^[0-9a-f]{32}$/;
export const validGroupId = (id: unknown): id is string => typeof id === 'string' && HEX32.test(id);
/** Фото профілю чи групи: base64 JPEG до ~64 КБ. */
export const validAvatar = (a: unknown): a is string => typeof a === 'string' && a.length < 90_000 && /^[A-Za-z0-9+/=]+$/.test(a);

/** Новий ідентифікатор повідомлення (128 біт). */
export function newMsgId(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');
}

const MSG_ID = /^[0-9a-f]{32}$/;
export const validMsgId = (id: unknown): id is string => typeof id === 'string' && MSG_ID.test(id);

const LEGACY_HELLO = '\u0001hello ';

export const encode = (b: Body): string => JSON.stringify({ ...b, v: 1 });

export function decode(raw: string): Body {
  if (raw.startsWith('{')) {
    try {
      const o = JSON.parse(raw);
      if (o && o.v === 1 && typeof o.t === 'string') return o as Body;
    } catch {
      /* звичайний текст, що починається з «{» */
    }
  }
  if (raw.startsWith(LEGACY_HELLO)) return { t: 'hello', name: raw.slice(LEGACY_HELLO.length).trim() };
  return { t: 'text', text: raw };
}

export function toBase64(buf: ArrayBuffer): string {
  const b = new Uint8Array(buf);
  let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(s);
}

export function fromBase64(s: string): Uint8Array {
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
}

export const fmtDuration = (ms: number) => {
  const s = Math.round(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};
