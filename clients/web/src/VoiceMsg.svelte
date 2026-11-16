<script lang="ts">
  import { onDestroy } from 'svelte';
  import { fmtDuration } from './lib/content';
  import type { Voice } from './lib/db';

  let { voice }: { voice: Voice } = $props();
  const url = URL.createObjectURL(voice.blob);
  onDestroy(() => URL.revokeObjectURL(url));
</script>

<div class="voice">
  <span>🎤</span>
  <audio controls preload="metadata" src={url}></audio>
  <span class="small muted">{fmtDuration(voice.dur)}</span>
</div>
