// Сторона «браузера» для ChannelsInteropTest: ті самі виклики WebCrypto, що в clients/web/src/lib/channels.ts.
// Аргументи: base id key pub priv. Читає дописи (розшифровує), публікує свій, друкує JSON.
const [base, id, key, pub, priv] = process.argv.slice(2);
const enc = new TextEncoder(), dec = new TextDecoder();
const b64u = (b) => Buffer.from(b).toString('base64url');
const unb64u = (s) => new Uint8Array(Buffer.from(s, 'base64url'));
const hex = (b) => Buffer.from(b).toString('hex');
const aes = () => crypto.subtle.importKey('raw', unb64u(key), 'AES-GCM', false, ['encrypt', 'decrypt']);
async function open(data) {
  const raw = unb64u(data);
  return dec.decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: raw.slice(0, 12) }, await aes(), raw.slice(12)));
}
async function seal(plain) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await aes(), enc.encode(plain)));
  return b64u(Buffer.concat([iv, ct]));
}
const raw = unb64u(pub);
const sk = await crypto.subtle.importKey('jwk', { kty: 'EC', crv: 'P-256', d: priv, x: b64u(raw.slice(1, 33)), y: b64u(raw.slice(33, 65)) }, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
const idCheck = hex(await crypto.subtle.digest('SHA-256', raw)).slice(0, 32);
const posts = await (await fetch(`${base}/v0/channels/${id}/posts?limit=10`)).json();
const read = await Promise.all(posts.map((p) => open(p.data).then((t) => JSON.parse(t).text)));
const path = `/v0/channels/${id}/posts`;
const body = enc.encode(JSON.stringify({ data: await seal(JSON.stringify({ v: 1, t: 'text', text: 'привіт з браузера' })) }));
const t = Date.now(), nonce = b64u(crypto.getRandomValues(new Uint8Array(12)));
const msg = `POST ${path}\n${t}\n${nonce}\n${hex(await crypto.subtle.digest('SHA-256', body))}`;
const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, sk, enc.encode(msg));
const r = await fetch(base + path, { method: 'POST', body, headers: { 'content-type': 'application/json', 'x-shepit-channel': `${t}.${nonce}.${b64u(new Uint8Array(sig))}` } });
console.log(JSON.stringify({ idOk: idCheck === id, read, posted: r.status }));
