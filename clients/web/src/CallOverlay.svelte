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
    // Вхідний — гучна подвійна трель; вихідний — тихий гудок.
    const incoming = calls.state === 'incoming';
    const peak = incoming ? 0.9 : 0.12;
    const tones = incoming ? [0, 0.35] : [0];
    for (const at of tones) {
      const o = ctx.createOscillator();
      const g = ctx.createGain();
      o.type = incoming ? 'square' : 'sine';
      o.frequency.value = incoming ? 880 : 440;
      const t0 = ctx.currentTime + at;
      g.gain.setValueAtTime(0.0001, t0);
      g.gain.exponentialRampToValueAtTime(peak, t0 + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.3);
      o.connect(g).connect(ctx.destination);
      o.start(t0);
      o.stop(t0 + 0.32);
    }
    if (incoming) navigator.vibrate?.([400, 200, 400]);
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
          {#if calls.earpieceId() && calls.speakerId()}
            {#if calls.inputId === calls.earpieceId()}
              <button class="big" onclick={() => calls.switchInput(calls.speakerId())}>🔊 Гучний зв'язок</button>
            {:else}
              <button class="big" onclick={() => calls.switchInput(calls.earpieceId())}>📱 До вуха</button>
            {/if}
          {/if}
        {/if}
        <button class="danger big" onclick={() => calls.hangup()}>{calls.state === 'outgoing' ? 'Скасувати' : 'Завершити'}</button>
      {/if}
    </div>
    <audio bind:this={audio} autoplay></audio>
  </div>
</div>
