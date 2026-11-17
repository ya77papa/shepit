// Що всередині зашифрованого повідомлення. Сервер цього не бачить — для нього це шифротекст.
// Формат: JSON {"v":1,"t":...}. Старі повідомлення (звичайний текст) теж розуміємо.

export type CallOp = 'offer' | 'answer' | 'end' | 'reject' | 'busy';

// id — випадковий ідентифікатор повідомлення: за ним відправник може видалити його «у всіх».
// g — ідентифікатор групи: повідомлення адресоване групі (кожному учаснику окремо).
export type GroupMember = { id: string; name: string };
export type Body =
  | { t: 'text'; text: string; id?: string; g?: string; re?: Reply }
  | { t: 'hello'; name: string }
  | { t: 'voice'; mime: string; dur: number; data: string; ptt?: boolean; id?: string; g?: string; re?: Reply }
  // Фото: JPEG, стиснений і очищений від EXIF/геолокації на пристрої. Сервер тримає його
  // лише до доставки (максимум 3 дні). caption — підпис (необов'язково).
  | { t: 'image'; mime: string; w: number; h: number; data: string; caption?: string; id?: string; g?: string; re?: Reply }
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
  | { t: 'leave'; g: string }
  // Реакція на повідомлення з id (порожня e — прибрати мою реакцію). У групі — з g.
  | { t: 'react'; id: string; e: string; g?: string }
  // Підтвердження: мої повідомлення з цими id доставлено (s:'d') або переглянуто (s:'r'). У групі — з g.
  | { t: 'receipt'; ids: string[]; s: 'd' | 'r'; g?: string };

/** Реакція: 1–8 символів емодзі, без латиниці, цифр і розмітки. */
export const validReaction = (e: unknown): e is string =>
  typeof e === 'string' && (e === '' || ([...e].length <= 8 && e.length <= 32 && !/[\x00-\x7f]/.test(e)));

export const QUICK_REACTIONS = ['👍', '❤️', '😂', '😮', '😢', '🙏', '🔥', '👎'];

/** Емодзі для поля введення (за групами). */
export const EMOJI: [string, string][] = [
  ['Смайли', '😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 💀 💩 🤡 👻 👽 🤖'],
  ['Жести', '👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐 🖖 👋 🤝 🙏 👏 🙌 👐 🤲 💪 ✍️ 🤳 💅 👀 🧠'],
  ['Серця', '❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💯 💢 💥 💫 💦 💨 🔥 ✨ ⭐ 🌟'],
  ['Природа', '🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦆 🦉 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🐙 🐬 🐳 🌸 🌻 🌹 🌷 🌱 🌲 🍀 🍁 🌈 ☀️ 🌙 ⛄ ❄️ ☔ ⚡'],
  ['Їжа', '🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🥕 🌽 🥔 🍞 🧀 🥚 🍳 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🍝 🍜 🍣 🍰 🎂 🍫 🍬 🍩 🍪 ☕ 🍵 🍺 🍷 🥂 🍾'],
  ['Різне', '🎉 🎊 🎁 🎈 🏆 ⚽ 🏀 🎮 🎵 🎶 🎤 🎧 📷 📱 💻 ⌚ 💡 📌 📎 ✏️ 📚 💰 🚗 ✈️ 🚀 🏠 ⏰ ✅ ❌ ❗ ❓ ⚠️ 🇺🇦'],
];

/** Фото в повідомленні: JPEG base64 до ~900 КБ, розумні розміри. */
export function validImage(b: { mime?: unknown; w?: unknown; h?: unknown; data?: unknown; caption?: unknown }): boolean {
  const dim = (n: unknown) => typeof n === 'number' && Number.isInteger(n) && n > 0 && n <= 4096;
  return (
    b.mime === 'image/jpeg' && dim(b.w) && dim(b.h) &&
    typeof b.data === 'string' && b.data.length > 0 && b.data.length < 900_000 && /^[A-Za-z0-9+/=]+$/.test(b.data) &&
    (b.caption === undefined || (typeof b.caption === 'string' && b.caption.length <= 2000))
  );
}

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

/**
 * Відповідь на повідомлення: id оригіналу, хто його написав, короткий уривок (≤ 200 символів).
 * Уривок їде разом із відповіддю — цитату видно, навіть якщо оригіналу в отримувача вже немає.
 */
export type Reply = { id: string; by: string; t: string };
const IDENTITY = /^05[0-9a-f]{64}$/;
export function validReply(r: unknown): Reply | undefined {
  if (!r || typeof r !== 'object') return undefined;
  const { id, by, t } = r as Record<string, unknown>;
  if (!validMsgId(id) || typeof by !== 'string' || !IDENTITY.test(by) || typeof t !== 'string') return undefined;
  return { id, by, t: t.slice(0, 200) };
}

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
