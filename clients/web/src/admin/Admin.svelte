<script lang="ts">
  import { onMount } from 'svelte';

  type Mode = 'open' | 'invite' | 'closed';
  type Invite = {
    code: string; created: number; creator: string | null; note: string;
    max_uses: number; uses: number; expires: number | null; revoked: boolean; active: boolean;
  };
  type Overview = {
    settings: { registration: Mode; user_invite_limit: number };
    stats: {
      users: number; new_7d: number; active_1d: number; active_7d: number; active_30d: number;
      queued: number; queued_bytes: number; mailboxes_waiting: number; push_subs: number; invites_active: number;
    };
    server: {
      version: string; commit: string; started: number; now: number; turn: string[]; web_push: boolean;
      live_connections: number; db_bytes: number; disk_free: number | null; disk_total: number | null;
    };
  };

  let state: 'loading' | 'login' | 'ready' = $state('loading');
  let password = $state('');
  let error = $state('');
  let notice = $state('');
  let busy = $state(false);
  let notConfigured = $state(false);
  let ov: Overview | null = $state(null);
  let invites: Invite[] = $state([]);
  let mode: Mode = $state('open');
  let limit = $state(5);
  let showUsed = $state(false);
  // форма нового запрошення
  let note = $state('');
  let uses = $state(1);
  let days = $state<number | ''>(7);
  let created: Invite | null = $state(null);
  type User = { identity: string; fingerprint: string; created: number; last_seen: number; invite: string | null; invited_by: string | null };
  type Blocked = { identity: string; fingerprint: string; at: number };
  let users: User[] = $state([]);
  let blocked: Blocked[] = $state([]);
  let query = $state('');
  let deleting: User | null = $state(null);
  let block = $state(true);
  let copied = $state('');

  const H = { 'x-shepit-admin': '1' };

  async function api(method: string, path: string, body?: unknown): Promise<Response> {
    const r = await fetch(`/v0/admin/${path}`, {
      method,
      cache: 'no-store',
      headers: body === undefined ? H : { ...H, 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (r.status === 401) {
      state = 'login';
      throw new Error('Сесію завершено — увійди знову');
    }
    if (!r.ok) throw new Error(`${method} ${path}: ${r.status}`);
    return r;
  }

  async function load() {
    const [o, i, u] = await Promise.all([api('GET', 'overview'), api('GET', 'invites'), api('GET', 'users')]);
    ov = await o.json();
    invites = await i.json();
    ({ users, blocked } = await u.json());
    if (state !== 'ready') {
      mode = ov!.settings.registration;
      limit = ov!.settings.user_invite_limit;
    }
    state = 'ready';
  }

  async function login(e: Event) {
    e.preventDefault();
    busy = true;
    error = '';
    try {
      const r = await fetch('/v0/admin/login', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ password }),
      });
      password = '';
      if (r.ok) return await load();
      const why = await r.text();
      if (why === 'not-configured') notConfigured = true;
      else if (why.startsWith('locked:')) error = `Забагато невдалих спроб. Спробуй через ${Math.ceil(+why.slice(7) / 60)} хв.`;
      else error = 'Неправильний пароль';
    } catch (e) {
      error = String(e);
    } finally {
      busy = false;
    }
  }

  async function logout() {
    await fetch('/v0/admin/logout', { method: 'POST', headers: H }).catch(() => {});
    ov = null;
    state = 'login';
  }

  async function act(f: () => Promise<unknown>, ok?: string) {
    busy = true;
    error = '';
    notice = '';
    try {
      await f();
      if (ok) notice = ok;
      await load();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = false;
    }
  }

  const saveSettings = () =>
    act(() => api('PUT', 'settings', { registration: mode, user_invite_limit: Math.max(0, Math.floor(+limit || 0)) }), 'Збережено ✓');

  const createInvite = () =>
    act(async () => {
      const r = await api('POST', 'invites', { note: note.trim(), max_uses: Math.max(1, Math.floor(+uses || 1)), days: days === '' ? null : Math.max(1, Math.floor(+days)) });
      created = { ...(await r.json()), active: true };
      note = '';
    });

  const revoke = (code: string) => {
    if (!confirm(`Відкликати запрошення ${code}? Ним більше не можна буде зареєструватись.`)) return;
    act(() => api('DELETE', `invites/${encodeURIComponent(code)}`), 'Запрошення відкликано');
  };

  const deleteUser = (u: User) =>
    act(async () => {
      await api('DELETE', `users/${u.identity}?block=${block ? 1 : 0}`);
      deleting = null;
    }, block ? 'Акаунт видалено і заблоковано' : 'Акаунт видалено');

  const unblock = (b: Blocked) => {
    if (!confirm(`Розблокувати ${short(b.fingerprint)}…? Цей ключ знову зможе зареєструватися (за правилами поточного режиму).`)) return;
    act(() => api('DELETE', `blocked/${b.identity}`), 'Розблоковано');
  };

  const digits = (s: string) => s.replace(/\D/g, '');
  const short = (fp: string) => fp.split(' ').slice(0, 2).join(' ');
  const day = (ms: number) => {
    if (!ms) return '—';
    const today = Math.floor((ov?.server.now ?? Date.now()) / 86400000) * 86400000;
    if (ms >= today) return 'сьогодні';
    if (ms >= today - 86400000) return 'вчора';
    return new Date(ms).toLocaleDateString('uk');
  };
  const shownUsers = $derived(query.trim() ? users.filter((u) => digits(u.fingerprint).includes(digits(query)) && digits(query).length > 0) : users);

  const linkOf = (code: string) => `${location.origin}/#i=${code}`;

  async function copy(text: string, what: string) {
    await navigator.clipboard.writeText(text);
    copied = what;
    setTimeout(() => (copied = ''), 1500);
  }

  // ---------- формат ----------
  const bytes = (n: number | null) => {
    if (n == null) return '—';
    const u = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
    let i = 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return `${n.toFixed(i ? 1 : 0)} ${u[i]}`;
  };
  const date = (ms: number) => new Date(ms).toLocaleString('uk', { day: '2-digit', month: '2-digit', year: '2-digit', hour: '2-digit', minute: '2-digit' });
  const uptime = (ms: number) => {
    const m = Math.floor(ms / 60000), d = Math.floor(m / 1440), h = Math.floor((m % 1440) / 60);
    return d ? `${d} д ${h} год` : h ? `${h} год ${m % 60} хв` : `${m} хв`;
  };
  const statusOf = (i: Invite) =>
    i.revoked ? 'відкликано' : i.uses >= i.max_uses ? 'використано' : i.expires && i.expires <= (ov?.server.now ?? Date.now()) ? 'прострочено' : 'чинне';

  const MODES: { v: Mode; title: string; text: string }[] = [
    { v: 'open', title: 'Вільна', text: 'Будь-хто може створити акаунт.' },
    { v: 'invite', title: 'За запрошенням', text: 'Новий акаунт — лише з кодом. Коди створюєш ти, а кожен користувач має кілька своїх (вбудовуються в його посилання-запрошення).' },
    { v: 'closed', title: 'Закрита', text: 'Нових акаунтів не буде. Наявні користувачі працюють як завжди.' },
  ];

  const shown = $derived(showUsed ? invites : invites.filter((i) => i.active));

  onMount(() => {
    load().catch(() => (state = 'login'));
    const t = setInterval(() => { if (state === 'ready' && !busy) load().catch(() => {}); }, 30_000);
    return () => clearInterval(t);
  });
</script>

{#if state === 'loading'}
  <main class="center"><p class="muted">Завантаження…</p></main>
{:else if state === 'login'}
  <main class="center">
    <form class="card login" onsubmit={login}>
      <img src="/favicon.svg" alt="" width="56" height="56" />
      <h1>Адміністрування</h1>
      {#if notConfigured}
        <p class="warn">Пароль адміністратора ще не задано. На сервері виконай:</p>
        <pre class="mono">sudo -u shepit bash -c 'cd /home/shepit/shepit-server && docker compose exec shepit shepit-server admin-password'</pre>
      {:else}
        <input type="password" placeholder="Пароль адміністратора" bind:value={password} autocomplete="current-password" required />
        <button class="primary" disabled={busy || !password}>{busy ? 'Перевіряю…' : 'Увійти'}</button>
      {/if}
      {#if error}<p class="error">{error}</p>{/if}
      <a class="small muted" href="/">← до Шепоту</a>
    </form>
  </main>
{:else if ov}
  <div class="page">
    <header class="row between">
      <h1>Шепіт · адміністрування</h1>
      <button class="ghost" onclick={logout}>Вийти</button>
    </header>
    {#if error}<p class="error">{error}</p>{/if}
    {#if notice}<p class="ok">{notice}</p>{/if}

    <section class="card">
      <h2>Реєстрація</h2>
      <div class="modes">
        {#each MODES as m (m.v)}
          <label class="mode" class:on={mode === m.v}>
            <input type="radio" name="mode" value={m.v} bind:group={mode} />
            <b>{m.title}</b>
            <span class="small muted">{m.text}</span>
          </label>
        {/each}
      </div>
      <label class="inline">
        <span>Запрошень на одного користувача (усього):</span>
        <input type="number" min="0" max="1000" bind:value={limit} />
      </label>
      <p class="small muted">0 — користувачі не можуть запрошувати, лише ти. Код користувача одноразовий і діє 30 днів. Сервер бачить, хто кого запросив.</p>
      <button class="primary" onclick={saveSettings} disabled={busy || (mode === ov.settings.registration && +limit === ov.settings.user_invite_limit)}>Зберегти</button>
    </section>

    <section class="card">
      <h2>Статистика</h2>
      <div class="tiles">
        <div class="tile"><span class="n">{ov.stats.users}</span><span class="small muted">користувачів</span></div>
        <div class="tile"><span class="n">{ov.stats.new_7d}</span><span class="small muted">нових за 7 днів</span></div>
        <div class="tile"><span class="n">{ov.stats.active_1d}</span><span class="small muted">активні сьогодні</span></div>
        <div class="tile"><span class="n">{ov.stats.active_7d}</span><span class="small muted">за 7 днів</span></div>
        <div class="tile"><span class="n">{ov.stats.active_30d}</span><span class="small muted">за 30 днів</span></div>
        <div class="tile"><span class="n">{ov.server.live_connections}</span><span class="small muted">на зв'язку зараз</span></div>
        <div class="tile"><span class="n">{ov.stats.queued}</span><span class="small muted">недоставлених ({bytes(ov.stats.queued_bytes)}) у {ov.stats.mailboxes_waiting} скриньках</span></div>
        <div class="tile"><span class="n">{ov.stats.push_subs}</span><span class="small muted">підписок на сповіщення</span></div>
        <div class="tile"><span class="n">{ov.stats.invites_active}</span><span class="small muted">чинних запрошень</span></div>
      </div>
      <p class="small muted">Активність рахується з точністю до дня. Вміст повідомлень і те, хто кому пише, сервер не знає.</p>
    </section>

    <section class="card">
      <h2>Користувачі ({users.length})</h2>
      <input placeholder="Пошук за «Мій ID»: попроси людину продиктувати кілька перших цифр" bind:value={query} inputmode="numeric" />
      <p class="small muted">Сервер не знає імен — лише ключі. «Мій ID» кожен бачить у своїх налаштуваннях.</p>
      {#if deleting}
        <div class="created danger-box">
          <div>Видалити акаунт <b class="mono">{short(deleting.fingerprint)}…</b>?</div>
          <div class="small muted">Зі сервера буде стерто його ключі, недоставлену пошту, підписки на сповіщення; його невикористані запрошення відкликано. Листування на пристроях людей залишиться.</div>
          <label class="row small"><input type="checkbox" class="cb" bind:checked={block} /> заборонити повторну реєстрацію цим ключем</label>
          <div class="row wrap">
            <button class="danger-btn" onclick={() => deleteUser(deleting!)} disabled={busy}>Видалити назавжди</button>
            <button class="ghost" onclick={() => (deleting = null)}>Скасувати</button>
          </div>
        </div>
      {/if}
      {#if shownUsers.length === 0}
        <p class="muted small">{query ? 'Нікого не знайдено.' : 'Користувачів ще немає.'}</p>
      {:else}
        <div class="tablewrap">
          <table>
            <thead><tr><th>Мій ID</th><th>Зареєстровано</th><th>Активність</th><th>Запрошення</th><th></th></tr></thead>
            <tbody>
              {#each shownUsers.slice(0, 200) as u (u.identity)}
                <tr>
                  <td class="mono fpcell">{u.fingerprint}</td>
                  <td>{u.created ? date(u.created) : 'давно'}</td>
                  <td>{day(u.last_seen)}</td>
                  <td class="small">{u.invite ? (u.invited_by ? `від користувача ${short(users.find((x) => x.identity === u.invited_by)?.fingerprint ?? '')}…` : `код адміна`) : '—'}</td>
                  <td><button class="small danger" onclick={() => { deleting = u; block = true; }}>Видалити</button></td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
        {#if shownUsers.length > 200}<p class="small muted">Показано 200 з {shownUsers.length} — уточни пошук.</p>{/if}
      {/if}
      {#if blocked.length}
        <h3>Заблоковані ({blocked.length})</h3>
        <table>
          <tbody>
            {#each blocked as b (b.identity)}
              <tr><td class="mono fpcell">{b.fingerprint}</td><td>{date(b.at)}</td><td><button class="small" onclick={() => unblock(b)}>Розблокувати</button></td></tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </section>

    <section class="card">
      <h2>Запрошення</h2>
      <div class="newinv">
        <input placeholder="Примітка, напр. «для мами»" bind:value={note} maxlength="200" />
        <label class="inline"><span>Скільки разів:</span><input type="number" min="1" max="10000" bind:value={uses} /></label>
        <label class="inline"><span>Днів дії:</span><input type="number" min="1" placeholder="∞" bind:value={days} /></label>
        <button class="primary" onclick={createInvite} disabled={busy}>Створити</button>
      </div>
      {#if created}
        <div class="created">
          <div>Нове запрошення: <b class="mono">{created.code}</b></div>
          <div class="mono small link">{linkOf(created.code)}</div>
          <div class="row wrap">
            <button onclick={() => copy(linkOf(created!.code), 'link')}>{copied === 'link' ? 'Скопійовано ✓' : 'Копіювати посилання'}</button>
            <button onclick={() => copy(created!.code, 'code')}>{copied === 'code' ? 'Скопійовано ✓' : 'Лише код'}</button>
            <button class="ghost" onclick={() => (created = null)}>✕</button>
          </div>
        </div>
      {/if}
      <label class="row small muted"><input type="checkbox" class="cb" bind:checked={showUsed} /> показати використані й відкликані</label>
      {#if shown.length === 0}
        <p class="muted small">Немає {showUsed ? '' : 'чинних '}запрошень.</p>
      {:else}
        <div class="tablewrap">
          <table>
            <thead><tr><th>Код</th><th>Хто створив</th><th>Використано</th><th>Діє до</th><th>Стан</th><th></th></tr></thead>
            <tbody>
              {#each shown as i (i.code)}
                <tr class:dim={!i.active}>
                  <td><span class="mono">{i.code}</span>{#if i.note}<div class="small muted">{i.note}</div>{/if}</td>
                  <td>{i.creator ? `користувач ${i.creator.slice(2, 10)}…` : 'адмін'}</td>
                  <td>{i.uses} / {i.max_uses}</td>
                  <td>{i.expires ? date(i.expires) : 'безстроково'}</td>
                  <td>{statusOf(i)}</td>
                  <td class="row">
                    {#if i.active}
                      <button class="small" onclick={() => copy(linkOf(i.code), i.code)}>{copied === i.code ? '✓' : 'Посилання'}</button>
                      <button class="small danger" onclick={() => revoke(i.code)}>Відкликати</button>
                    {/if}
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </section>

    <section class="card">
      <h2>Стан сервера</h2>
      <dl>
        <dt>Версія</dt>
        <dd>
          {ov.server.version} ·
          {#if ov.server.commit !== 'dev'}<a href={`https://github.com/ya77papa/shepit/commit/${ov.server.commit}`} target="_blank" rel="noopener" class="mono">{ov.server.commit.slice(0, 7)}</a>{:else}dev{/if}
        </dd>
        <dt>Працює</dt><dd>{uptime(ov.server.now - ov.server.started)} (з {date(ov.server.started)})</dd>
        <dt>Ретранслятор дзвінків</dt><dd>{ov.server.turn.length ? ov.server.turn.join(', ') : '⚠️ не налаштовано'}</dd>
        <dt>Web Push</dt><dd>{ov.server.web_push ? 'увімкнено' : 'вимкнено'}</dd>
        <dt>База даних</dt><dd>{bytes(ov.server.db_bytes)}</dd>
        <dt>Диск</dt>
        <dd>
          {#if ov.server.disk_free != null && ov.server.disk_total}
            вільно {bytes(ov.server.disk_free)} з {bytes(ov.server.disk_total)}
            {#if ov.server.disk_free / ov.server.disk_total < 0.1}<span class="warn"> — місця мало!</span>{/if}
          {:else}—{/if}
        </dd>
      </dl>
    </section>
    <p class="small muted center-text-s">Сесія завершиться після 30 хв без дій. <a href="/" class="muted">До Шепоту →</a></p>
  </div>
{/if}

<style>
  .login { width: min(380px, 100%); display: grid; gap: 12px; text-align: center; justify-items: center; }
  .login h1 { margin: 0; }
  .login input, .login button { width: 100%; }
  pre { white-space: pre-wrap; word-break: break-all; text-align: left; background: var(--bg); padding: 10px; border-radius: 8px; font-size: .8rem; }
  .page { max-width: 960px; margin: 0 auto; padding: 16px; display: grid; gap: 16px; }
  .page h1 { font-size: 1.3rem; margin: 0; }
  .page h2 { font-size: 1.05rem; margin: 0 0 12px; }
  .card { padding: 20px; }
  .ok { color: #6fd39a; }
  .modes { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; margin-bottom: 14px; }
  .mode { display: grid; gap: 4px; border: 1px solid var(--line); border-radius: 12px; padding: 12px; cursor: pointer; }
  .mode.on { border-color: var(--accent); background: rgba(245, 197, 66, .06); }
  .mode input { display: none; }
  .inline { display: flex; align-items: center; gap: 8px; margin: 8px 0; flex-wrap: wrap; }
  .inline input { width: 110px; }
  .tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; }
  .tile { background: var(--bg); border-radius: 12px; padding: 12px; display: grid; gap: 4px; }
  .tile .n { font-size: 1.6rem; font-weight: 600; }
  .newinv { display: grid; grid-template-columns: 1fr auto auto auto; gap: 8px; align-items: center; margin-bottom: 12px; }
  .newinv .inline { margin: 0; }
  .newinv .inline input { width: 90px; }
  @media (max-width: 700px) { .newinv { grid-template-columns: 1fr; } }
  .created { border: 1px solid var(--accent); border-radius: 12px; padding: 12px; display: grid; gap: 8px; margin-bottom: 12px; }
  .link { word-break: break-all; color: var(--muted); }
  .cb { width: auto; }
  .tablewrap { overflow-x: auto; }
  table { width: 100%; border-collapse: collapse; font-size: .9rem; }
  th, td { text-align: left; padding: 8px 6px; border-top: 1px solid var(--line); vertical-align: top; }
  th { color: var(--muted); font-weight: 500; font-size: .8rem; }
  tr.dim { opacity: .5; }
  button.danger { color: var(--danger); }
  .danger-box { border-color: var(--danger); margin: 12px 0; }
  .danger-btn { background: var(--danger); color: #2a0b0b; font-weight: 600; }
  .fpcell { font-size: .78rem; word-spacing: .15em; max-width: 260px; }
  h3 { font-size: .95rem; margin: 16px 0 6px; color: var(--muted); }
  section > input { margin-bottom: 6px; }
  button.small { padding: 4px 8px; }
  dl { display: grid; grid-template-columns: max-content 1fr; gap: 8px 16px; margin: 0; }
  dt { color: var(--muted); }
  dd { margin: 0; word-break: break-word; }
  a { color: var(--accent); }
</style>
