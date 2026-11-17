<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { renderSVG } from 'uqr';
  import { Shepit, announceAccountChange, fingerprintOf, isValidIdentity, loadWasm, normalizeInviteCode, onAccountChangedElsewhere, registrationMode, type RegistrationMode } from './lib/shepit';
  import { channels as channelsDb, contacts as contactsDb, groups as groupsDb, kv, messages as messagesDb, wipe, type Channel, type Contact, type Group, type Member, type Message } from './lib/db';
  import { channelFromServer, fetchChannel, newChannelKeys, parseChannelLink, putMeta, searchPublic, type ChannelMeta } from './lib/channels';
  import Avatar from './Avatar.svelte';
  import ChannelView from './ChannelView.svelte';
  import AvatarEditor from './AvatarEditor.svelte';
  import { exportBackup, importBackup, MIN_PASSWORD } from './lib/backup';
  import { COMMIT, REPO, SHORT, commitUrl, compareUrl, registerServiceWorker } from './lib/pwa';
  import { decode, encode, fromBase64, toBase64, fmtDuration, newMsgId, validAvatar, validImage, validReply, type Reply, validGroupId, validMsgId, validReaction, QUICK_REACTIONS, EMOJI, type Body } from './lib/content';
  import { VoiceRecorder, MAX_VOICE_MS } from './lib/recorder';
  import { CallManager, checkRelay, type RelayCheck } from './lib/call.svelte';
  import VoiceMsg from './VoiceMsg.svelte';
  import ImageMsg from './ImageMsg.svelte';
  import Ticks, { type TickState } from './Ticks.svelte';
  import { prepareImage } from './lib/image';
  import { playSent, playReceived } from './lib/sounds';
  import { avatarSrc } from './lib/avatar';
  import CallOverlay from './CallOverlay.svelte';

  const POLL_MS = 3000;
  // Службове «привітання» після прийняття запрошення: несе ім'я, щоб той, хто запросив,
  // побачив його в контактах. Ім'я йде лише всередині зашифрованого повідомлення.
  const helloText = (name: string) => `👋 ${name || 'Новий контакт'} приєднується за запрошенням`;
  const KEYS_CHECK_MS = 10 * 60 * 1000;

  type Invite = { key: string; name: string; fingerprint: string; code?: string };

  let me: Shepit | null = $state(null);
  let myName = $state('');
  let loading = $state(true);
  let busy = $state(false);
  let error = $state('');
  let notice = $state('');

  let contacts: Contact[] = $state([]);
  let current: Contact | null = $state(null);
  let thread: Message[] = $state([]);
  let draft = $state('');
  let unread: Record<string, number> = $state({});

  let invite: Invite | null = $state(null); // прийшли за посиланням-запрошенням
  let regMode: RegistrationMode = $state('open');
  let inviteCode = $state(''); // код запрошення для реєстрації (з посилання #i=… або введений)
  let myInvite: { code: string | null; mode: RegistrationMode; left: number } | null = $state(null);
  let panel: '' | 'invite' | 'backup' | 'add' | 'restore' | 'calltest' | 'settings' | 'chat' | 'newgroup' | 'group' | 'newchannel' | 'findchannel' = $state('');
  let relayResults: RelayCheck[] | null = $state(null);
  let pushState: 'unsupported' | 'off' | 'on' | 'denied' | 'busy' = $state('unsupported');
  let newName = $state('');
  let newKey = $state('');
  let password = $state('');
  let password2 = $state('');
  let restoreFile: File | null = $state(null);
  let copied = $state('');
  let showSafety = $state(false);
  let selected: Message | null = $state(null); // повідомлення, для якого відкрито дії
  let myAvatar = $state('');
  // Відкритий редактор фото: обрати фрагмент, масштаб, поворот.
  let avatarEdit: { file: File; resolve: (b64: string | null) => void } | null = $state(null);
  function editAvatar(file: File): Promise<string | null> {
    if (!file.type.startsWith('image/')) return Promise.reject(new Error('Це не зображення'));
    return new Promise((resolve) => (avatarEdit = { file, resolve }));
  }
  /** Файл з <input type=file> → редактор → base64 (або null, якщо скасовано). */
  async function pickFrom(e: Event): Promise<string | null> {
    const input = e.currentTarget as HTMLInputElement;
    const f = input.files?.[0];
    input.value = ''; // щоб той самий файл можна було обрати знову
    return f ? editAvatar(f) : null;
  }
  let groups: Group[] = $state([]);
  let group: Group | null = $state(null); // відкрита група
  let channels: Channel[] = $state([]);
  let channel: Channel | null = $state(null); // відкритий канал
  let channelSubscribed = $state(false);
  let channelLinkPending: { id: string; key?: string } | null = $state(null);
  // нова група / канал / пошук
  let newGroupName = $state('');
  let newGroupMembers: string[] = $state([]);
  let newChannelName = $state('');
  let newChannelAbout = $state('');
  let newChannelPublic = $state(false);
  let searchQ = $state('');
  let searchResults: { id: string; name: string; about: string; avatar?: string; posts: number }[] | null = $state(null);
  let addMembers: string[] = $state([]);

  /** Ключ бесіди: контакт — його ідентичність, група — "g:<id>". */
  const chatKey = $derived(group ? `g:${group.id}` : current?.identity ?? '');
  const isAdmin = $derived(!!(group && me && group.admins.includes(me.identity) && !group.left));

  function closeChats() {
    current = null;
    group = null;
    channel = null;
    selected = null;
    replyTo = null;
  }

  /** Як показувати учасника групи: ім'я з моїх контактів, інакше як його назвав адмін. */
  function senderName(id: string | undefined, g: Group | null = group): string {
    if (!id) return '';
    if (me && id === me.identity) return myName || 'Я';
    return contacts.find((c) => c.identity === id)?.name ?? g?.members.find((m) => m.id === id)?.name ?? `Контакт ${short(fingerprintOf(id))}`;
  }

  // ---------- профіль ----------

  /** Надіслати ім'я й фото контактам (усім або одному). */
  async function sendProfile(to?: string[]) {
    if (!me) return;
    const body = encode({ t: 'profile', name: myName.trim(), avatar: myAvatar || undefined });
    for (const id of to ?? contacts.map((c) => c.identity)) await me.send(id, body).catch(() => {});
  }

  async function setMyAvatar(e: Event) {
    let a: string | null;
    try {
      a = await pickFrom(e);
    } catch (err) {
      return fail(err);
    }
    if (!a) return;
    busy = true;
    try {
      myAvatar = a;
      await kv.set('myAvatar', myAvatar);
      await sendProfile();
      notice = 'Фото оновлено і надіслано контактам';
    } catch (err) {
      fail(err);
    } finally {
      busy = false;
    }
  }

  async function removeMyAvatar() {
    myAvatar = '';
    await kv.set('myAvatar', '');
    await sendProfile();
  }

  async function onProfile(from: string, b: Extract<Body, { t: 'profile' }>) {
    const c = contacts.find((x) => x.identity === from);
    if (!c) return;
    const upd: Contact = { ...$state.snapshot(c), avatar: validAvatar(b.avatar) ? b.avatar : undefined };
    const name = typeof b.name === 'string' ? b.name.trim().slice(0, 60) : '';
    // Ім'я, яке дав сам користувач, не чіпаємо; автоматичне — замінюємо.
    if (name && /^(Новий|Контакт) \d/.test(c.name)) upd.name = name;
    await contactsDb.put(upd);
  }

  // ---------- групи ----------

  async function refreshGroups() {
    groups = (await groupsDb.all()).sort((a, b) => a.name.localeCompare(b.name, 'uk'));
  }

  async function openGroup(g: Group) {
    closeChats();
    group = g;
    unread[`g:${g.id}`] = 0;
    thread = await messagesDb.forPeer(`g:${g.id}`);
    await tick();
    threadEl?.scrollTo({ top: threadEl.scrollHeight });
    markRead();
  }

  async function reloadThread() {
    if (group) {
      const g = groups.find((x) => x.id === group!.id);
      if (g) group = g;
      thread = await messagesDb.forPeer(`g:${group.id}`);
    } else if (current) {
      thread = await messagesDb.forPeer(current.identity);
    } else return;
    if (selected && !thread.some((t) => t.id === selected!.id)) selected = null;
    await tick();
    threadEl?.scrollTo({ top: threadEl.scrollHeight });
    markRead();
  }

  // ---------- доставлено / переглянуто ----------

  /** Повідомляти співрозмовникам, що я переглянув (як у Signal: вимкнеш — сам теж не бачиш). */
  let readReceipts = $state(true);
  /** Кому ще треба підтвердити доставку: "кому|група" → id повідомлень. */
  const pendingAcks = new Map<string, { to: string; g?: string; ids: Set<string> }>();

  function noteDelivered(to: string, mid: string | undefined, g?: string) {
    if (chatVisible(g ? `g:${g}` : to)) heardIn = true;
    if (!mid) return;
    const k = `${to}|${g ?? ''}`;
    let e = pendingAcks.get(k);
    if (!e) pendingAcks.set(k, (e = { to, g, ids: new Set() }));
    e.ids.add(mid);
  }

  function sendReceipt(to: string, ids: string[], s: 'd' | 'r', g?: string) {
    if (!me || to === me.identity) return;
    for (let i = 0; i < ids.length; i += 200) {
      const body: Body = { t: 'receipt', ids: ids.slice(i, i + 200), s, ...(g ? { g } : {}) };
      me.send(to, encode(body), 'quiet').catch(() => {}); // не критично
    }
  }

  /** Відкрита бесіда на екрані — позначити вхідні переглянутими і (якщо дозволено) повідомити. */
  async function markRead() {
    if (!me || !chatKey || document.visibilityState !== 'visible') return;
    const key = chatKey;
    const fresh = await messagesDb.takeUnseen(key);
    if (!fresh.length || !readReceipts) return;
    const gid = key.startsWith('g:') ? key.slice(2) : undefined;
    const bySender = new Map<string, string[]>();
    for (const m of fresh) {
      const to = gid ? m.from : key;
      if (!to || !m.mid) continue;
      if (!bySender.has(to)) bySender.set(to, []);
      bySender.get(to)!.push(m.mid);
      pendingAcks.get(`${to}|${gid ?? ''}`)?.ids.delete(m.mid); // «переглянуто» включає «доставлено»
    }
    for (const [to, ids] of bySender) sendReceipt(to, ids, 'r', gid);
  }

  async function flushAcks() {
    if (heardIn && chatSounds) playReceived();
    heardIn = false;
    await markRead();
    for (const { to, g, ids } of pendingAcks.values()) if (ids.size) sendReceipt(to, [...ids], 'd', g);
    pendingAcks.clear();
  }

  async function setReadReceipts(on: boolean) {
    readReceipts = on;
    await kv.set('readReceipts', on);
  }

  /** Стан галочок вихідного повідомлення. */
  function tickOf(m: Message): { state: TickState; title: string } | null {
    if (!m.out || m.kind === 'call' || !me) return null;
    if (m.status === 'sending') return { state: 'sending', title: 'Надсилається' };
    if (m.status === 'failed') return { state: 'failed', title: 'Не надіслано. Натисни, щоб повторити' };
    const others = group ? group.members.map((x) => x.id).filter((id) => id !== me!.identity) : current ? [current.identity] : [];
    if (!others.length) return { state: 'sent', title: 'Надіслано' };
    const rd = new Set(m.rd ?? []);
    const dlv = new Set([...(m.dlv ?? []), ...rd]);
    const nd = others.filter((o) => dlv.has(o)).length;
    const nr = others.filter((o) => rd.has(o)).length;
    const detail = group ? ` (доставлено ${nd} з ${others.length}${readReceipts ? `, переглянуто ${nr}` : ''})` : '';
    if (readReceipts && nr === others.length) return { state: 'read', title: 'Переглянуто' + detail };
    if (nd === others.length) return { state: 'delivered', title: 'Доставлено' + detail };
    return { state: 'sent', title: (group ? 'Надіслано' : 'На сервері, ще не доставлено') + detail };
  }

  /** Передати вихідне повідомлення. true — пішло (у групі — хоча б комусь). */
  async function transmit(key: string, body: Body): Promise<boolean> {
    if (!me) return false;
    if (key.startsWith('g:')) {
      const g = groups.find((x) => x.id === key.slice(2));
      if (!g || g.left) return false;
      const { failed, total } = await sendGroup(g, body);
      return total === 0 || failed < total;
    }
    try {
      await me.send(key, encode(body));
      return true;
    } catch (e) {
      fail(e);
      return false;
    }
  }

  /** Записати вихідне в бесіду («надсилається»), передати, оновити стан. */
  async function sendOut(msg: Message, body: Body) {
    const key = msg.peer;
    const id = (await messagesDb.add({ ...msg, status: 'sending' })) as number;
    if (chatKey === key) await reloadThread();
    const ok = await transmit(key, body);
    await messagesDb.patch(id, { status: ok ? 'sent' : 'failed' });
    if (ok && chatSounds && chatVisible(key)) playSent();
    if (chatKey === key) await reloadThread();
  }

  /** Тіло для повторної спроби — з того, що збережено в бесіді. */
  async function bodyOf(m: Message): Promise<Body | null> {
    if (m.kind === 'voice' && m.voice) return { t: 'voice', mime: m.voice.mime, dur: m.voice.dur, data: toBase64(await m.voice.blob.arrayBuffer()), id: m.mid, re: m.re };
    if (m.kind === 'image' && m.image)
      return { t: 'image', mime: m.image.mime, w: m.image.w, h: m.image.h, data: toBase64(await m.image.blob.arrayBuffer()), caption: m.text !== 'Фото' ? m.text : undefined, id: m.mid, re: m.re };
    if (!m.kind || m.kind === 'text') return { t: 'text', text: m.text, id: m.mid, re: m.re };
    return null;
  }

  async function retry(m: Message) {
    const body = await bodyOf(m);
    if (!body || m.id === undefined) return;
    error = '';
    selected = null;
    await messagesDb.patch(m.id, { status: 'sending' });
    await reloadThread();
    const ok = await transmit(m.peer, body);
    await messagesDb.patch(m.id, { status: ok ? 'sent' : 'failed' });
    if (chatKey === m.peer) await reloadThread();
  }

  /** Надіслати в групу: кожному учаснику окремо (сервер не знає, що це група). */
  async function sendGroup(g: Group, body: Body, to: Member[] = g.members): Promise<{ failed: number; total: number }> {
    if (!me) return { failed: 0, total: 0 };
    const raw = encode({ ...body, g: g.id } as Body);
    let failed = 0;
    for (const m of to) {
      if (m.id === me.identity) continue;
      try {
        await me.send(m.id, raw);
      } catch {
        failed++;
      }
    }
    const total = to.filter((m) => m.id !== me!.identity).length;
    if (failed) error = `Не вдалося доставити ${failed} з ${total} учасникам (спробуй пізніше).`;
    return { failed, total };
  }

  const stateBody = (g: Group): Body => ({ t: 'group', g: g.id, name: g.name, avatar: g.avatar, members: g.members, admins: g.admins, ver: g.v });

  async function logGroup(g: Group, text: string) {
    await messagesDb.add({ peer: `g:${g.id}`, out: false, text, at: Date.now(), kind: 'call' });
  }

  /** Адмін змінив групу: нова версія стану всім (і тим, кого видалили, — щоб знали). */
  async function updateGroup(next: Group, notify: Member[] = next.members) {
    next.v += 1;
    await groupsDb.put(next);
    await refreshGroups();
    group = groups.find((x) => x.id === next.id) ?? null;
    await sendGroup(next, stateBody(next), notify);
    await reloadThread();
  }

  async function createGroup() {
    if (!me) return;
    const name = newGroupName.trim().slice(0, 80);
    if (!name) return fail('Вкажи назву групи');
    if (!newGroupMembers.length) return fail('Обери хоча б одного учасника');
    busy = true;
    try {
      const g: Group = {
        id: newMsgId(), name, v: 0, admins: [me.identity],
        members: [{ id: me.identity, name: myName || 'Я' }, ...newGroupMembers.map((id) => ({ id, name: nameOf(id) }))],
      };
      await logGroup(g, `Групу «${name}» створено`);
      await updateGroup(g);
      newGroupName = '';
      newGroupMembers = [];
      panel = '';
      await openGroup(groups.find((x) => x.id === g.id)!);
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function groupOp(f: (g: Group) => Member[] | void | Promise<Member[] | void>) {
    if (!group) return;
    busy = true;
    try {
      const g: Group = $state.snapshot(group);
      const old = g.members.slice();
      const notify = await f(g);
      await updateGroup(g, notify ?? old);
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  const renameGroup = () => {
    const name = prompt('Назва групи', group?.name)?.trim().slice(0, 80);
    if (name) groupOp((g) => { g.name = name; return g.members; });
  };

  async function setGroupAvatar(e: Event) {
    try {
      const a = await pickFrom(e);
      if (!a) return;
      await groupOp((g) => { g.avatar = a; return g.members; });
    } catch (err) {
      fail(err);
    }
  }

  const addToGroup = () =>
    groupOp(async (g) => {
      const added = addMembers.filter((id) => !g.members.some((m) => m.id === id)).map((id) => ({ id, name: nameOf(id) }));
      g.members.push(...added);
      addMembers = [];
      if (added.length) await logGroup(g, `Додано: ${added.map((m) => m.name).join(', ')}`);
      return g.members;
    });

  const removeFromGroup = (id: string) => {
    if (!confirm(`Видалити ${senderName(id)} з групи?`)) return;
    groupOp(async (g) => {
      const all = g.members.slice();
      g.members = g.members.filter((m) => m.id !== id);
      g.admins = g.admins.filter((a) => a !== id);
      await logGroup(g, `${senderName(id)} видалено з групи`);
      return all;
    });
  };

  const toggleAdmin = (id: string) =>
    groupOp((g) => {
      g.admins = g.admins.includes(id) ? g.admins.filter((a) => a !== id) : [...g.admins, id];
      if (!g.admins.length) g.admins = [me!.identity];
      return g.members;
    });

  async function leaveGroup() {
    if (!group || !me) return;
    if (!confirm(`Вийти з групи «${group.name}»? Ти більше не отримуватимеш повідомлень з неї.`)) return;
    const g: Group = $state.snapshot(group);
    await sendGroup(g, { t: 'leave', g: g.id });
    g.left = true;
    g.members = g.members.filter((m) => m.id !== me!.identity);
    await groupsDb.put(g);
    await logGroup(g, 'Ти вийшов(-ла) з групи');
    await refreshGroups();
    group = groups.find((x) => x.id === g.id) ?? null;
    panel = '';
    await reloadThread();
  }

  async function deleteGroupLocal() {
    if (!group) return;
    if (!confirm(`Видалити групу «${group.name}» і її історію з цього пристрою?`)) return;
    await messagesDb.clear(`g:${group.id}`);
    await groupsDb.delete(group.id);
    closeChats();
    panel = '';
    await refreshGroups();
  }

  function validState(b: Extract<Body, { t: 'group' }>): boolean {
    return (
      validGroupId(b.g) && typeof b.name === 'string' && b.name.length <= 80 && Number.isInteger(b.ver) && b.ver > 0 &&
      Array.isArray(b.members) && b.members.length <= 101 &&
      b.members.every((m) => m && isValidIdentity(m.id) && typeof m.name === 'string') &&
      Array.isArray(b.admins) && b.admins.every((a) => b.members.some((m) => m.id === a)) &&
      (b.avatar === undefined || validAvatar(b.avatar))
    );
  }

  /** Повідомлення, що стосується групи. Повертає ключ бесіди, якщо там щось змінилось. */
  async function onGroupMessage(from: string, b: Body): Promise<string | null> {
    if (!me) return null;
    const gid = (b as { g?: string }).g;
    if (!validGroupId(gid)) return null;
    const key = `g:${gid}`;
    const local = await groupsDb.get(gid);
    if (b.t === 'group') {
      if (!validState(b)) return null;
      const amMember = b.members.some((m) => m.id === me!.identity);
      const next: Group = { id: gid, name: b.name, avatar: b.avatar, members: b.members.map((m) => ({ id: m.id, name: m.name.slice(0, 60) })), admins: b.admins, v: b.ver };
      if (!local) {
        if (!b.admins.includes(from) || !amMember) return null;
        await groupsDb.put(next);
        await logGroup(next, `${senderName(from, next)} додав(-ла) тебе до групи «${next.name}»`);
        return key;
      }
      if (!local.admins.includes(from) || b.ver <= local.v) return null;
      const added = next.members.filter((m) => !local.members.some((x) => x.id === m.id) && m.id !== me!.identity);
      const removed = local.members.filter((m) => !next.members.some((x) => x.id === m.id) && m.id !== me!.identity);
      if (!amMember) next.left = true;
      await groupsDb.put(next);
      if (!amMember && !local.left) await logGroup(next, `${senderName(from, local)} видалив(-ла) тебе з групи`);
      else if (amMember && local.left) await logGroup(next, `${senderName(from, local)} повернув(-ла) тебе до групи`);
      if (amMember && added.length) await logGroup(next, `Додано: ${added.map((m) => senderName(m.id, next)).join(', ')}`);
      if (amMember && removed.length) await logGroup(next, `Видалено: ${removed.map((m) => senderName(m.id, local)).join(', ')}`);
      if (next.name !== local.name) await logGroup(next, `Нова назва: «${next.name}»`);
      return key;
    }
    if (!local || local.left || !local.members.some((m) => m.id === from)) return null;
    if (b.t === 'leave') {
      const name = senderName(from, local);
      local.members = local.members.filter((m) => m.id !== from);
      local.admins = local.admins.filter((a) => a !== from);
      // Без адміністраторів група не лишається: адміном стає учасник з найменшим ключем (у всіх однаково).
      if (!local.admins.length && local.members.length) local.admins = [local.members.map((m) => m.id).sort()[0]!];
      await groupsDb.put(local);
      await logGroup(local, `${name} виходить з групи`);
      return key;
    }
    if (b.t === 'delete') {
      const ids = Array.isArray(b.ids) ? b.ids.filter(validMsgId).slice(0, 500) : [];
      return ids.length && (await messagesDb.deleteIncoming(key, ids, from)) ? key : null;
    }
    if (b.t === 'receipt') {
      const ids = Array.isArray(b.ids) ? b.ids.filter(validMsgId).slice(0, 500) : [];
      return ids.length && (b.s === 'd' || b.s === 'r') && (await messagesDb.receipt(key, ids, from, b.s === 'r')) ? key : null;
    }
    if (b.t === 'react') {
      return validMsgId(b.id) && validReaction(b.e) && (await messagesDb.react(key, b.id, from, b.e)) ? key : null;
    }
    const mid = validMsgId((b as { id?: string }).id) ? (b as { id: string }).id : undefined;
    const re = validReply((b as { re?: unknown }).re);
    if (b.t === 'text') {
      await messagesDb.add({ peer: key, out: false, text: b.text, at: Date.now(), mid, from, re });
    } else if (b.t === 'voice') {
      const blob = new Blob([fromBase64(b.data)], { type: b.mime });
      await messagesDb.add({ peer: key, out: false, text: '🎤 Голосове', at: Date.now(), kind: 'voice', voice: { blob, mime: b.mime, dur: b.dur }, mid, from, re });
    } else if (b.t === 'image' && validImage(b)) {
      await messagesDb.add({ peer: key, out: false, text: b.caption || 'Фото', at: Date.now(), kind: 'image', image: photoOf(b), mid, from, re });
    } else return null;
    noteDelivered(from, mid, gid);
    if (!group || group.id !== gid) unread[key] = (unread[key] ?? 0) + 1;
    return key;
  }

  // ---------- канали ----------

  async function refreshChannels() {
    channels = (await channelsDb.all()).sort((a, b) => a.name.localeCompare(b.name, 'uk'));
  }

  async function openChannel(c: Channel, subscribed = true) {
    closeChats();
    channel = c;
    channelSubscribed = subscribed;
  }

  async function saveChannel(c: Channel) {
    await channelsDb.put(c);
    await refreshChannels();
    if (channel?.id === c.id) channel = channels.find((x) => x.id === c.id) ?? c;
  }

  async function createChannel() {
    if (!me) return;
    const name = newChannelName.trim().slice(0, 80);
    if (!name) return fail('Вкажи назву каналу');
    busy = true;
    try {
      const k = await newChannelKeys(newChannelPublic);
      const rec: Channel = { id: k.id, public: newChannelPublic, key: k.key, pub: k.pub, priv: k.priv, name, about: newChannelAbout.trim().slice(0, 500), lastSeq: 0, readSeq: 0, joined: Date.now() };
      const meta: ChannelMeta = { name: rec.name, about: rec.about };
      await putMeta(rec, meta, (purpose, body) => me!.signRequest(purpose, body));
      await saveChannel(rec);
      newChannelName = newChannelAbout = '';
      panel = '';
      await openChannel(rec);
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  /** Відкрити канал за посиланням (#c=…&k=…): свій/підписаний — одразу, інакше попередній перегляд. */
  async function openChannelLink(l: { id: string; key?: string }) {
    const have = channels.find((c) => c.id === l.id);
    if (have) return openChannel(have);
    try {
      const sc = await fetchChannel(l.id);
      if (!sc) return fail('Канал не знайдено — можливо, його видалено');
      const r = await channelFromServer(sc, l.key);
      if (!r) return fail(sc.public ? 'Канал пошкоджено' : 'Посилання на зашифрований канал без ключа або з неправильним ключем');
      await openChannel(r.rec, false);
    } catch (e) {
      fail(e);
    }
  }

  async function subscribe() {
    if (!channel) return;
    await saveChannel($state.snapshot(channel));
    channelSubscribed = true;
    notice = `Ти підписався(-лась) на «${channel.name}»`;
  }

  async function unsubscribe() {
    if (!channel) return;
    if (!confirm(`Відписатися від «${channel.name}»?`)) return;
    await channelsDb.delete(channel.id);
    closeChats();
    await refreshChannels();
  }

  async function onChannelDeleted() {
    if (!channel) return;
    await channelsDb.delete(channel.id);
    closeChats();
    await refreshChannels();
    notice = 'Канал видалено';
  }

  async function doSearch() {
    try {
      const list = await searchPublic(searchQ.trim());
      searchResults = list.map((c) => ({ id: c.id, name: c.parsed.name, about: c.parsed.about, avatar: c.parsed.avatar, posts: c.posts }));
    } catch (e) {
      fail(e);
    }
  }

  /** Чи є нові дописи в каналах (раз на 2 хв). */
  async function checkChannels() {
    for (const c of channels) {
      try {
        const sc = await fetchChannel(c.id);
        if (!sc) continue;
        if (sc.last_seq !== c.lastSeq) await channelsDb.put({ ...$state.snapshot(c), lastSeq: sc.last_seq });
      } catch { /* мережа */ }
    }
    await refreshChannels();
  }

  /** Видалити повідомлення: лише в мене або в обох (лише свої, нові повідомлення). */
  async function deleteMessage(m: Message, everywhere: boolean) {
    if (!me || (!current && !group)) return;
    busy = true;
    error = '';
    try {
      if (everywhere && m.mid) {
        if (group) await sendGroup(group, { t: 'delete', ids: [m.mid] });
        else await me.send(current!.identity, encode({ t: 'delete', ids: [m.mid] }));
      }
      await messagesDb.delete(m.id!);
      selected = null;
      await reloadThread();
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  let emojiOpen = $state(false);
  let draftEl: HTMLInputElement | undefined = $state();

  /** Вставити емодзі в поле введення там, де курсор. */
  function insertEmoji(e: string) {
    const el = draftEl;
    const at = el?.selectionStart ?? draft.length;
    const end = el?.selectionEnd ?? at;
    draft = draft.slice(0, at) + e + draft.slice(end);
    tick().then(() => {
      el?.focus();
      el?.setSelectionRange(at + e.length, at + e.length);
    });
  }

  /** Поставити реакцію (та сама ще раз — прибрати). Лише на повідомлення з id. */
  async function react(m: Message, e: string) {
    if (!me || !m.mid || (!current && !group)) return;
    const mine = m.reactions?.[me.identity];
    const next = mine === e ? '' : e;
    selected = null;
    try {
      if (group) await sendGroup(group, { t: 'react', id: m.mid, e: next });
      else await me.send(current!.identity, encode({ t: 'react', id: m.mid, e: next }));
      await messagesDb.react(chatKey, m.mid, me.identity, next);
      await reloadThread();
    } catch (err) {
      fail(err);
    }
  }

  /** Реакції під повідомленням: емодзі → кількість, і чи є серед них моя. */
  function reactionChips(m: Message): { e: string; n: number; mine: boolean }[] {
    const out = new Map<string, { e: string; n: number; mine: boolean }>();
    for (const [who, e] of Object.entries(m.reactions ?? {})) {
      const c = out.get(e) ?? { e, n: 0, mine: false };
      c.n++;
      if (who === me?.identity) c.mine = true;
      out.set(e, c);
    }
    return [...out.values()];
  }

  async function clearChat(everywhere: boolean) {
    if (group && !everywhere) {
      if (!confirm(`Очистити історію групи «${group.name}» на цьому пристрої?`)) return;
      await messagesDb.clear(`g:${group.id}`);
      panel = '';
      return reloadThread();
    }
    if (!me || !current) return;
    const peer = current;
    if (!confirm(everywhere
      ? `Очистити всю бесіду з ${peer.name} — і в тебе, і в співрозмовника? Цього не скасувати.`
      : `Очистити всю бесіду з ${peer.name} лише в тебе? У співрозмовника все залишиться.`)) return;
    busy = true;
    try {
      if (everywhere) await me.send(peer.identity, encode({ t: 'clear' }));
      await messagesDb.clear(peer.identity);
      panel = '';
      await openThread(peer);
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function deleteContact() {
    if (!current) return;
    const peer = current;
    if (!confirm(`Видалити контакт ${peer.name} і всю бесіду з ним у тебе? Якщо ця людина напише знову, вона з'явиться як новий контакт.`)) return;
    await messagesDb.clear(peer.identity);
    await contactsDb.delete(peer.identity);
    panel = '';
    closeChats();
    thread = [];
    await refreshContacts();
  }
  let threadEl: HTMLElement | undefined = $state();
  let applyUpdate: (() => void) | null = $state(null); // є нова версія, чекає згоди
  let updatedFrom = $state(''); // версію щойно змінено (показати, з якої)
  let streamOk = $state(false); // відкритий потік подій — пошта приходить миттєво
  const recorder = new VoiceRecorder();
  let recording = $state(false);
  let recStart = $state(0);
  let recNow = $state(0);
  let recTimer: ReturnType<typeof setInterval> | null = null;

  async function logCall(peer: string, text: string) {
    await messagesDb.add({ peer, out: false, text, at: Date.now(), kind: 'call' });
    if (current?.identity === peer) await reloadThread();
  }
  const calls = new CallManager(() => me, logCall);
  const nameOf = (identity: string) => contacts.find((c) => c.identity === identity)?.name ?? `Контакт ${short(fingerprintOf(identity))}`;

  const short = (fp: string) => fp.split(' ').slice(0, 2).join(' ');
  const inviteUrl = $derived(
    me
      ? `${location.origin}/#add=${me.identity}${myName ? `&name=${encodeURIComponent(myName)}` : ''}${myInvite?.code ? `&i=${myInvite.code}` : ''}`
      : '',
  );
  const qr = $derived(inviteUrl ? renderSVG(inviteUrl, { ecc: 'M', border: 2 }) : '');

  // Відкрили «Запросити друга» — дізнатись, чи потрібен код (сервер «за запрошенням»).
  $effect(() => {
    if (panel === 'invite' && me) me.myInvite().then((v) => (myInvite = v)).catch(() => (myInvite = null));
  });

  $effect(() => {
    if (calls.error) {
      error = calls.error;
      calls.error = '';
    }
  });

  function fail(e: unknown) {
    error = e instanceof Error ? e.message : String(e);
  }

  function readInviteFromUrl(): Invite | null {
    const p = new URLSearchParams(location.hash.slice(1));
    const ch = parseChannelLink(location.hash);
    if (ch) {
      channelLinkPending = ch;
      history.replaceState(null, '', location.pathname);
      return null;
    }
    const key = (p.get('add') ?? '').toLowerCase();
    const code = normalizeInviteCode(p.get('i') ?? '') ?? undefined;
    if (code) inviteCode = code;
    if (!key) {
      if (code) history.replaceState(null, '', location.pathname);
      return null;
    }
    history.replaceState(null, '', location.pathname); // прибрати з адресного рядка
    if (!isValidIdentity(key)) {
      error = 'Посилання-запрошення пошкоджене';
      return null;
    }
    const fp = fingerprintOf(key);
    return { key, name: (p.get('name') ?? '').trim().slice(0, 60) || `Контакт ${short(fp)}`, fingerprint: fp, code };
  }

  async function refreshContacts() {
    contacts = (await contactsDb.all()).sort((a, b) => a.name.localeCompare(b.name, 'uk'));
    // Відкрита бесіда — підхопити нове ім'я чи фото співрозмовника.
    if (current) current = contacts.find((c) => c.identity === current!.identity) ?? current;
  }

  async function openThread(c: Contact) {
    closeChats();
    current = c;
    showSafety = false;
    unread[c.identity] = 0;
    thread = await messagesDb.forPeer(c.identity);
    await tick();
    threadEl?.scrollTo({ top: threadEl.scrollHeight });
    markRead();
  }

  async function saveContact(c: Contact) {
    const existing = contacts.find((x) => x.identity === c.identity);
    if (!existing) await contactsDb.put(c);
    await refreshContacts();
    await openThread(existing ?? c);
  }

  async function acceptInvite() {
    if (!invite || !me) return;
    if (invite.key === me.identity) {
      notice = 'Це твоє власне запрошення 🙂';
      invite = null;
      return;
    }
    const inv = invite;
    invite = null;
    await saveContact({ identity: inv.key, name: inv.name, fingerprint: inv.fingerprint });
    // Перше повідомлення — щоб той, хто запросив, теж побачив тебе в контактах (з іменем).
    busy = true;
    try {
      await me.send(inv.key, encode({ t: 'hello', name: myName.trim() }));
      if (myAvatar) await sendProfile([inv.key]);
      await messagesDb.add({ peer: inv.key, out: true, text: helloText(myName.trim()), at: Date.now() });
      if (current?.identity === inv.key) await openThread(current);
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function register() {
    busy = true;
    error = '';
    try {
      const code = normalizeInviteCode(inviteCode);
      if (regMode === 'invite' && !code) throw new Error(inviteCode.trim() ? 'Код запрошення має вигляд XXXX-XXXX-XXXX' : 'Потрібен код запрошення');
      await kv.set('myName', myName.trim());
      me = await Shepit.register(code);
      me.listen(poll, (ok) => (streamOk = ok));
      refreshPush().catch(() => {});
      if (invite) await acceptInvite();
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function addContact() {
    const key = newKey.trim().toLowerCase().replace(/^.*#add=([0-9a-f]+).*$/, '$1'); // можна вставити й посилання
    if (!isValidIdentity(key)) return fail('Це не схоже на ключ Шепоту (66 hex-символів, починається з 05) чи посилання-запрошення');
    if (key === me?.identity) return fail('Це твій власний ключ');
    const fp = fingerprintOf(key);
    await saveContact({ identity: key, name: newName.trim() || `Контакт ${short(fp)}`, fingerprint: fp });
    newName = newKey = '';
    panel = '';
    error = '';
  }

  async function send() {
    const text = draft.trim();
    if (!text || !me || (!current && !group)) return;
    const key = chatKey;
    busy = true;
    error = '';
    try {
      const mid = newMsgId();
      const re = takeReply();
      draft = '';
      await sendOut({ peer: key, out: true, text, at: Date.now(), mid, re }, { t: 'text', text, id: mid, re });
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function sendVoice(blob: Blob, mime: string, dur: number) {
    if (!me || (!current && !group)) return;
    const key = chatKey;
    busy = true;
    try {
      const mid = newMsgId();
      const re = takeReply();
      const body: Body = { t: 'voice', mime, dur, data: toBase64(await blob.arrayBuffer()), id: mid, re };
      await sendOut({ peer: key, out: true, text: '🎤 Голосове', at: Date.now(), kind: 'voice', voice: { blob, mime, dur }, mid, re }, body);
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  const photoOf = (b: { mime: string; w: number; h: number; data: string }) =>
    ({ blob: new Blob([fromBase64(b.data)], { type: b.mime }), mime: b.mime, w: b.w, h: b.h });

  let photoInput: HTMLInputElement | undefined = $state();
  let viewing = $state('');
  /** Збільшений аватар (перший дотик); дотик по ньому — на весь екран. */
  let bigAvatar = $state<{ name: string; src?: string } | null>(null);
  /** На яке повідомлення відповідаю (цитата над полем введення). */
  let replyTo = $state<Message | null>(null);

  const previewOf = (m: Message) =>
    m.kind === 'voice' ? 'Голосове повідомлення' : m.kind === 'image' ? (m.text !== 'Фото' ? `Фото: ${m.text}` : 'Фото') : m.text;

  function startReply(m: Message) {
    replyTo = m;
    selected = null;
    tick().then(() => draftEl?.focus());
  }

  /** Забрати цитату для повідомлення, що надсилається. */
  function takeReply(): Reply | undefined {
    const m = replyTo;
    replyTo = null;
    if (!m?.mid || !me) return undefined;
    const by = m.out ? me.identity : group ? m.from : current?.identity;
    if (!by) return undefined;
    return { id: m.mid, by, t: previewOf(m).slice(0, 200) };
  }

  /** Дотик по цитаті — прокрутити до оригіналу й підсвітити. */
  function jumpTo(mid: string) {
    const el = threadEl?.querySelector<HTMLElement>(`[data-mid="${mid}"]`);
    if (!el) { error = 'Оригінал уже видалено'; return; }
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    el.classList.remove('flash');
    void el.offsetWidth;
    el.classList.add('flash');
  }

  /** Тихі звуки у відкритій бесіді. */
  let chatSounds = $state(true);
  let heardIn = false;
  const chatVisible = (key: string) => chatKey === key && document.visibilityState === 'visible';

  async function sendImage(file: File) {
    if (!me || (!current && !group)) return;
    const key = chatKey;
    busy = true;
    error = '';
    try {
      const { blob, w, h } = await prepareImage(file);
      const caption = draft.trim() || undefined;
      const mid = newMsgId();
      const re = takeReply();
      const body: Body = { t: 'image', mime: 'image/jpeg', w, h, data: toBase64(await blob.arrayBuffer()), caption, id: mid, re };
      draft = '';
      await sendOut({ peer: key, out: true, text: caption || 'Фото', at: Date.now(), kind: 'image', image: { blob, mime: 'image/jpeg', w, h }, mid, re }, body);
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function startRecording() {
    if (recording) return;
    error = '';
    try {
      await recorder.start(() => stopRecording(false));
      recording = true;
      recStart = recNow = Date.now();
      recTimer = setInterval(() => (recNow = Date.now()), 200);
    } catch (e) {
      fail(e instanceof DOMException && e.name === 'NotAllowedError' ? 'Немає доступу до мікрофона' : e);
    }
  }

  async function stopRecording(cancel: boolean) {
    if (!recording) return;
    recording = false;
    if (recTimer) clearInterval(recTimer);
    const rec = await recorder.stop(cancel);
    if (rec) await sendVoice(rec.blob, rec.mime, rec.dur);
  }

  const b64ToBytes = (s: string) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));

  async function refreshPush() {
    if (!me || !import.meta.env.PROD || !('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
      pushState = 'unsupported';
      return;
    }
    if (Notification.permission === 'denied') {
      pushState = 'denied';
      return;
    }
    const reg = await navigator.serviceWorker.ready;
    const sub = await reg.pushManager.getSubscription();
    pushState = sub ? 'on' : 'off';
    if (sub) await me.subscribePush(sub).catch(() => {}); // нагадати серверу (на випадок втрати бази)
  }

  async function enablePush() {
    if (!me) return;
    pushState = 'busy';
    try {
      const r = await fetch('/v0/push/key');
      if (!r.ok) throw new Error('Сервер поки не підтримує сповіщення');
      const key = (await r.text()).trim();
      if ((await Notification.requestPermission()) !== 'granted') {
        pushState = Notification.permission === 'denied' ? 'denied' : 'off';
        return;
      }
      const reg = await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(key) });
      await me.subscribePush(sub);
      pushState = 'on';
      notice = 'Сповіщення увімкнено: дзвінки й повідомлення прийдуть, навіть коли Шепіт закритий.';
    } catch (e) {
      pushState = 'off';
      fail(/permission denied|NotAllowed/i.test(String(e)) ? 'Браузер не дозволив сповіщення (у приватному режимі чи без Google-сервісів вони можуть бути недоступні)' : e);
    }
  }

  async function disablePush() {
    if (!me) return;
    const reg = await navigator.serviceWorker.ready;
    const sub = await reg.pushManager.getSubscription();
    if (sub) {
      await me.unsubscribePush(sub.endpoint).catch(() => {});
      await sub.unsubscribe();
    }
    pushState = 'off';
  }

  async function runCallTest() {
    if (!me) return;
    panel = 'calltest';
    relayResults = null;
    try {
      relayResults = await checkRelay(me);
    } catch (e) {
      relayResults = [{ url: '—', ok: false, detail: String(e) }];
    }
  }

  async function poll() {
    if (!me) return;
    const known = new Set(contacts.map((c) => c.identity));
    const changedPeers = new Set<string>();
    const newcomers: string[] = [];
    let firstFrom = '';
    let delivered = 0;
    try {
      await me.receive(async (m) => {
        const body = decode(m.text);
        // Групи: окремий шлях (і жодних нових контактів від учасників групи).
        if ((body as { g?: unknown }).g !== undefined || body.t === 'group' || body.t === 'leave') {
          const key = await onGroupMessage(m.from, body);
          if (key) changedPeers.add(key);
          return;
        }
        if (body.t === 'receipt') {
          const ids = Array.isArray(body.ids) ? body.ids.filter(validMsgId).slice(0, 500) : [];
          if (known.has(m.from) && ids.length && (body.s === 'd' || body.s === 'r') && (await messagesDb.receipt(m.from, ids, m.from, body.s === 'r')))
            changedPeers.add(m.from);
          return;
        }
        if (body.t === 'react') {
          if (known.has(m.from) && validMsgId(body.id) && validReaction(body.e) && (await messagesDb.react(m.from, body.id, m.from, body.e))) changedPeers.add(m.from);
          return;
        }
        if (body.t === 'profile') {
          if (known.has(m.from)) {
            await onProfile(m.from, body);
            changedPeers.add('contacts');
          }
          return;
        }
        const hello = body.t === 'hello' ? body.name.trim().slice(0, 60) : null;
        if (hello !== null && !known.has(m.from) && myAvatar) newcomers.push(m.from);
        if (!known.has(m.from)) {
          const fp = fingerprintOf(m.from);
          await contactsDb.put({ identity: m.from, name: hello || `Новий ${short(fp)}`, fingerprint: fp });
          known.add(m.from);
          delivered++;
        }
        if (body.t === 'call') {
          await calls.onSignal(m.from, body); // службове — у стрічку не пишемо
          return;
        }
        // Співрозмовник видалив свої повідомлення «у всіх» або очистив бесіду.
        if (body.t === 'delete') {
          const ids = Array.isArray(body.ids) ? body.ids.filter(validMsgId).slice(0, 500) : [];
          if (ids.length && (await messagesDb.deleteIncoming(m.from, ids))) changedPeers.add(m.from);
          return;
        }
        if (body.t === 'clear') {
          await messagesDb.clear(m.from);
          await messagesDb.add({ peer: m.from, out: false, text: `🧹 ${nameOf(m.from)} очистив(-ла) бесіду`, at: Date.now(), kind: 'call' });
          changedPeers.add(m.from);
          return;
        }
        firstFrom ||= m.from;
        delivered++;
        if (body.t === 'voice') {
          const blob = new Blob([fromBase64(body.data)], { type: body.mime });
          await messagesDb.add({
            peer: m.from, out: false, text: '🎤 Голосове', at: Date.now(), kind: 'voice',
            voice: { blob, mime: body.mime, dur: body.dur }, mid: validMsgId(body.id) ? body.id : undefined, re: validReply(body.re),
          });
        } else if (body.t === 'image') {
          if (!validImage(body)) return;
          await messagesDb.add({
            peer: m.from, out: false, text: body.caption || 'Фото', at: Date.now(), kind: 'image',
            image: photoOf(body), mid: validMsgId(body.id) ? body.id : undefined, re: validReply(body.re),
          });
        } else if (body.t === 'hello') {
          await messagesDb.add({ peer: m.from, out: false, text: helloText(body.name.trim()), at: Date.now() });
        } else if (body.t === 'text') {
          await messagesDb.add({ peer: m.from, out: false, text: body.text, at: Date.now(), mid: validMsgId(body.id) ? body.id : undefined, re: validReply(body.re) });
        } else {
          return; // невідомий тип (новіша версія) — пропустити
        }
        if (body.t !== 'hello') noteDelivered(m.from, validMsgId(body.id) ? body.id : undefined);
        if (current?.identity !== m.from) unread[m.from] = (unread[m.from] ?? 0) + 1;
      });
    } catch (e) {
      fail(e); // повідомлення вже збережені локально — показуємо їх нижче
    }
    if ([...changedPeers].some((k) => k.startsWith('g:'))) await refreshGroups();
    if (changedPeers.has('contacts')) await refreshContacts();
    if (chatKey && changedPeers.has(chatKey)) await reloadThread();
    flushAcks();
    // Новий контакт привітався — надіслати йому моє фото.
    if (newcomers.length) sendProfile(newcomers);
    if (!delivered) return;
    await refreshContacts();
    // Нікого не відкрито (напр. перше привітання після запрошення) — відкрити розмову.
    if (current) await reloadThread();
    else if (!group && !channel) {
      const target = contacts.find((c) => c.identity === firstFrom);
      if (target) await openThread(target);
    }
  }

  async function copy(text: string, what: string) {
    await navigator.clipboard.writeText(text);
    copied = what;
    setTimeout(() => (copied = ''), 1500);
  }

  async function share() {
    if (navigator.share) {
      try {
        await navigator.share({ title: 'Шепіт', text: 'Пиши мені в Шепоті — без номера телефону, з наскрізним шифруванням:', url: inviteUrl });
      } catch { /* користувач скасував */ }
    } else {
      await copy(inviteUrl, 'link');
    }
  }

  async function saveName() {
    myName = myName.trim().slice(0, 60);
    await kv.set('myName', myName);
    sendProfile();
  }

  async function rename() {
    if (!current) return;
    const name = prompt('Ім\'я контакту', current.name)?.trim();
    if (!name) return;
    current.name = name;
    await contactsDb.put($state.snapshot(current));
    await refreshContacts();
  }

  async function doBackup() {
    if (password !== password2) return fail('Паролі не збігаються');
    busy = true;
    error = '';
    try {
      const blob = await exportBackup(password);
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `shepit-${short(me!.fingerprint).replace(' ', '-')}-${new Date().toISOString().slice(0, 10)}.shepit-backup`;
      a.click();
      URL.revokeObjectURL(a.href);
      password = password2 = '';
      panel = '';
      notice = 'Резервну копію збережено. Зберігай файл і пароль окремо.';
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function doRestore() {
    if (!restoreFile) return fail('Обери файл резервної копії');
    busy = true;
    error = '';
    try {
      await importBackup(restoreFile, password);
      announceAccountChange();
      password = '';
      me = await Shepit.restore();
      myName = (await kv.get<string>('myName')) ?? '';
      myAvatar = (await kv.get<string>('myAvatar')) ?? '';
      await refreshContacts();
      await refreshGroups();
      await refreshChannels();
      await me?.ensureKeys();
      panel = '';
      notice = 'Акаунт відновлено. Не користуйся ним одночасно в іншому браузері.';
      if (invite) await acceptInvite();
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function logout() {
    if (!confirm('Видалити акаунт з цього браузера? Без резервної копії ключі буде втрачено назавжди.')) return;
    await wipe();
    announceAccountChange();
    location.reload();
  }

  onMount(() => {
    let pollTimer: ReturnType<typeof setInterval>;
    let stopEvents: (() => void) | null = null;
    let keysTimer: ReturnType<typeof setInterval>;
    let channelsTimer: ReturnType<typeof setInterval>;
    const onHash = async () => {
      const inv = readInviteFromUrl();
      if (inv) invite = inv;
      if (channelLinkPending) {
        await openChannelLink(channelLinkPending);
        channelLinkPending = null;
      }
    };
    window.addEventListener('hashchange', onHash);
    onAccountChangedElsewhere(() => location.reload());
    // Застосунок на екрані — прибрати сповіщення (дзвінок покаже сам застосунок) і одразу перевірити пошту.
    const onVisible = async () => {
      if (document.visibilityState !== 'visible') return;
      poll();
      markRead();
      const reg = await navigator.serviceWorker?.getRegistration();
      (await reg?.getNotifications())?.forEach((n) => n.close());
    };
    document.addEventListener('visibilitychange', onVisible);
    registerServiceWorker((apply) => (applyUpdate = apply)).catch((e) => console.warn('service worker:', e));
    (async () => {
      try {
        const prev = await kv.get<string>('version');
        if (prev && prev !== COMMIT) updatedFrom = prev;
        await kv.set('version', COMMIT);
        await loadWasm();
        invite = readInviteFromUrl();
        me = await Shepit.restore();
        if (!me) regMode = await registrationMode();
        myName = (await kv.get<string>('myName')) ?? '';
        myAvatar = (await kv.get<string>('myAvatar')) ?? '';
        readReceipts = (await kv.get<boolean>('readReceipts')) ?? true;
        chatSounds = (await kv.get<boolean>('chatSounds')) ?? true;
        await messagesDb.failStuck();
        if (!(await kv.get<boolean>('receiptsInit'))) {
          await messagesDb.markAllSeen();
          await kv.set('receiptsInit', true);
        }
        await refreshContacts();
        await refreshGroups();
        await refreshChannels();
        if (channelLinkPending) await openChannelLink(channelLinkPending);
        if (me) await me.ensureKeys();
        refreshPush().catch(() => {});
      } catch (e) {
        fail(e);
      } finally {
        loading = false;
      }
      // Миттєва доставка через потік подій; опитування — лише запасний варіант.
      let tickN = 0;
      pollTimer = setInterval(() => {
        tickN++;
        if (!streamOk || tickN % 10 === 0) poll();
      }, POLL_MS);
      keysTimer = setInterval(() => me?.ensureKeys().catch(fail), KEYS_CHECK_MS);
      channelsTimer = setInterval(() => me && checkChannels(), 2 * 60 * 1000);
      if (me) checkChannels();
      if (me) stopEvents = me.listen(poll, (ok) => (streamOk = ok));
      poll();
    })();
    return () => {
      clearInterval(pollTimer);
      clearInterval(keysTimer);
      clearInterval(channelsTimer);
      stopEvents?.();
      window.removeEventListener('hashchange', onHash);
    };
  });
</script>

{#if applyUpdate}
  <div class="update-bar" role="status">
    <span>Доступна нова версія Шепоту. Код нової версії вже перевірено браузером, але ввімкнеться вона лише з твоєї згоди.</span>
    <span class="row">
      <a href={commitUrl(COMMIT)} target="_blank" rel="noopener">Поточна: {SHORT}</a>
      <button class="primary" onclick={() => applyUpdate?.()}>Оновити</button>
      <button class="ghost" onclick={() => (applyUpdate = null)}>Пізніше</button>
    </span>
  </div>
{/if}
{#if updatedFrom}
  <div class="update-bar ok" role="status">
    <span>Шепіт оновлено: {updatedFrom === 'dev' ? 'dev' : updatedFrom.slice(0, 7)} → {SHORT}.</span>
    <span class="row">
      {#if updatedFrom !== 'dev' && COMMIT !== 'dev'}<a href={compareUrl(updatedFrom, COMMIT)} target="_blank" rel="noopener">Що змінилось</a>{/if}
      <button class="ghost" onclick={() => (updatedFrom = '')}>✕</button>
    </span>
  </div>
{/if}
{#if loading}
  <main class="center"><p class="muted">Завантаження…</p></main>
{:else if !me && channel}
  <div class="app solo">
    <main class="chat">
      <ChannelView {channel} subscribed={false} canSubscribe={false} onError={fail} onChange={(c) => (channel = c)} />
      <div class="composer solo-cta">
        <span class="small muted">Щоб підписатися на канал, створи акаунт у Шепоті — це хвилина, без номера телефону.</span>
        <span class="spacer"></span>
        <button class="primary" onclick={() => (channel = null)}>Створити акаунт</button>
      </div>
    </main>
  </div>
{:else if !me}
  <main class="center">
    <div class="card welcome">
      <img src="/favicon.svg" alt="" width="72" height="72" />
      <h1>Шепіт</h1>
      {#if invite}
        <p class="invite-banner"><b>{invite.name}</b> запрошує тебе поспілкуватися в Шепоті.</p>
      {/if}
      <p>Месенджер без номера телефону. Твоя «адреса» — криптографічний ключ, який створюється прямо тут, у браузері.</p>
      {#if panel !== 'restore' && regMode === 'closed'}
        <p class="warn">Реєстрацію нових користувачів на цьому сервері зараз закрито.</p>
        <button class="ghost" onclick={() => { panel = 'restore'; error = ''; }}>У мене вже є акаунт — відновити з резервної копії</button>
      {:else if panel !== 'restore'}
        <input placeholder="Як тебе називати (необов'язково)" bind:value={myName} maxlength="60" />
        <p class="muted small left">Ім'я бачать лише ті, кому ти надішлеш запрошення. Сервер його не знає.</p>
        {#if regMode === 'invite'}
          {#if invite?.code || (inviteCode && normalizeInviteCode(inviteCode))}
            <p class="muted small left">Код запрошення: <span class="mono">{normalizeInviteCode(inviteCode)}</span></p>
          {:else}
            <input placeholder="Код запрошення: XXXX-XXXX-XXXX" bind:value={inviteCode} maxlength="20" autocomplete="off" class="mono" />
            <p class="muted small left">Реєстрація на цьому сервері — лише за запрошенням. Код є в посиланні, яке тобі надіслали.</p>
          {/if}
        {/if}
        <button class="primary" onclick={register} disabled={busy}>{busy ? 'Створюю ключі…' : invite ? 'Створити акаунт і відповісти' : 'Створити акаунт'}</button>
        <button class="ghost" onclick={() => { panel = 'restore'; error = ''; }}>У мене вже є акаунт — відновити з резервної копії</button>
      {:else}
        <div class="form">
          <label class="file">
            <input type="file" accept=".shepit-backup,application/json" onchange={(e) => (restoreFile = (e.currentTarget as HTMLInputElement).files?.[0] ?? null)} />
          </label>
          <input type="password" placeholder="Пароль резервної копії" bind:value={password} autocomplete="current-password" />
          <button class="primary" onclick={doRestore} disabled={busy || !restoreFile || !password}>{busy ? 'Розшифровую…' : 'Відновити акаунт'}</button>
          <button class="ghost" onclick={() => { panel = ''; error = ''; }}>Назад</button>
        </div>
      {/if}
      {#if error}<p class="error">{error}</p>{/if}
      <p class="muted small">Версія <a href={commitUrl(COMMIT)} target="_blank" rel="noopener">{SHORT}</a> · відкритий код</p>
      <p class="warn small">Веб-версія зручна, але менш захищена за нативний застосунок: код завантажується з сервера. Для максимального захисту — <a href="https://github.com/ya77papa/shepit/releases/latest" target="_blank" rel="noopener">встанови застосунок для Android</a>.</p>
    </div>
  </main>
{:else}
  <div class="app">
    <aside>
      <section class="me">
        <div class="row between">
          <Avatar name={myName || '?'} src={myAvatar} size={44} />
          <div class="who">
            <div class="who-name">{myName || 'Без імені'}</div>
            <div class="small muted">Мій ID: <span class="mono">{short(me.fingerprint)}</span></div>
          </div>
          <button class="ghost gear" title="Налаштування" aria-label="Налаштування" onclick={() => (panel = 'settings')}>Налаштування</button>
        </div>
        <button class="calm wide" onclick={() => (panel = 'invite')}>Запросити друга</button>
      </section>

      {#if pushState === 'off'}
        <button class="notify-cta" onclick={enablePush}>Увімкнути сповіщення — щоб дзвінки доходили, коли Шепіт закритий</button>
      {:else if pushState === 'denied'}
        <p class="small warn">Сповіщення заборонені для цього сайту в налаштуваннях браузера — дзвінки не дійдуть, коли Шепіт закритий.</p>
      {/if}

      <section class="contacts">
        <div class="row between">
          <div class="label">Контакти</div>
          <button class="ghost" onclick={() => (panel = 'add')}>+ За ключем</button>
        </div>
        <ul>
          {#each contacts as c (c.identity)}
            <li>
              <button class="chat-item" class:active={current?.identity === c.identity} onclick={() => openThread(c)}>
                <!-- svelte-ignore a11y_click_events_have_key_events -->
                <span class="avatar-btn" role="button" tabindex="-1" title="Переглянути фото" onclick={(e) => { e.stopPropagation(); bigAvatar = { name: c.name, src: c.avatar }; }}>
                  <Avatar name={c.name} src={c.avatar} size={36} />
                </span>
                <span class="item-text">
                  <span class="name">{c.name}</span>
                  <span class="sub">{short(c.fingerprint)}…</span>
                </span>
                {#if unread[c.identity]}<span class="badge">{unread[c.identity]}</span>{/if}
              </button>
            </li>
          {/each}
        </ul>
      </section>

      <section class="contacts">
        <div class="row between">
          <div class="label">Групи</div>
          <button class="ghost" onclick={() => { newGroupMembers = []; panel = 'newgroup'; }} disabled={!contacts.length} title={contacts.length ? '' : 'Спершу додай контакти'}>+ Нова</button>
        </div>
        <ul>
          {#each groups as g (g.id)}
            <li>
              <button class="chat-item" class:active={group?.id === g.id} onclick={() => openGroup(g)}>
                <!-- svelte-ignore a11y_click_events_have_key_events -->
                <span class="avatar-btn" role="button" tabindex="-1" title="Переглянути фото" onclick={(e) => { e.stopPropagation(); bigAvatar = { name: g.name, src: g.avatar }; }}>
                  <Avatar name={g.name} src={g.avatar} size={36} />
                </span>
                <span class="item-text">
                  <span class="name">{g.name}</span>
                  <span class="sub">{g.left ? 'ти не в групі' : `${g.members.length} учасн.`}</span>
                </span>
                {#if unread[`g:${g.id}`]}<span class="badge">{unread[`g:${g.id}`]}</span>{/if}
              </button>
            </li>
          {:else}
            <li class="small muted empty">Груп ще немає</li>
          {/each}
        </ul>
      </section>

      <section class="contacts">
        <div class="row between">
          <div class="label">Канали</div>
          <span class="row">
            <button class="ghost" onclick={() => { searchResults = null; searchQ = ''; panel = 'findchannel'; doSearch(); }}>Знайти</button>
            <button class="ghost" onclick={() => (panel = 'newchannel')}>+ Новий</button>
          </span>
        </div>
        <ul>
          {#each channels as c (c.id)}
            <li>
              <button class="chat-item" class:active={channel?.id === c.id} onclick={() => openChannel(c)}>
                <Avatar name={c.name} src={c.avatar} size={36} />
                <span class="item-text">
                  <span class="name">{c.name}</span>
                  <span class="sub">{c.priv ? 'мій канал' : c.public ? 'публічний' : 'зашифрований'}</span>
                </span>
                {#if c.lastSeq > c.readSeq && channel?.id !== c.id}<span class="badge">•</span>{/if}
              </button>
            </li>
          {:else}
            <li class="small muted empty">Каналів ще немає</li>
          {/each}
        </ul>
      </section>
    </aside>

    <main class="chat">
      {#if invite}
        <div class="invite-confirm">
          <div><b>{invite.name}</b> запрошує тебе. Відбиток: <span class="mono">{short(invite.fingerprint)}…</span></div>
          <div class="row">
            <button class="primary" onclick={acceptInvite} disabled={busy}>Додати і привітатися</button>
            <button class="ghost" onclick={() => (invite = null)}>Не зараз</button>
          </div>
        </div>
      {/if}
      {#if channel}
        <ChannelView
          {channel}
          subscribed={channelSubscribed}
          onSubscribe={subscribe}
          onUnsubscribe={unsubscribe}
          onChange={(c) => (channelSubscribed ? saveChannel(c) : (channel = c))}
          onDeleted={onChannelDeleted}
          onError={fail}
          {editAvatar}
        />
      {:else if current || group}
        {#if group}
        <header>
          <button class="avatar-btn" title="Переглянути фото" onclick={() => (bigAvatar = { name: group!.name, src: group!.avatar })}>
            <Avatar name={group.name} src={group.avatar} size={36} />
          </button>
          <button class="title" onclick={() => (panel = 'group')} title="Учасники й налаштування">{group.name}</button>
          <span class="small muted">{group.members.length} учасн.</span>
          <span class="spacer"></span>
          <button class="ghost" title="Група" onclick={() => (panel = 'group')}>⋯</button>
        </header>
        {:else if current}
        <header>
          <button class="avatar-btn" title="Переглянути фото" onclick={() => (bigAvatar = { name: current!.name, src: current!.avatar })}>
            <Avatar name={current.name} src={current.avatar} size={36} />
          </button>
          <button class="title" onclick={rename} title="Перейменувати">{current.name} </button>
          <button class="ghost small" onclick={() => (showSafety = !showSafety)}>код безпеки</button>
          <span class="spacer"></span>
          <button class="ghost" title="Голосовий дзвінок" disabled={calls.state !== 'idle'} onclick={() => calls.start(current!.identity)}>Дзвінок</button>
          <button class="ghost" title="Бесіда: очистити, видалити контакт" onclick={() => (panel = 'chat')}>⋯</button>
        </header>
        {/if}
        {#if showSafety && current}
          <div class="safety">
            Цей код <b>однаковий у вас обох</b>. Порівняйте його з {current.name} наживо або по телефону: якщо збігається — ніхто не підмінив ключі.
            <div class="fp">{me!.safetyNumber(current.identity)}</div>
          </div>
        {/if}
        <div class="thread" bind:this={threadEl}>
          {#each thread as m (m.id)}
            <div
              class="msg" class:out={m.out} class:system={m.kind === 'call'} class:selected={selected?.id === m.id}
              data-mid={m.mid}
              role="listitem"
              oncontextmenu={(e) => { e.preventDefault(); selected = selected?.id === m.id ? null : m; }}
            >
              {#if group && !m.out && m.from && m.kind !== 'call'}<div class="from small">{senderName(m.from)}</div>{/if}
              {#if m.re}
                <button class="quote" onclick={() => jumpTo(m.re!.id)} title="Перейти до повідомлення">
                  <span class="quote-who">{m.re.by === me?.identity ? 'Ти' : senderName(m.re.by)}</span>
                  <span class="quote-text">{m.re.t}</span>
                </button>
              {/if}
              {#if m.kind === 'voice' && m.voice}
                <div class="bubble"><VoiceMsg voice={m.voice} /></div>
              {:else if m.kind === 'image' && m.image}
                <!-- svelte-ignore a11y_no_static_element_interactions -->
                <div class="bubble image-bubble" oncontextmenu={(e) => { e.preventDefault(); selected = m; }}>
                  <ImageMsg image={m.image} caption={m.text !== 'Фото' ? m.text : ''} onopen={(u) => (viewing = u)} />
                </div>
              {:else}
                <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
                <div class="bubble" onclick={() => (selected = selected?.id === m.id ? null : m)}>{m.text}</div>
              {/if}
              {#if m.reactions && Object.keys(m.reactions).length}
                <div class="reactions">
                  {#each reactionChips(m) as c (c.e)}
                    <button class="chip" class:mine={c.mine} onclick={() => react(m, c.e)} title={c.mine ? 'Прибрати мою реакцію' : 'Поставити таку саму'}>{c.e} {c.n}</button>
                  {/each}
                </div>
              {/if}
              <div class="time">
                {new Date(m.at).toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' })}
                {#if tickOf(m)}{@const t = tickOf(m)!}<Ticks state={t.state} title={t.title} onretry={() => retry(m)} />{/if}
              </div>
            </div>
          {:else}
            <p class="muted center-text">Повідомлень ще немає. Усе, що ви напишете, шифрується на пристрої — сервер бачить лише шифротекст.</p>
          {/each}
        </div>
        {#if group?.left}
          <div class="composer"><span class="muted small">Ти не в цій групі — писати сюди не можна.</span></div>
        {:else if selected}
          <div class="composer msg-actions">
            {#if selected.mid && selected.kind !== 'call'}
              <span class="quick-reactions">
                {#each QUICK_REACTIONS as e (e)}
                  <button class="ghost react-btn" class:mine={selected.reactions?.[me?.identity ?? ''] === e} onclick={() => react(selected!, e)}>{e}</button>
                {/each}
              </span>
            {:else}
              <span class="muted small">Повідомлення:</span>
            {/if}
            <span class="spacer"></span>
            {#if selected.mid && selected.kind !== 'call' && !group?.left}
              <button class="primary" onclick={() => startReply(selected!)}>Відповісти</button>
            {/if}
            {#if selected.status === 'failed'}
              <button class="primary" onclick={() => retry(selected!)}>Надіслати ще раз</button>
            {/if}
            <button onclick={() => deleteMessage(selected!, false)} disabled={busy}>Видалити в мене</button>
            {#if selected.out && selected.mid}
              <button class="danger" onclick={() => deleteMessage(selected!, true)} disabled={busy}>Видалити у всіх</button>
            {/if}
            <button class="ghost" onclick={() => (selected = null)}>✕</button>
          </div>
        {:else if recording}
          <div class="composer recording-bar">
            <span class="rec-dot"></span>
            <span>Запис {fmtDuration(recNow - recStart)} / {fmtDuration(MAX_VOICE_MS)}</span>
            <span class="spacer"></span>
            <button class="ghost" onclick={() => stopRecording(true)}>✕ Скасувати</button>
            <button class="primary" onclick={() => stopRecording(false)}>Надіслати</button>
          </div>
        {:else}
          {#if emojiOpen}
            <div class="emoji-panel">
              {#each EMOJI as [title, list] (title)}
                <div class="small muted emoji-title">{title}</div>
                <div class="emoji-grid">
                  {#each list.split(' ') as e (e)}
                    <button type="button" class="ghost" onclick={() => insertEmoji(e)}>{e}</button>
                  {/each}
                </div>
              {/each}
            </div>
          {/if}
          {#if replyTo}
            <div class="reply-bar">
              <span class="quote static">
                <span class="quote-who">Відповідь: {replyTo.out ? 'Ти' : (group ? senderName(replyTo.from) : current?.name)}</span>
                <span class="quote-text">{previewOf(replyTo)}</span>
              </span>
              <button class="ghost" title="Скасувати відповідь" onclick={() => (replyTo = null)}>✕</button>
            </div>
          {/if}
          <form class="composer" onsubmit={(e) => { e.preventDefault(); emojiOpen = false; send(); }}>
            <button type="button" class="ghost emoji-toggle" class:on={emojiOpen} title="Емодзі" onclick={() => (emojiOpen = !emojiOpen)}>😊</button>
            <button type="button" class="ghost attach" title="Надіслати фото" disabled={busy} onclick={() => photoInput?.click()}>Фото</button>
            <input
              type="file" accept="image/*" hidden bind:this={photoInput}
              onchange={(e) => { const f = e.currentTarget.files?.[0]; e.currentTarget.value = ''; if (f) { emojiOpen = false; sendImage(f); } }}
            />
            <input placeholder="Повідомлення" bind:value={draft} disabled={busy} bind:this={draftEl} />
            {#if draft.trim()}
              <button class="primary" type="submit" disabled={busy}>Надіслати</button>
            {:else}
              <button type="button" title="Голосове повідомлення" disabled={busy} onclick={startRecording}>Голосове</button>
            {/if}
          </form>
        {/if}
      {:else if contacts.length === 0}
        <div class="onboarding">
          <h2>Як почати спілкуватися</h2>
          <ol>
            <li><b>Натисни «Запросити друга»</b> і надішли посилання будь-яким способом (Telegram, SMS, пошта) або покажи QR-код.</li>
            <li><b>Друг відкриває посилання</b> і створює акаунт — ти одразу з'являєшся в його контактах, а він надсилає тобі привітання.</li>
            <li><b>Ви в контактах одне в одного</b> — пишіть. Для впевненості звірте «код безпеки» наживо.</li>
          </ol>
          <button class="calm" onclick={() => (panel = 'invite')}>Запросити друга</button>
          <p class="muted small">Номер телефону, email чи пароль не потрібні. Посилання містить лише твій публічний ключ.</p>
        </div>
      {:else}
        <div class="center"><p class="muted">Обери контакт, групу чи канал.</p></div>
      {/if}
      {#if notice}<div class="toast ok" role="status">{notice} <button class="ghost" onclick={() => (notice = '')}>✕</button></div>{/if}
      {#if error}<div class="toast" role="alert">{error} <button class="ghost" onclick={() => (error = '')}>✕</button></div>{/if}
    </main>
  </div>

  {#if avatarEdit}
    <AvatarEditor
      file={avatarEdit.file}
      onDone={(b) => { avatarEdit?.resolve(b); avatarEdit = null; }}
      onCancel={() => { avatarEdit?.resolve(null); avatarEdit = null; }}
    />
  {/if}

  {#if bigAvatar}
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
    <div class="avatar-viewer" onclick={() => (bigAvatar = null)}>
      <button
        class="big" disabled={!bigAvatar.src} title={bigAvatar.src ? 'Збільшити' : ''}
        onclick={(e) => { e.stopPropagation(); if (bigAvatar?.src) { viewing = avatarSrc(bigAvatar.src); bigAvatar = null; } }}
      >
        <Avatar name={bigAvatar.name} src={bigAvatar.src} size={260} />
      </button>
      <div class="name">{bigAvatar.name}</div>
      {#if bigAvatar.src}<div class="small muted">Натисни фото, щоб збільшити</div>{/if}
    </div>
  {/if}
  {#if viewing}
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
    <div class="photo-viewer" onclick={() => (viewing = '')}>
      <img src={viewing} alt="Фото" />
      <div class="photo-bar">
        <a class="button" href={viewing} download={`shepit-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.jpg`} onclick={(e) => e.stopPropagation()}>Зберегти</a>
        <button onclick={() => (viewing = '')}>Закрити</button>
      </div>
    </div>
  {/if}
  {#if calls.state !== 'idle'}
    <CallOverlay {calls} name={nameOf(calls.peer)} avatar={contacts.find((c) => c.identity === calls.peer)?.avatar} />
  {/if}

  {#if panel}
    <div class="overlay" role="presentation" onclick={(e) => { if (e.target === e.currentTarget) panel = ''; }}>
      <div class="card modal">
        {#if panel === 'newgroup'}
          <h2>Нова група</h2>
          <input placeholder="Назва групи" bind:value={newGroupName} maxlength="80" />
          <p class="small muted">Учасники (до 100). Сервер не знає ні про групу, ні про її склад: повідомлення шифрується кожному окремо.</p>
          <div class="pick">
            {#each contacts as c (c.identity)}
              <label class="row"><input type="checkbox" class="cb" value={c.identity} bind:group={newGroupMembers} /> <Avatar name={c.name} src={c.avatar} size={28} /> {c.name}</label>
            {/each}
          </div>
          <button class="primary" onclick={createGroup} disabled={busy || !newGroupName.trim() || !newGroupMembers.length || newGroupMembers.length > 99}>Створити групу</button>
        {:else if panel === 'group' && group}
          <div class="row">
            <Avatar name={group.name} src={group.avatar} size={56} />
            <div>
              <h2>{group.name}</h2>
              <span class="small muted">{group.members.length} учасн. · {group.left ? 'ти не в групі' : isAdmin ? 'ти адміністратор' : 'учасник'}</span>
            </div>
          </div>
          {#if isAdmin}
            <div class="row wrap">
              <button onclick={renameGroup} disabled={busy}>Назва</button>
              <label class="button-like">Фото<input type="file" accept="image/*" hidden onchange={setGroupAvatar} /></label>
            </div>
          {/if}
          <div class="label">Учасники</div>
          <ul class="members">
            {#each group.members as m (m.id)}
              <li class="row">
                <Avatar name={senderName(m.id)} src={contacts.find((c) => c.identity === m.id)?.avatar ?? (m.id === me?.identity ? myAvatar : undefined)} size={28} />
                <span class="grow">{senderName(m.id)}{#if group.admins.includes(m.id)} <span class="small muted">· адмін</span>{/if}</span>
                {#if isAdmin && m.id !== me?.identity}
                  <button class="ghost small" onclick={() => toggleAdmin(m.id)} disabled={busy}>{group.admins.includes(m.id) ? 'зняти адміна' : 'зробити адміном'}</button>
                  <button class="ghost small danger-text" onclick={() => removeFromGroup(m.id)} disabled={busy}>✕</button>
                {/if}
              </li>
            {/each}
          </ul>
          {#if isAdmin}
            {@const candidates = contacts.filter((c) => !group!.members.some((m) => m.id === c.identity))}
            {#if candidates.length}
              <div class="label">Додати</div>
              <div class="pick">
                {#each candidates as c (c.identity)}
                  <label class="row"><input type="checkbox" class="cb" value={c.identity} bind:group={addMembers} /> {c.name}</label>
                {/each}
              </div>
              <button onclick={addToGroup} disabled={busy || !addMembers.length || group.members.length + addMembers.length > 100}>Додати обраних</button>
            {/if}
          {/if}
          <button onclick={() => clearChat(false)}>Очистити історію в мене</button>
          {#if group.left}
            <button class="danger" onclick={deleteGroupLocal}>Видалити групу з цього пристрою</button>
          {:else}
            <button class="danger" onclick={leaveGroup}>Вийти з групи</button>
          {/if}
        {:else if panel === 'newchannel'}
          <h2>Новий канал</h2>
          <input placeholder="Назва каналу" bind:value={newChannelName} maxlength="80" />
          <textarea placeholder="Опис (необов'язково)" bind:value={newChannelAbout} maxlength="500" rows="3"></textarea>
          <label class="choice"><input type="radio" class="cb" name="ctype" value={false} bind:group={newChannelPublic} /> <span><b>Зашифрований</b><br /><span class="small muted">Читає лише той, хто має посилання: ключ у посиланні, сервер дописів не бачить.</span></span></label>
          <label class="choice"><input type="radio" class="cb" name="ctype" value={true} bind:group={newChannelPublic} /> <span><b>Публічний</b><br /><span class="small muted">Дописи відкриті, канал знаходиться пошуком, читати можна без акаунта.</span></span></label>
          <button class="primary" onclick={createChannel} disabled={busy || !newChannelName.trim()}>{busy ? 'Створюю…' : 'Створити канал'}</button>
          <p class="small warn">Ключ автора зберігається лише на цьому пристрої — зроби резервну копію, інакше втратиш можливість писати в канал.</p>
        {:else if panel === 'findchannel'}
          <h2>Публічні канали</h2>
          <form class="row" onsubmit={(e) => { e.preventDefault(); doSearch(); }}>
            <input placeholder="Назва або опис" bind:value={searchQ} />
            <button type="submit">Шукати</button>
          </form>
          {#if searchResults}
            <ul class="members">
              {#each searchResults as r (r.id)}
                <li><button class="chat-item" onclick={() => { panel = ''; openChannelLink({ id: r.id }); }}>
                  <Avatar name={r.name} src={r.avatar} size={36} />
                  <span class="item-text"><span class="name">{r.name}</span><span class="sub">{r.about || `${r.posts} дописів`}</span></span>
                </button></li>
              {:else}
                <li class="small muted">Нічого не знайдено.</li>
              {/each}
            </ul>
          {/if}
          <p class="small muted">Зашифровані канали в пошуку не з'являються — до них потрапляють лише за посиланням.</p>
        {:else if panel === 'chat' && current}
          <h2>{current.name}</h2>
          <button onclick={() => clearChat(false)} disabled={busy}>Очистити бесіду в мене</button>
          <button onclick={() => clearChat(true)} disabled={busy}>Очистити в мене і в {current.name}</button>
          <button class="danger" onclick={deleteContact} disabled={busy}>Видалити контакт</button>
          <p class="muted small">Щоб видалити окреме повідомлення — натисни на нього (на телефоні можна довго втримати). «Видалити у всіх» доступне для твоїх повідомлень, надісланих у цій версії Шепоту.</p>
        {:else if panel === 'invite'}
          <h2>Запросити друга</h2>
          {#if !myName}<p class="warn small">Підказка: вкажи своє ім'я зліва — тоді друг побачить, від кого запрошення.</p>{/if}
          {#if myInvite?.mode === 'invite'}
            {#if myInvite.code}
              <p class="muted small">Реєстрація на сервері — за запрошенням. Посилання містить твій одноразовий код (діє 30 днів){myInvite.left ? `; ще можна створити ${myInvite.left}` : ''}.</p>
            {:else}
              <p class="warn small">Реєстрація на сервері — за запрошенням, а твої запрошення закінчились. Друг, який уже має Шепіт, може додати тебе за цим посиланням; новому користувачеві потрібен код від адміністратора.</p>
            {/if}
          {:else if myInvite?.mode === 'closed'}
            <p class="warn small">Реєстрацію нових користувачів на сервері закрито — посилання спрацює лише для тих, хто вже має Шепіт.</p>
          {/if}
          <div class="qr">{@html qr}</div>
          <p class="muted small">Друг сканує QR камерою телефона або відкриває посилання:</p>
          <div class="linkbox mono">{inviteUrl}</div>
          <div class="row wrap">
            <button class="primary" onclick={share}>{copied === 'link' ? 'Скопійовано ' : 'Поділитися посиланням'}</button>
            <button onclick={() => copy(inviteUrl, 'link')}>Копіювати</button>
            <button onclick={() => copy(me!.identity, 'key')}>{copied === 'key' ? 'Скопійовано ' : 'Лише ключ'}</button>
          </div>
        {:else if panel === 'settings'}
          <h2>Налаштування</h2>
          <div class="field">
            <span class="label">Моє фото</span>
            <div class="row">
              <Avatar name={myName || '?'} src={myAvatar} size={56} />
              <label class="button-like">Обрати<input type="file" accept="image/*" hidden onchange={setMyAvatar} /></label>
              {#if myAvatar}<button class="ghost" onclick={removeMyAvatar}>Прибрати</button>{/if}
            </div>
            <span class="small muted">Бачать лише твої контакти: фото йде їм зашифрованим, сервер його не бачить.</span>
          </div>
          <label class="field">
            <span class="label">Моє ім'я</span>
            <input placeholder="Як тебе називати" bind:value={myName} onchange={saveName} maxlength="60" />
            <span class="small muted">Бачать лише ті, кого ти запрошуєш. Сервер його не знає.</span>
          </label>
          <div class="field">
            <span class="label">Мій ID</span>
            <span class="mono">{me.fingerprint}</span>
          </div>
          <div class="field">
            <span class="label">Сповіщення</span>
            {#if pushState === 'on'}
              <span class="row wrap">Увімкнено <button class="ghost small" onclick={disablePush}>Вимкнути</button></span>
            {:else if pushState === 'off'}
              <button onclick={enablePush}>Увімкнути</button>
            {:else if pushState === 'busy'}
              <span class="muted">Вмикаю…</span>
            {:else if pushState === 'denied'}
              <span class="warn small">Заборонені в налаштуваннях браузера для цього сайту.</span>
            {:else}
              <span class="muted small">Цей браузер не підтримує сповіщення.</span>
            {/if}
          </div>
          <label class="field check">
            <span class="label">Переглянуто</span>
            <span class="row">
              <input type="checkbox" checked={readReceipts} onchange={(e) => setReadReceipts(e.currentTarget.checked)} />
              Повідомляти, що я переглянув повідомлення
            </span>
            <span class="small muted">Якщо вимкнути, ти теж не бачитимеш, чи переглянули твої. «Доставлено» працює завжди.</span>
          </label>
          <label class="field check">
            <span class="label">Звуки в бесіді</span>
            <span class="row">
              <input type="checkbox" checked={chatSounds} onchange={(e) => { chatSounds = e.currentTarget.checked; kv.set('chatSounds', chatSounds); }} />
              Тихий сигнал «надіслано» й «отримано», коли бесіда відкрита
            </span>
          </label>
          <div class="field">
            <span class="label">Дзвінки</span>
            <button onclick={runCallTest}>Перевірити дзвінки</button>
          </div>
          <div class="field">
            <span class="label">Резервна копія</span>
            <button onclick={() => (panel = 'backup')}>Зберегти ключі у файл</button>
            <span class="small warn">Ключі зберігаються лише в цьому браузері. Без копії очищення даних сайту = втрата акаунта.</span>
          </div>
          <div class="field">
            <span class="label">Версія</span>
            <span class="small"><a href={commitUrl(COMMIT)} target="_blank" rel="noopener">{SHORT}</a> · <a href="/version.json" target="_blank">хеш збірки</a> · <a href={REPO} target="_blank" rel="noopener">відкритий код</a></span>
          </div>
          <button class="danger" onclick={logout}>Вийти й стерти ключі з цього браузера</button>
        {:else if panel === 'calltest'}
          <h2>Перевірка дзвінків</h2>
          <p class="small muted">Перевіряємо, чи цей пристрій досягає ретранслятора голосу.</p>
          {#if !relayResults}
            <p>Перевіряю…</p>
          {:else}
            <ul class="small">
              {#each relayResults as r}
                <li>{r.ok ? 'OK' : 'Помилка'}: <span class="mono">{r.url}</span> — {r.detail}</li>
              {/each}
            </ul>
          {/if}
        {:else if panel === 'add'}
          <h2>Додати за ключем</h2>
          <form class="form" onsubmit={(e) => { e.preventDefault(); addContact(); }}>
            <input placeholder="Ім'я (як ти його називаєш)" bind:value={newName} />
            <input placeholder="Ключ (05…) або посилання-запрошення" bind:value={newKey} spellcheck="false" autocomplete="off" />
            <button class="primary" type="submit">Додати</button>
          </form>
        {:else if panel === 'backup'}
          <h2>Резервна копія</h2>
          <p class="small">Файл містить твої ключі, контакти й історію, зашифровані паролем. З ним можна відновити акаунт в іншому браузері чи після очищення даних.</p>
          <ul class="small warn">
            <li>Без пароля файл відкрити неможливо — і ми не зможемо допомогти, якщо його забудеш.</li>
            <li>Не користуйся одним акаунтом у двох браузерах одночасно: повідомлення дійдуть лише в один.</li>
            <li>Стара копія не містить нових повідомлень; після відновлення з неї розмови можуть потребувати повторного привітання.</li>
          </ul>
          <form class="form" onsubmit={(e) => { e.preventDefault(); doBackup(); }}>
            <input type="password" placeholder={`Пароль (щонайменше ${MIN_PASSWORD} символів)`} bind:value={password} autocomplete="new-password" />
            <input type="password" placeholder="Повтори пароль" bind:value={password2} autocomplete="new-password" />
            <button class="primary" type="submit" disabled={busy || password.length < MIN_PASSWORD}>{busy ? 'Шифрую…' : 'Завантажити файл'}</button>
          </form>
        {/if}
        <button class="ghost close" onclick={() => (panel = '')}>Закрити</button>
      </div>
    </div>
  {/if}
{/if}
