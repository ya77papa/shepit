<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { Shepit, fingerprintOf, isValidIdentity } from './lib/shepit';
  import { contacts as contactsDb, messages as messagesDb, wipe, type Contact, type Message } from './lib/db';

  const POLL_MS = 3000;

  let me: Shepit | null = $state(null);
  let loading = $state(true);
  let busy = $state(false);
  let error = $state('');

  let contacts: Contact[] = $state([]);
  let current: Contact | null = $state(null);
  let thread: Message[] = $state([]);
  let draft = $state('');
  let unread: Record<string, number> = $state({});

  let adding = $state(false);
  let newName = $state('');
  let newKey = $state('');
  let copied = $state(false);
  let showSafety = $state(false);
  let threadEl: HTMLElement | undefined = $state();

  const short = (fp: string) => fp.split(' ').slice(0, 2).join(' ');

  function fail(e: unknown) {
    error = e instanceof Error ? e.message : String(e);
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

  async function register() {
    busy = true;
    error = '';
    try {
      me = await Shepit.register();
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function addContact() {
    const key = newKey.trim().toLowerCase();
    if (!isValidIdentity(key)) return fail('Це не схоже на ключ Шепоту (66 hex-символів, починається з 05)');
    if (key === me?.identity) return fail('Це твій власний ключ');
    const c = { identity: key, name: newName.trim() || `Контакт ${short(fingerprintOf(key))}`, fingerprint: fingerprintOf(key) };
    await contactsDb.put(c);
    newName = newKey = '';
    adding = false;
    error = '';
    await refreshContacts();
    await openThread(c);
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
        if (!known.has(m.from)) {
          const fp = fingerprintOf(m.from);
          await contactsDb.put({ identity: m.from, name: `Новий ${short(fp)}`, fingerprint: fp });
          known.add(m.from);
        }
        await messagesDb.add({ peer: m.from, out: false, text: m.text, at: Date.now() });
        if (current?.identity !== m.from) unread[m.from] = (unread[m.from] ?? 0) + 1;
      }
      await refreshContacts();
      if (current) await openThread(current);
    } catch (e) {
      fail(e);
    }
  }

  async function copyKey() {
    if (!me) return;
    await navigator.clipboard.writeText(me.identity);
    copied = true;
    setTimeout(() => (copied = false), 1500);
  }

  async function rename() {
    if (!current) return;
    const name = prompt('Ім\'я контакту', current.name)?.trim();
    if (!name) return;
    current.name = name;
    await contactsDb.put($state.snapshot(current));
    await refreshContacts();
  }

  async function logout() {
    if (!confirm('Видалити акаунт з цього браузера? Ключі буде втрачено назавжди — відновити їх неможливо.')) return;
    await wipe();
    location.reload();
  }

  onMount(() => {
    let timer: ReturnType<typeof setInterval>;
    (async () => {
      try {
        me = await Shepit.restore();
        await refreshContacts();
      } catch (e) {
        fail(e);
      } finally {
        loading = false;
      }
      timer = setInterval(poll, POLL_MS);
      poll();
    })();
    return () => clearInterval(timer);
  });
</script>

{#if loading}
  <main class="center"><p class="muted">Завантаження…</p></main>
{:else if !me}
  <main class="center">
    <div class="card welcome">
      <img src="/favicon.svg" alt="" width="72" height="72" />
      <h1>Шепіт</h1>
      <p>Месенджер без номера телефону. Твоя «адреса» — це криптографічний ключ, який створюється прямо тут, у браузері.</p>
      <button class="primary" onclick={register} disabled={busy}>{busy ? 'Створюю ключі…' : 'Створити акаунт'}</button>
      {#if error}<p class="error">{error}</p>{/if}
      <p class="warn small">Веб-версія зручна, але менш захищена за нативний застосунок: код завантажується з сервера. Для високого ризику — чекай на застосунок для Android.</p>
    </div>
  </main>
{:else}
  <div class="app">
    <aside>
      <section class="me">
        <div class="label">Мій відбиток</div>
        <div class="fp">{me.fingerprint}</div>
        <div class="row">
          <button onclick={copyKey}>{copied ? 'Скопійовано ✓' : 'Скопіювати мій ключ'}</button>
          <button class="ghost" title="Вийти і стерти ключі" onclick={logout}>⏻</button>
        </div>
      </section>

      <section class="contacts">
        <div class="row between">
          <div class="label">Контакти</div>
          <button class="ghost" onclick={() => (adding = !adding)}>{adding ? 'Скасувати' : '+ Додати'}</button>
        </div>
        {#if adding}
          <form class="add" onsubmit={(e) => { e.preventDefault(); addContact(); }}>
            <input placeholder="Ім'я (як ти його називаєш)" bind:value={newName} />
            <input placeholder="Ключ співрозмовника (05…)" bind:value={newKey} spellcheck="false" autocomplete="off" />
            <button class="primary" type="submit">Додати</button>
          </form>
        {/if}
        <ul>
          {#each contacts as c (c.identity)}
            <li>
              <button class:active={current?.identity === c.identity} onclick={() => openThread(c)}>
                <span class="name">{c.name}</span>
                <span class="sub">{short(c.fingerprint)}…</span>
                {#if unread[c.identity]}<span class="badge">{unread[c.identity]}</span>{/if}
              </button>
            </li>
          {:else}
            <li class="muted small">Поки нікого. Обміняйтеся ключами з другом — будь-яким каналом.</li>
          {/each}
        </ul>
      </section>
      <p class="warn small">Веб-версія: ключі зберігаються в цьому браузері. Очищення даних сайту = втрата акаунта.</p>
    </aside>

    <main class="chat">
      {#if current}
        <header>
          <div>
            <button class="title" onclick={rename} title="Перейменувати">{current.name}</button>
            <button class="ghost small" onclick={() => (showSafety = !showSafety)}>🔒 код безпеки</button>
          </div>
        </header>
        {#if showSafety}
          <div class="safety">
            Порівняйте з {current.name} наживо або в іншому каналі. Якщо збігається — ніхто не стоїть посередині.
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
      {:else}
        <div class="center"><p class="muted">Обери контакт або додай новий.</p></div>
      {/if}
      {#if error}<div class="toast" role="alert">{error} <button class="ghost" onclick={() => (error = '')}>✕</button></div>{/if}
    </main>
  </div>
{/if}
