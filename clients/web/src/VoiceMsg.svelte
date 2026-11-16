<script lang="ts">
  import { onDestroy } from 'svelte';
  import { fmtDuration } from './lib/content';
  import type { Voice } from './lib/db';

  let { voice, autoplay = false }: { voice: Voice; autoplay?: boolean } = $props();
  const url = URL.createObjectURL(voice.blob);
  let el: HTMLAudioElement | undefined = $state();
  $effect(() => {
    if (autoplay && el) el.play().catch(() => {});
  });
  onDestroy(() => URL.revokeObjectURL(url));
</script>

<div class="voice">
  <span>{voice.ptt ? '📻' : '🎤'}</span>
  <audio bind:this={el} controls preload="metadata" src={url}></audio>
  <span class="small muted">{fmtDuration(voice.dur)}</span>
</div>
