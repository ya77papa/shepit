<script lang="ts">
  // Позначка стану вихідного повідомлення (варіант A — галочки).
  // sending — годинник; sent — одна; delivered — дві; read — дві кольорові; failed — знак оклику.
  export type TickState = 'sending' | 'sent' | 'delivered' | 'read' | 'failed';
  let { state, title, onretry }: { state: TickState; title: string; onretry?: () => void } = $props();
</script>

{#if state === 'failed'}
  <button class="tick failed" {title} aria-label={title} onclick={(e) => { e.stopPropagation(); onretry?.(); }}>
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" stroke-width="1.5" />
      <path d="M8 4.5v4.2M8 10.8v.7" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
    </svg>
  </button>
{:else}
  <span class="tick {state}" {title} role="img" aria-label={title}>
    <svg viewBox="0 0 18 12" width="17" height="12" aria-hidden="true">
      {#if state === 'sending'}
        <circle cx="9" cy="6" r="4.8" fill="none" stroke="currentColor" stroke-width="1.3" />
        <path d="M9 3.6V6l1.7 1.1" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
      {:else}
        <path d="M1.5 6.4l3 3 6-6.8" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
        {#if state !== 'sent'}
          <path d="M7.6 9.2l.2.2 6-6.8" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
        {/if}
      {/if}
    </svg>
  </span>
{/if}

<style>
  .tick { display: inline-flex; vertical-align: -2px; margin-left: 3px; color: inherit; }
  .tick.read { color: var(--accent); }
  .failed { color: var(--danger); background: none; border: 0; padding: 0; cursor: pointer; min-height: 0; }
</style>
