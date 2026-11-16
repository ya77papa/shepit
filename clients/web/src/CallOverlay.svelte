<script lang="ts">
  import { onDestroy } from 'svelte';
  import type { CallManager } from './lib/call.svelte';
  import { fmtDuration } from './lib/content';

  let { calls, name }: { calls: CallManager; name: string } = $props();
  let audio: HTMLAudioElement | undefined = $state();
  let now = $state(Date.now());
  const tick = setInterval(() => (now = Date.now()), 500);

  $effect(() => {
    if (audio) {
      audio.srcObject = calls.remote;
      if (calls.remote) audio.play().catch(() => {});
    }
  });

  // Простий звук дзвінка (WebAudio), поки йде виклик.
  let ctx: AudioContext | null = null;
  let ringer: ReturnType<typeof setInterval> | null = null;
  function beep() {
    ctx ??= new AudioContext();
    const o = ctx.createOscillator();
    const g = ctx.createGain();
    o.frequency.value = calls.state === 'incoming' ? 660 : 440;
    g.gain.setValueAtTime(0.0001, ctx.currentTime);
    g.gain.exponentialRampToValueAtTime(0.15, ctx.currentTime + 0.05);
    g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.6);
    o.connect(g).connect(ctx.destination);
    o.start();
    o.stop(ctx.currentTime + 0.65);
  }
  $effect(() => {
    const ringing = calls.state === 'incoming' || calls.state === 'outgoing';
    if (ringing && !ringer) {
      beep();
      ringer = setInterval(beep, 2000);
    } else if (!ringing && ringer) {
      clearInterval(ringer);
      ringer = null;
    }
  });
  onDestroy(() => {
    clearInterval(tick);
    if (ringer) clearInterval(ringer);
    ctx?.close();
  });
</script>

<div class="overlay call">
  <div class="card modal call-card">
    <div class="call-avatar">📞</div>
    <h2>{name}</h2>
    <p class="muted">
      {#if calls.state === 'outgoing'}Дзвоню…
      {:else if calls.state === 'incoming'}Вхідний дзвінок
      {:else if calls.state === 'connecting'}З'єднання…
      {:else if calls.state === 'active'}{fmtDuration(now - calls.startedAt)}{/if}
    </p>
    <p class="small muted">🔒 Наскрізне шифрування · {calls.relayed ? 'через ретранслятор — твою IP-адресу не видно' : 'пряме з\'єднання'}</p>
    <div class="row center-row">
      {#if calls.state === 'incoming'}
        <button class="primary big" onclick={() => calls.accept()}>Прийняти</button>
        <button class="danger big" onclick={() => calls.reject()}>Відхилити</button>
      {:else}
        {#if calls.state === 'active'}
          <button class="big" onclick={() => calls.toggleMute()}>{calls.muted ? '🔇 Увімкнути мікрофон' : '🎙 Вимкнути мікрофон'}</button>
        {/if}
        <button class="danger big" onclick={() => calls.hangup()}>{calls.state === 'outgoing' ? 'Скасувати' : 'Завершити'}</button>
      {/if}
    </div>
    <audio bind:this={audio} autoplay></audio>
  </div>
</div>
