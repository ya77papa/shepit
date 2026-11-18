<script lang="ts">
  // Публічна сторінка сервера: веб-версії чату більше немає (ADR 0011).
  // Тут — завантаження Android-застосунку, запрошення / посилання на канал і перехід
  // для тих, у кого акаунт лишився в цьому браузері.
  import { onMount } from 'svelte';

  const APK = 'https://github.com/ya77papa/shepit/releases/latest/download/shepit.apk';
  const RELEASES = 'https://github.com/ya77papa/shepit/releases/latest';
  const REPO = 'https://github.com/ya77papa/shepit';
  /** Відбиток сертифіката підпису APK — збігається з assetlinks.json. */
  const CERT = '7E:E1:40:BE:36:A1:96:FC:83:B6:80:D1:28:EE:B1:73:23:3F:52:B1:8A:9E:32:B0:48:BB:A1:B5:9A:7A:73:9A';

  const link = location.href;
  const p = new URLSearchParams(location.hash.slice(1));
  const kind: 'invite' | 'contact' | 'channel' | null = p.has('c') ? 'channel' : p.has('add') ? 'contact' : p.has('i') ? 'invite' : null;
  const code = (p.get('i') ?? '').toUpperCase();
  const isAndroid = /android/i.test(navigator.userAgent);

  let copied = $state('');
  async function copy(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text);
      copied = what;
      setTimeout(() => (copied = ''), 2000);
    } catch {
      prompt('Скопіюй:', text);
    }
  }

  // ---------- перехід зі старої веб-версії ----------
  let legacy = $state(false);
  let password = $state('');
  let password2 = $state('');
  let busy = $state(false);
  let error = $state('');
  let saved = $state(false);

  onMount(async () => {
    // Залишки старої веб-версії: service worker і кеші більше не потрібні.
    navigator.serviceWorker?.getRegistrations().then((rs) => rs.forEach((r) => r.unregister())).catch(() => {});
    caches?.keys().then((ks) => ks.forEach((k) => caches.delete(k))).catch(() => {});
    try {
      // Не створювати порожню базу в чужих браузерах: спершу перевірити, чи вона є.
      const dbs = await indexedDB.databases?.();
      if (dbs && !dbs.some((d) => d.name === 'shepit')) return;
      const { kv } = await import('../lib/db');
      legacy = !!(await kv.get('account'));
    } catch {
      /* немає бази — нічого переносити */
    }
  });

  async function saveBackup() {
    error = '';
    if (password !== password2) { error = 'Паролі не збігаються'; return; }
    busy = true;
    try {
      const { exportBackup } = await import('../lib/backup');
      const blob = await exportBackup(password);
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `shepit-${new Date().toISOString().slice(0, 10)}.shepit-backup`;
      a.click();
      URL.revokeObjectURL(a.href);
      password = password2 = '';
      saved = true;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = false;
    }
  }

  async function wipe() {
    if (!confirm('Видалити акаунт Шепоту з цього браузера? Без резервної копії відновити його буде неможливо.')) return;
    indexedDB.deleteDatabase('shepit');
    legacy = false;
  }
</script>

<main class="landing">
  <header class="hero">
    <img src="/icon-192.png" alt="" width="72" height="72" />
    <h1>Шепіт</h1>
    <p class="muted">Месенджер із наскрізним шифруванням. Сервер бачить лише шифротекст.</p>
  </header>

  {#if kind}
    <section class="card accent">
      <h2>
        {kind === 'channel' ? 'Тобі надіслали посилання на канал' : kind === 'contact' ? 'Тебе запрошують поспілкуватися в Шепоті' : 'Тебе запросили до Шепоту'}
      </h2>
      <ol class="steps">
        <li>Завантаж і встанови застосунок (нижче).</li>
        <li>
          Повернись сюди й натисни <b>«Відкрити в Шепоті»</b> — {kind === 'channel'
            ? 'канал відкриється в застосунку.'
            : kind === 'contact'
              ? 'застосунок додасть контакт.'
              : 'посилання відкриється в застосунку.'}
        </li>
      </ol>
      <div class="row wrap">
        <a class="button primary" href={link}>Відкрити в Шепоті</a>
        <button onclick={() => copy(link, 'link')}>{copied === 'link' ? 'Скопійовано' : 'Скопіювати посилання'}</button>
      </div>
      {#if code}
        <p class="small">
          Код запрошення: <code class="code">{code}</code>
          <button class="ghost small" onclick={() => copy(code, 'code')}>{copied === 'code' ? 'Скопійовано' : 'Копіювати'}</button>
          <br /><span class="muted">Якщо посилання не відкривається в застосунку — введи цей код під час реєстрації.</span>
        </p>
      {/if}
    </section>
  {/if}

  <section class="card">
    <h2>Завантаж Шепіт для Android</h2>
    <a class="button primary big" href={APK}>Завантажити APK</a>
    <p class="small muted">Остання версія з <a href={RELEASES} target="_blank" rel="noopener">GitHub Releases</a>. Далі застосунок оновлюється сам.</p>
    {#if !isAndroid}
      <p class="small">Відкрий цю сторінку на телефоні з Android. Для iPhone і комп'ютера версії поки немає.</p>
    {/if}
    <h3>Як встановити</h3>
    <ol class="steps">
      <li>Натисни «Завантажити APK» і відкрий завантажений файл.</li>
      <li>Android попросить дозволу на встановлення: «Налаштування» → увімкни <b>«Дозволити з цього джерела»</b> → повернись назад.</li>
      <li>Натисни «Встановити». Якщо Play Захист попереджає про невідомого розробника — «Деталі» → «Усе одно встановити».</li>
      <li>Відкрий Шепіт і створи акаунт (або віднови з резервної копії).</li>
    </ol>
    <details class="small">
      <summary>Перевірити, що застосунок справжній</summary>
      <p>Відбиток SHA-256 сертифіката, яким підписано APK:</p>
      <code class="code wrap-any">{CERT}</code>
      <p class="muted">Код відкритий: <a href={REPO} target="_blank" rel="noopener">{REPO.replace('https://', '')}</a>. Кожна збірка — з GitHub Actions.</p>
    </details>
  </section>

  {#if legacy}
    <section class="card legacy">
      <h2>У цьому браузері є твій акаунт</h2>
      <p>Веб-версію Шепоту закрито — вона менш захищена, ніж застосунок. Щоб не втратити акаунт, контакти й історію:</p>
      <ol class="steps">
        <li>Збережи зашифровану резервну копію (нижче).</li>
        <li>Встанови Шепіт на Android → «Відновити з резервної копії» → обери цей файл і введи пароль.</li>
      </ol>
      {#if saved}
        <p class="ok">Резервну копію збережено. Зберігай файл і пароль окремо.</p>
        <button class="ghost small danger-text" onclick={wipe}>Видалити акаунт з цього браузера</button>
      {:else}
        <label class="field">
          <span class="label">Пароль для копії (щонайменше 10 символів)</span>
          <input type="password" bind:value={password} autocomplete="new-password" />
        </label>
        <label class="field">
          <span class="label">Повтори пароль</span>
          <input type="password" bind:value={password2} autocomplete="new-password" />
        </label>
        {#if error}<p class="error">{error}</p>{/if}
        <button class="primary" disabled={busy || password.length < 10} onclick={saveBackup}>Зберегти резервну копію</button>
      {/if}
    </section>
  {/if}
</main>

<style>
  .landing { max-width: 560px; margin: 0 auto; padding: 24px 16px 48px; display: flex; flex-direction: column; gap: 16px; }
  .hero { text-align: center; display: flex; flex-direction: column; align-items: center; gap: 6px; }
  .hero h1 { margin: 6px 0 0; font-size: 2rem; }
  .hero img { border-radius: 18px; }
  .card { background: var(--panel); border: 1px solid var(--line); border-radius: 16px; padding: 18px; display: flex; flex-direction: column; gap: 10px; }
  .card.accent { border-color: var(--accent); }
  .card.legacy { border-color: #b8862b; }
  .card h2 { margin: 0; font-size: 1.15rem; }
  .card h3 { margin: 8px 0 0; font-size: 1rem; }
  .steps { margin: 0; padding-left: 1.3em; display: flex; flex-direction: column; gap: 6px; }
  .button {
    display: inline-flex; align-items: center; justify-content: center; text-decoration: none;
    background: var(--line); color: var(--text); border-radius: 8px; padding: 8px 12px;
  }
  .button.primary { background: var(--accent); color: var(--accent-text); font-weight: 600; }
  .field { display: flex; flex-direction: column; gap: 4px; }
  a { color: var(--accent); }
  .big { padding: 14px 20px; font-size: 1.05rem; }
  .code { font-family: ui-monospace, monospace; background: var(--bg); padding: 2px 6px; border-radius: 6px; }
  .wrap-any { display: block; word-break: break-all; padding: 8px; }
  .wrap { flex-wrap: wrap; gap: 8px; }
  .ok { color: #3fb950; }
  .danger-text { color: var(--danger); align-self: flex-start; }
</style>
