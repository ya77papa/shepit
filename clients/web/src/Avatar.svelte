<script lang="ts">
  import { avatarSrc } from './lib/avatar';

  let { name, src, size = 40, icon = '' }: { name: string; src?: string; size?: number; icon?: string } = $props();
  const COLORS = ['#5b8def', '#e0884b', '#4bb38a', '#b46bd6', '#d6577a', '#4ba3c7'];
  const initials = $derived(
    icon || name.split(/[\s-]+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('') || '?',
  );
  const color = $derived(COLORS[[...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 0) % COLORS.length]);
</script>

{#if src}
  <img class="avatar" src={avatarSrc(src)} alt="" width={size} height={size} style:width="{size}px" style:height="{size}px" />
{:else}
  <span class="avatar" style:width="{size}px" style:height="{size}px" style:background={color} style:font-size="{size * 0.4}px">{initials}</span>
{/if}

<style>
  .avatar { border-radius: 50%; flex: none; object-fit: cover; display: inline-grid; place-items: center; color: white; font-weight: 600; }
</style>
