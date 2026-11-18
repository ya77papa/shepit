<script lang="ts">
  import { avatarSrc } from './lib/avatar';

  let { name, src, size = 40, icon = '', online = false }: { name: string; src?: string; size?: number; icon?: string; online?: boolean } = $props();
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

{#if online}
  <span class="wrap" style:width="{size}px" style:height="{size}px">
    {@render face()}
    <span class="dot" title="В мережі" style:width="{Math.max(9, size * 0.28)}px" style:height="{Math.max(9, size * 0.28)}px"></span>
  </span>
{:else}
  {@render face()}
{/if}

<style>
  .avatar { border-radius: 50%; flex: none; object-fit: cover; display: inline-grid; place-items: center; color: white; font-weight: 600; }
  .wrap { position: relative; display: inline-block; flex: none; }
  .dot { position: absolute; right: 0; bottom: 0; border-radius: 50%; background: #3fb950; box-shadow: 0 0 0 2px var(--bg, #0d1117); }
</style>
