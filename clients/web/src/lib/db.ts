// Локальне сховище в браузері (IndexedDB). Нічого з цього не йде на сервер.
// УВАГА: стан акаунта містить секретні ключі; IndexedDB не зашифрована (етап 0).

export type Contact = { identity: string; name: string; fingerprint: string };
export type Voice = { blob: Blob; mime: string; dur: number; ptt?: boolean };
export type Message = {
  id?: number;
  peer: string;
  out: boolean;
  text: string;
  at: number;
  kind?: 'text' | 'voice' | 'call';
  voice?: Voice;
  /** Спільний з співрозмовником id (для «видалити у всіх»); у старих повідомлень немає. */
  mid?: string;
};

const DB = 'shepit';
let dbp: Promise<IDBDatabase> | null = null;

function open(): Promise<IDBDatabase> {
  dbp ??= new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, 1);
    r.onupgradeneeded = () => {
      const db = r.result;
      db.createObjectStore('kv');
      db.createObjectStore('contacts', { keyPath: 'identity' });
      db.createObjectStore('messages', { keyPath: 'id', autoIncrement: true }).createIndex('peer', 'peer');
    };
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

export const kv = {
  get: async <T>(k: string) => req<T | undefined>((await store('kv')).get(k)),
  set: async (k: string, v: unknown) => req((await store('kv', 'readwrite')).put(v, k)),
};

export const contacts = {
  all: async () => req<Contact[]>((await store('contacts')).getAll()),
  put: async (c: Contact) => req((await store('contacts', 'readwrite')).put(c)),
  delete: async (identity: string) => req((await store('contacts', 'readwrite')).delete(identity)),
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
  /** Усю бесіду з peer. */
  clear: (peer: string) => deleteWhere(peer, () => true),
  /** Повідомлення, які peer надіслав мені, з цими id (він попросив видалити «у всіх»). */
  deleteIncoming: (peer: string, mids: string[]) => {
    const set = new Set(mids);
    return deleteWhere(peer, (m) => !m.out && !!m.mid && set.has(m.mid));
  },
};

/** Повністю стерти локальні дані (вихід з акаунта). */
export async function wipe() {
  (await open()).close();
  dbp = null;
  await req(indexedDB.deleteDatabase(DB));
}
