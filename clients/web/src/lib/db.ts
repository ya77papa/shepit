// Локальне сховище в браузері (IndexedDB). Нічого з цього не йде на сервер.
// УВАГА: стан акаунта містить секретні ключі; IndexedDB не зашифрована (етап 0).

/** avatar — JPEG у base64 (з профілю, який надіслав сам контакт). */
export type Contact = { identity: string; name: string; fingerprint: string; avatar?: string };

/** Учасник групи: ім'я — як його знає той, хто додав. */
export type Member = { id: string; name: string };
/**
 * Група: існує лише на пристроях учасників, сервер про неї не знає.
 * Змінюють склад і назву адміністратори; v — версія стану (більша перемагає).
 */
export type Group = { id: string; name: string; avatar?: string; members: Member[]; admins: string[]; v: number; left?: boolean };

/**
 * Канал, на який я підписаний (або власний). key — ключ шифрування (зашифрований канал),
 * priv — приватний ключ підпису (лише в автора).
 */
export type Channel = {
  id: string; public: boolean; key?: string; pub: string; priv?: string;
  name: string; about: string; avatar?: string;
  lastSeq: number; readSeq: number; joined: number;
};
export type Voice = { blob: Blob; mime: string; dur: number; ptt?: boolean };
export type Photo = { blob: Blob; mime: string; w: number; h: number };
export type Message = {
  id?: number;
  peer: string;
  out: boolean;
  text: string;
  at: number;
  kind?: 'text' | 'voice' | 'call' | 'image';
  voice?: Voice;
  /** Фото (kind = 'image'); text — підпис або «Фото». */
  image?: Photo;
  /** Спільний з співрозмовником id (для «видалити у всіх»); у старих повідомлень немає. */
  mid?: string;
  /** У групі (peer = "g:<id>"): хто надіслав. */
  from?: string;
  /** Реакції: ідентичність → емодзі (одна на людину). */
  reactions?: Record<string, string>;
  /** Вихідне: надсилається / на сервері / не вдалося (старі — без статусу, вважаємо «на сервері»). */
  status?: 'sending' | 'sent' | 'failed';
  /** Вихідне: хто підтвердив доставку і хто переглянув. */
  dlv?: string[];
  rd?: string[];
  /** Вхідне: я вже переглянув (підтвердження перегляду надіслано або вимкнене). */
  seen?: boolean;
};

const DB = 'shepit';
let dbp: Promise<IDBDatabase> | null = null;

function open(): Promise<IDBDatabase> {
  dbp ??= new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, 2);
    r.onupgradeneeded = (e) => {
      const db = r.result;
      if (e.oldVersion < 1) {
        db.createObjectStore('kv');
        db.createObjectStore('contacts', { keyPath: 'identity' });
        db.createObjectStore('messages', { keyPath: 'id', autoIncrement: true }).createIndex('peer', 'peer');
      }
      if (e.oldVersion < 2) {
        db.createObjectStore('groups', { keyPath: 'id' });
        db.createObjectStore('channels', { keyPath: 'id' });
      }
    };
    // Інша вкладка оновлює базу — звільнити її.
    r.onblocked = () => console.warn('IndexedDB: оновлення чекає, поки закриються інші вкладки');
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
  return dbp;
}

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

async function store(name: string, mode: IDBTransactionMode = 'readonly') {
  return (await open()).transaction(name, mode).objectStore(name);
}

/** Реактивні об'єкти Svelte (Proxy) не можна покласти в IndexedDB — зберігаємо звичайну копію. */
const plain = <T>(o: T): T => JSON.parse(JSON.stringify(o));

export const kv = {
  get: async <T>(k: string) => req<T | undefined>((await store('kv')).get(k)),
  set: async (k: string, v: unknown) => req((await store('kv', 'readwrite')).put(v, k)),
};

export const contacts = {
  all: async () => req<Contact[]>((await store('contacts')).getAll()),
  put: async (c: Contact) => req((await store('contacts', 'readwrite')).put(plain(c))),
  delete: async (identity: string) => req((await store('contacts', 'readwrite')).delete(identity)),
};

export const groups = {
  all: async () => req<Group[]>((await store('groups')).getAll()),
  get: async (id: string) => req<Group | undefined>((await store('groups')).get(id)),
  put: async (g: Group) => req((await store('groups', 'readwrite')).put(plain(g))),
  delete: async (id: string) => req((await store('groups', 'readwrite')).delete(id)),
};

export const channels = {
  all: async () => req<Channel[]>((await store('channels')).getAll()),
  get: async (id: string) => req<Channel | undefined>((await store('channels')).get(id)),
  put: async (c: Channel) => req((await store('channels', 'readwrite')).put(plain(c))),
  delete: async (id: string) => req((await store('channels', 'readwrite')).delete(id)),
};

/** Видалити повідомлення бесіди, що підходять під умову. Повертає кількість. */
async function deleteWhere(peer: string, match: (m: Message) => boolean): Promise<number> {
  const st = await store('messages', 'readwrite');
  const all = await req<Message[]>(st.index('peer').getAll(peer));
  const doomed = all.filter(match);
  await Promise.all(doomed.map((m) => req(st.delete(m.id!))));
  return doomed.length;
}

export const messages = {
  forPeer: async (peer: string) => req<Message[]>((await store('messages')).index('peer').getAll(peer)),
  add: async (m: Message) => req((await store('messages', 'readwrite')).add(m)),
  all: async () => req<Message[]>((await store('messages')).getAll()),
  delete: async (id: number) => req((await store('messages', 'readwrite')).delete(id)),
  /** Поставити/прибрати реакцію who на повідомлення з mid у бесіді peer. true — знайдено. */
  react: async (peer: string, mid: string, who: string, e: string): Promise<boolean> => {
    const st = await store('messages', 'readwrite');
    const all = await req<Message[]>(st.index('peer').getAll(peer));
    const m = all.find((x) => x.mid === mid);
    if (!m) return false;
    const r = { ...(m.reactions ?? {}) };
    if (e) r[who] = e;
    else delete r[who];
    m.reactions = r;
    await req(st.put(m));
    return true;
  },
  /** Оновити поля повідомлення. */
  patch: async (id: number, p: Partial<Message>) => {
    const st = await store('messages', 'readwrite');
    const m = await req<Message | undefined>(st.get(id));
    if (m) await req(st.put({ ...m, ...plain(p) }));
  },
  /** who підтвердив доставку (і перегляд, якщо read) моїх повідомлень з mids у бесіді peer. */
  receipt: async (peer: string, mids: string[], who: string, read: boolean): Promise<boolean> => {
    const set = new Set(mids);
    const st = await store('messages', 'readwrite');
    const all = await req<Message[]>(st.index('peer').getAll(peer));
    let changed = false;
    for (const m of all) {
      if (!m.out || !m.mid || !set.has(m.mid)) continue;
      const dlv = new Set(m.dlv ?? []);
      const rd = new Set(m.rd ?? []);
      const before = dlv.size + rd.size;
      dlv.add(who);
      if (read) rd.add(who);
      if (dlv.size + rd.size === before && m.status === 'sent') continue;
      m.dlv = [...dlv];
      m.rd = [...rd];
      m.status = 'sent';
      await req(st.put(m));
      changed = true;
    }
    return changed;
  },
  /** Вхідні з id, які я ще не переглянув, — і позначити переглянутими. */
  takeUnseen: async (peer: string): Promise<Message[]> => {
    const st = await store('messages', 'readwrite');
    const all = await req<Message[]>(st.index('peer').getAll(peer));
    const fresh = all.filter((m) => !m.out && m.mid && !m.seen && m.kind !== 'call');
    for (const m of fresh) await req(st.put({ ...m, seen: true }));
    return fresh;
  },
  /** Перший запуск версії з «переглянуто»: стара історія вважається переглянутою. */
  markAllSeen: async () => {
    const st = await store('messages', 'readwrite');
    const all = await req<Message[]>(st.getAll());
    for (const m of all) if (!m.out && !m.seen) await req(st.put({ ...m, seen: true }));
  },
  /** Після перезапуску: те, що «надсилалось», не надіслалось. */
  failStuck: async () => {
    const st = await store('messages', 'readwrite');
    const all = await req<Message[]>(st.getAll());
    for (const m of all) if (m.status === 'sending') await req(st.put({ ...m, status: 'failed' }));
  },
  /** Усю бесіду з peer. */
  clear: (peer: string) => deleteWhere(peer, () => true),
  /** Повідомлення, які peer надіслав мені, з цими id (він попросив видалити «у всіх»). */
  deleteIncoming: (peer: string, mids: string[], from?: string) => {
    const set = new Set(mids);
    // У групі видалити можна лише власні повідомлення відправника.
    return deleteWhere(peer, (m) => !m.out && !!m.mid && set.has(m.mid) && (from === undefined || m.from === from));
  },
};

/** Повністю стерти локальні дані (вихід з акаунта). */
export async function wipe() {
  (await open()).close();
  dbp = null;
  await req(indexedDB.deleteDatabase(DB));
}
