<script lang="ts">
  import { avatarSrc } from './lib/avatar';

  let {
    name, src, size = 40, icon = '', online = false, unread = 0, ring = 'var(--bg)',
  }: { name: string; src?: string; size?: number; icon?: string; online?: boolean; unread?: number; ring?: string } = $props();
  const COLORS = ['#5b8def', '#e0884b', '#4bb38a', '#b46bd6', '#d6577a', '#4ba3c7'];
  const initials = $derived(
    icon || name.split(/[\s-]+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('') || '?',
  );
  const color = $derived(COLORS[[...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 0) % COLORS.length]);
</script>

{#snippet face()}
  {#if src}
    <img class="avatar" src={avatarSrc(src)} alt="" width={size} height={size} style:width="{size}px" style:height="{size}px" />
  {:else}
    <span class="avatar" style:width="{size}px" style:height="{size}px" style:background={color} style:font-size="{size * 0.4}px">{initials}</span>
  {/if}
{/snippet}

{#if online || unread > 0}
  <span class="wrap" style:width="{size}px" style:height="{size}px" style:--ring={ring}>
    {@render face()}
    {#if online}<span class="dot" title="В мережі" style:width="{Math.max(12, size * 0.3)}px" style:height="{Math.max(12, size * 0.3)}px"></span>{/if}
    {#if unread > 0}<span class="count" title="Непрочитаних: {unread}">{unread > 99 ? '99+' : unread}</span>{/if}
  </span>
{:else}
  {@render face()}
{/if}

<style>
  .avatar { border-radius: 50%; flex: none; object-fit: cover; display: inline-grid; place-items: center; color: white; font-weight: 600; }
  .wrap { position: relative; display: inline-block; flex: none; }
  .dot { position: absolute; right: -2px; bottom: -2px; box-sizing: border-box; border-radius: 50%; background: #3fb950; border: 2.5px solid var(--ring); }
  .count {
    position: absolute; top: -5px; right: -7px; box-sizing: border-box; min-width: 20px; height: 20px; padding: 0 5px;
    border-radius: 10px; border: 2px solid var(--ring); background: var(--accent); color: #1b1b1b;
    font-size: 11px; font-weight: 700; line-height: 16px; text-align: center;
  }
</style>
