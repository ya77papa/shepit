<script lang="ts">
  import { onDestroy, tick } from 'svelte';
  import { renderSVG } from 'uqr';
  import Avatar from './Avatar.svelte';
  import { makeAvatar } from './lib/avatar';
  import { channelLink, deleteChannel, deletePost, fetchPosts, publish, putMeta, type Post } from './lib/channels';
  import type { Channel } from './lib/db';

  let {
    channel, subscribed, canSubscribe = true, onSubscribe, onUnsubscribe, onChange, onDeleted, onError,
  }: {
    channel: Channel;
    subscribed: boolean;
    canSubscribe?: boolean;
    onSubscribe?: () => void;
    onUnsubscribe?: () => void;
    /** Опис змінено або прочитано нові дописи — зберегти запис. */
    onChange?: (c: Channel) => void;
    onDeleted?: () => void;
    onError: (msg: string) => void;
  } = $props();

  const mine = $derived(!!channel.priv);
  let posts: Post[] = $state([]);
  let loading = $state(true);
  let more = $state(false);
  let draft = $state('');
  let busy = $state(false);
  let info = $state(false);
  let editing = $state(false);
  let editName = $state('');
  let editAbout = $state('');
  let selected: number | null = $state(null);
  let feed: HTMLElement | undefined = $state();
  let copied = $state(false);
  let timer: ReturnType<typeof setInterval> | null = null;
  let loadedFor = '';

  const link = $derived(channelLink(channel));
  const qr = $derived(info ? renderSVG(link, { ecc: 'M', border: 2 }) : '');
  const fail = (e: unknown) => onError(e instanceof Error ? e.message : String(e));

  async function scrollDown() {
    await tick();
    feed?.scrollTo({ top: feed.scrollHeight });
  }

  async function load() {
    loading = true;
    posts = [];
    try {
      posts = await fetchPosts(channel, { limit: 50 });
      more = posts.length === 50;
      markRead();
      await scrollDown();
    } catch (e) {
      fail(e);
    } finally {
      loading = false;
    }
  }

  function markRead() {
    const last = posts.at(-1)?.seq ?? 0;
    if (subscribed && last > channel.readSeq) onChange?.({ ...$state.snapshot(channel), readSeq: last, lastSeq: Math.max(channel.lastSeq, last) });
  }

  async function refresh() {
    try {
      const after = posts.at(-1)?.seq ?? 0;
      const fresh = await fetchPosts(channel, { after, limit: 100 });
      if (fresh.length) {
        posts = [...posts, ...fresh];
        markRead();
        await scrollDown();
      }
    } catch { /* мережа — наступного разу */ }
  }

  async function older() {
    const first = posts[0]?.seq;
    if (!first) return;
    const prev = await fetchPosts(channel, { before: first, limit: 50 }).catch((e) => (fail(e), []));
    more = prev.length === 50;
    posts = [...prev, ...posts];
  }

  $effect(() => {
    if (channel.id !== loadedFor) {
      loadedFor = channel.id;
      info = editing = false;
      selected = null;
      load();
    }
  });
  timer = setInterval(refresh, 10_000);
  onDestroy(() => timer && clearInterval(timer));

  async function send() {
    const text = draft.trim();
    if (!text) return;
    busy = true;
    try {
      await publish(channel, text);
      draft = '';
      await refresh();
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function removePost(seq: number) {
    if (!confirm('Видалити цей допис для всіх читачів?')) return;
    try {
      await deletePost(channel, seq);
      posts = posts.filter((p) => p.seq !== seq);
      selected = null;
    } catch (e) {
      fail(e);
    }
  }

  function startEdit() {
    editName = channel.name;
    editAbout = channel.about;
    editing = true;
  }

  async function saveMeta(avatar = channel.avatar) {
    busy = true;
    try {
      const meta = { name: editName.trim().slice(0, 80) || channel.name, about: editAbout.trim().slice(0, 500), avatar };
      await putMeta(channel, meta);
      onChange?.({ ...$state.snapshot(channel), name: meta.name, about: meta.about, avatar });
      editing = false;
    } catch (e) {
      fail(e);
    } finally {
      busy = false;
    }
  }

  async function pickAvatar(e: Event) {
    const f = (e.currentTarget as HTMLInputElement).files?.[0];
    if (!f) return;
    try {
      editName ||= channel.name;
      editAbout ||= channel.about;
      await saveMeta(await makeAvatar(f));
    } catch (err) {
      fail(err);
    }
  }

  async function removeChannel() {
    if (!confirm(`Видалити канал «${channel.name}» з сервера? Усі дописи зникнуть для всіх читачів.`)) return;
    try {
      await deleteChannel(channel);
      onDeleted?.();
    } catch (e) {
      fail(e);
    }
  }

  async function copyLink() {
    await navigator.clipboard.writeText(link);
    copied = true;
    setTimeout(() => (copied = false), 1500);
  }

  const time = (ms: number) => new Date(ms).toLocaleString('uk', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
</script>

<header>
  <Avatar name={channel.name} src={channel.avatar} size={36} />
  <button class="title" onclick={() => (info = !info)} title="Про канал">{channel.name}</button>
  <span class="small muted">{channel.public ? '📢 публічний' : '🔒 зашифрований'}</span>
  <span class="spacer"></span>
  {#if !subscribed && canSubscribe}
    <button class="primary" onclick={() => onSubscribe?.()}>Підписатися</button>
  {/if}
  <button class="ghost" title="Про канал" onclick={() => (info = !info)}>ⓘ</button>
</header>

{#if info}
  <div class="safety channel-info">
    {#if editing}
      <input placeholder="Назва" bind:value={editName} maxlength="80" />
      <textarea placeholder="Опис" bind:value={editAbout} maxlength="500" rows="3"></textarea>
      <div class="row wrap">
        <button class="primary" onclick={() => saveMeta()} disabled={busy}>Зберегти</button>
        <button class="ghost" onclick={() => (editing = false)}>Скасувати</button>
      </div>
    {:else}
      {#if channel.about}<p class="about">{channel.about}</p>{/if}
      <p class="small muted">
        {#if channel.public}
          Публічний канал: дописи відкриті, сервер їх бачить, канал знаходиться пошуком.
        {:else}
          Зашифрований канал: читати може лише той, хто має посилання — ключ у ньому, сервер дописів не бачить.
        {/if}
      </p>
      <div class="qr">{@html qr}</div>
      <div class="linkbox mono small">{link}</div>
      <div class="row wrap">
        <button onclick={copyLink}>{copied ? 'Скопійовано ✓' : 'Копіювати посилання'}</button>
        {#if mine}
          <button onclick={startEdit}>✎ Назва й опис</button>
          <label class="button-like">🖼 Фото<input type="file" accept="image/*" hidden onchange={pickAvatar} /></label>
          <button class="danger" onclick={removeChannel}>Видалити канал</button>
        {:else if subscribed}
          <button class="ghost" onclick={() => onUnsubscribe?.()}>Відписатися</button>
        {/if}
      </div>
      {#if mine}<p class="small warn">Ти автор: ключ каналу зберігається лише на цьому пристрої і в резервній копії. Без нього писати в канал неможливо.</p>{/if}
    {/if}
  </div>
{/if}

<div class="thread" bind:this={feed}>
  {#if more}<button class="ghost small" onclick={older}>Старіші дописи</button>{/if}
  {#each posts as p (p.seq)}
    <div class="msg post" class:selected={selected === p.seq}>
      <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
      <div class="bubble" onclick={() => mine && (selected = selected === p.seq ? null : p.seq)}>
        {#if p.body}{p.body.text}{:else}<span class="muted">🔒 не вдалося розшифрувати</span>{/if}
      </div>
      <div class="time">{time(p.at)}</div>
    </div>
  {:else}
    <p class="muted center-text">{loading ? 'Завантаження…' : 'Дописів ще немає.'}</p>
  {/each}
</div>

{#if mine && selected !== null}
  <div class="composer msg-actions">
    <span class="spacer"></span>
    <button class="danger" onclick={() => removePost(selected!)}>Видалити допис</button>
    <button class="ghost" onclick={() => (selected = null)}>✕</button>
  </div>
{:else if mine}
  <form class="composer" onsubmit={(e) => { e.preventDefault(); send(); }}>
    <input placeholder="Допис у канал" bind:value={draft} disabled={busy} maxlength="4000" />
    <button class="primary" type="submit" disabled={busy || !draft.trim()}>Опублікувати</button>
  </form>
{/if}

<style>
  .post { max-width: 90%; }
  .channel-info { display: grid; gap: 8px; }
  .channel-info .qr { max-width: 180px; }
  .about { white-space: pre-wrap; margin: 0; }
  textarea { width: 100%; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; color: inherit; font: inherit; }
  .button-like { background: var(--line); border-radius: 8px; padding: 8px 12px; cursor: pointer; }
</style>
