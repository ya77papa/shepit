<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { renderSVG } from 'uqr';
  import { Shepit, fingerprintOf, isValidIdentity, loadWasm } from './lib/shepit';
  import { contacts as contactsDb, kv, messages as messagesDb, wipe, type Contact, type Message } from './lib/db';
  import { exportBackup, importBackup, MIN_PASSWORD } from './lib/backup';

  const POLL_MS = 3000;
  // Службове «привітання» після прийняття запрошення: несе ім'я, щоб той, хто запросив,
  // побачив його в контактах. Ім'я йде лише всередині зашифрованого повідомлення.
  const HELLO = '\u0001hello ';
  const helloText = (name: string) => `👋 ${name || 'Новий контакт'} приєднується за запрошенням`;
  const KEYS_CHECK_MS = 10 * 60 * 1000;

  type Invite = { key: string; name: string; fingerprint: string };

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
  let panel: '' | 'invite' | 'backup' | 'add' | 'restore' = $state('');
  let newName = $state('');
  let newKey = $state('');
  let password = $state('');
  let password2 = $state('');
  let restoreFile: File | null = $state(null);
  let copied = $state('');
  let showSafety = $state(false);
  let threadEl: HTMLElement | undefined = $state();

  const short = (fp: string) => fp.split(' ').slice(0, 2).join(' ');
  const inviteUrl = $derived(
    me ? `${location.origin}/#add=${me.identity}${myName ? `&name=${encodeURIComponent(myName)}` : ''}` : '',
  );
  const qr = $derived(inviteUrl ? renderSVG(inviteUrl, { ecc: 'M', border: 2 }) : '');

  function fail(e: unknown) {
    error = e instanceof Error ? e.message : String(e);
  }

  function readInviteFromUrl(): Invite | null {
    const p = new URLSearchParams(location.hash.slice(1));
    const key = (p.get('add') ?? '').toLowerCase();
    if (!key) return null;
    history.replaceState(null, '', location.pathname); // прибрати з адресного рядка
    if (!isValidIdentity(key)) {
      error = 'Посилання-запрошення пошкоджене';
      return null;
    }
    const fp = fingerprintOf(key);
    return { key, name: (p.get('name') ?? '').trim().slice(0, 60) || `Контакт ${short(fp)}`, fingerprint: fp };
  }

  async function refreshContacts() {
    contacts = (await contactsDb.all()).sort((a, b) => a.name.localeCompare(b.name, 'uk'));
  }

  async function openThread(c: Contact) {
    current = c;
    showSafety = false;
    unread[c.identity] = 0;
    thread = await messagesDb.forPeer(c.identity);
    await tick();
    threadEl?.scrollTo({ top: threadEl.scrollHeight });
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
      await me.send(inv.key, HELLO + myName.trim());
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
      await kv.set('myName', myName.trim());
      me = await Shepit.register();
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
    if (!text || !me || !current) return;
    const peer = current;
    busy = true;
    error = '';
    try {
      await me.send(peer.identity, text);
      await messagesDb.add({ peer: peer.identity, out: true, text, at: Date.now() });
      draft = '';
      if (current?.identity === peer.identity) await openThread(peer);
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function poll() {
    if (!me) return;
    try {
      const incoming = await me.receive();
      if (!incoming.length) return;
      const known = new Set(contacts.map((c) => c.identity));
      for (const m of incoming) {
        const hello = m.text.startsWith(HELLO) ? m.text.slice(HELLO.length).trim().slice(0, 60) : null;
        if (!known.has(m.from)) {
          const fp = fingerprintOf(m.from);
          await contactsDb.put({ identity: m.from, name: hello || `Новий ${short(fp)}`, fingerprint: fp });
          known.add(m.from);
        }
        const text = hello !== null ? helloText(hello) : m.text;
        await messagesDb.add({ peer: m.from, out: false, text, at: Date.now() });
        if (current?.identity !== m.from) unread[m.from] = (unread[m.from] ?? 0) + 1;
      }
      await refreshContacts();
      // Нікого не відкрито (напр. перше привітання після запрошення) — відкрити розмову.
      const target = current ?? contacts.find((c) => c.identity === incoming[0].from) ?? null;
      if (target) await openThread(target);
    } catch (e) {
      fail(e);
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
      password = '';
      me = await Shepit.restore();
      myName = (await kv.get<string>('myName')) ?? '';
      await refreshContacts();
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
    location.reload();
  }

  onMount(() => {
    let pollTimer: ReturnType<typeof setInterval>;
    let keysTimer: ReturnType<typeof setInterval>;
    const onHash = async () => {
      const inv = readInviteFromUrl();
      if (inv) invite = inv;
    };
    window.addEventListener('hashchange', onHash);
    (async () => {
      try {
        await loadWasm();
        invite = readInviteFromUrl();
        me = await Shepit.restore();
        myName = (await kv.get<string>('myName')) ?? '';
        await refreshContacts();
        if (me) await me.ensureKeys();
      } catch (e) {
        fail(e);
      } finally {
        loading = false;
      }
      pollTimer = setInterval(poll, POLL_MS);
      keysTimer = setInterval(() => me?.ensureKeys().catch(fail), KEYS_CHECK_MS);
      poll();
    })();
    return () => {
      clearInterval(pollTimer);
      clearInterval(keysTimer);
      window.removeEventListener('hashchange', onHash);
    };
  });
</script>

{#if loading}
  <main class="center"><p class="muted">Завантаження…</p></main>
{:else if !me}
  <main class="center">
    <div class="card welcome">
      <img src="/favicon.svg" alt="" width="72" height="72" />
      <h1>Шепіт</h1>
      {#if invite}
        <p class="invite-banner"><b>{invite.name}</b> запрошує тебе поспілкуватися в Шепоті.</p>
      {/if}
      <p>Месенджер без номера телефону. Твоя «адреса» — криптографічний ключ, який створюється прямо тут, у браузері.</p>
      {#if panel !== 'restore'}
        <input placeholder="Як тебе називати (необов'язково)" bind:value={myName} maxlength="60" />
        <p class="muted small left">Ім'я бачать лише ті, кому ти надішлеш запрошення. Сервер його не знає.</p>
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
      <p class="warn small">Веб-версія зручна, але менш захищена за нативний застосунок: код завантажується з сервера. Для максимального захисту — дочекайся застосунку для Android.</p>
    </div>
  </main>
{:else}
  <div class="app">
    <aside>
      <section class="me">
        <input class="myname" placeholder="Твоє ім'я (для запрошень)" bind:value={myName} onchange={saveName} maxlength="60" />
        <div class="label">Мій відбиток</div>
        <div class="fp">{me.fingerprint}</div>
        <div class="row wrap">
          <button class="primary" onclick={() => (panel = 'invite')}>Запросити друга</button>
          <button onclick={() => (panel = 'backup')} title="Зашифрована резервна копія ключів">Резервна копія</button>
          <button class="ghost" title="Вийти і стерти ключі з цього браузера" onclick={logout}>⏻</button>
        </div>
      </section>

      <section class="contacts">
        <div class="row between">
          <div class="label">Контакти</div>
          <button class="ghost" onclick={() => (panel = 'add')}>+ За ключем</button>
        </div>
        <ul>
          {#each contacts as c (c.identity)}
            <li>
              <button class:active={current?.identity === c.identity} onclick={() => openThread(c)}>
                <span class="name">{c.name}</span>
                <span class="sub">{short(c.fingerprint)}…</span>
                {#if unread[c.identity]}<span class="badge">{unread[c.identity]}</span>{/if}
              </button>
            </li>
          {/each}
        </ul>
      </section>
      <p class="warn small">Веб-версія: ключі зберігаються в цьому браузері. Зроби резервну копію, інакше очищення даних сайту = втрата акаунта.</p>
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
      {#if current}
        <header>
          <button class="title" onclick={rename} title="Перейменувати">{current.name} ✎</button>
          <button class="ghost small" onclick={() => (showSafety = !showSafety)}>🔒 код безпеки</button>
        </header>
        {#if showSafety}
          <div class="safety">
            Порівняйте цей код з {current.name} наживо або по телефону. Якщо збігається — ніхто не стоїть посередині.
            <div class="fp">{current.fingerprint}</div>
          </div>
        {/if}
        <div class="thread" bind:this={threadEl}>
          {#each thread as m (m.id)}
            <div class="msg" class:out={m.out}>
              <div class="bubble">{m.text}</div>
              <div class="time">{new Date(m.at).toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' })}</div>
            </div>
          {:else}
            <p class="muted center-text">Повідомлень ще немає. Усе, що ви напишете, шифрується на пристрої — сервер бачить лише шифротекст.</p>
          {/each}
        </div>
        <form class="composer" onsubmit={(e) => { e.preventDefault(); send(); }}>
          <input placeholder="Повідомлення" bind:value={draft} disabled={busy} />
          <button class="primary" type="submit" disabled={busy || !draft.trim()}>Надіслати</button>
        </form>
      {:else if contacts.length === 0}
        <div class="onboarding">
          <h2>Як почати спілкуватися</h2>
          <ol>
            <li><b>Натисни «Запросити друга»</b> і надішли посилання будь-яким способом (Telegram, SMS, пошта) або покажи QR-код.</li>
            <li><b>Друг відкриває посилання</b> і створює акаунт — ти одразу з'являєшся в його контактах, а він надсилає тобі привітання.</li>
            <li><b>Ви в контактах одне в одного</b> — пишіть. Для впевненості звірте «код безпеки» наживо.</li>
          </ol>
          <button class="primary" onclick={() => (panel = 'invite')}>Запросити друга</button>
          <p class="muted small">Номер телефону, email чи пароль не потрібні. Посилання містить лише твій публічний ключ.</p>
        </div>
      {:else}
        <div class="center"><p class="muted">Обери контакт.</p></div>
      {/if}
      {#if notice}<div class="toast ok" role="status">{notice} <button class="ghost" onclick={() => (notice = '')}>✕</button></div>{/if}
      {#if error}<div class="toast" role="alert">{error} <button class="ghost" onclick={() => (error = '')}>✕</button></div>{/if}
    </main>
  </div>

  {#if panel}
    <div class="overlay" role="presentation" onclick={(e) => { if (e.target === e.currentTarget) panel = ''; }}>
      <div class="card modal">
        {#if panel === 'invite'}
          <h2>Запросити друга</h2>
          {#if !myName}<p class="warn small">Підказка: вкажи своє ім'я зліва — тоді друг побачить, від кого запрошення.</p>{/if}
          <div class="qr">{@html qr}</div>
          <p class="muted small">Друг сканує QR камерою телефона або відкриває посилання:</p>
          <div class="linkbox mono">{inviteUrl}</div>
          <div class="row wrap">
            <button class="primary" onclick={share}>{copied === 'link' ? 'Скопійовано ✓' : 'Поділитися посиланням'}</button>
            <button onclick={() => copy(inviteUrl, 'link')}>Копіювати</button>
            <button onclick={() => copy(me!.identity, 'key')}>{copied === 'key' ? 'Скопійовано ✓' : 'Лише ключ'}</button>
          </div>
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
