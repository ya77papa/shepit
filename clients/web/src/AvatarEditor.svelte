<script lang="ts">
  // Редактор фото: посунути мишею чи пальцем, збільшити колесом, жестом чи повзунком,
  // повернути повзунком, кнопками на 90° або двома пальцями. В аватар потрапляє вміст кола.
  import { onMount } from 'svelte';

  let { file, onDone, onCancel }: { file: File; onDone: (b64: string) => void; onCancel: () => void } = $props();

  const VIEW = 300; // розмір області в CSS-пікселях
  const OUT = 192;
  let canvas: HTMLCanvasElement | undefined = $state();
  let img: ImageBitmap | null = $state(null);
  let scale = $state(1);
  let rotation = $state(0); // градуси
  let dx = $state(0);
  let dy = $state(0);
  let error = $state('');
  let busy = $state(false);

  onMount(() => {
    createImageBitmap(file, { imageOrientation: 'from-image' })
      .then((b) => (img = b))
      .catch(() => (error = 'Не вдалося відкрити зображення'));
    return () => img?.close();
  });

  /** Однакове перетворення для перегляду і для результату (k — масштаб відносно області). */
  function paint(ctx: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, size: number) {
    if (!img) return;
    const k = size / VIEW;
    const base = VIEW / Math.min(img.width, img.height);
    ctx.fillStyle = '#0f1722';
    ctx.fillRect(0, 0, size, size);
    ctx.save();
    ctx.translate(size / 2 + dx * k, size / 2 + dy * k);
    ctx.rotate((rotation * Math.PI) / 180);
    const s = base * scale * k;
    ctx.scale(s, s);
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(img, -img.width / 2, -img.height / 2);
    ctx.restore();
  }

  $effect(() => {
    // залежності: img, scale, rotation, dx, dy
    void [img, scale, rotation, dx, dy];
    if (!canvas || !img) return;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = VIEW * dpr;
    canvas.height = VIEW * dpr;
    const ctx = canvas.getContext('2d')!;
    paint(ctx, VIEW * dpr);
    // затемнення поза колом
    const r = (VIEW * dpr) / 2;
    ctx.save();
    ctx.fillStyle = 'rgba(0,0,0,0.6)';
    ctx.beginPath();
    ctx.rect(0, 0, VIEW * dpr, VIEW * dpr);
    ctx.arc(r, r, r, 0, Math.PI * 2, true);
    ctx.fill('evenodd');
    ctx.strokeStyle = 'rgba(255,255,255,0.7)';
    ctx.lineWidth = 2 * dpr;
    ctx.beginPath();
    ctx.arc(r, r, r - dpr, 0, Math.PI * 2);
    ctx.stroke();
    ctx.restore();
  });

  // ---- жести ----
  const pointers = new Map<number, { x: number; y: number }>();
  let gesture: { dist: number; angle: number; scale: number; rotation: number } | null = null;

  const pair = () => {
    const [a, b] = [...pointers.values()];
    return { dist: Math.hypot(b!.x - a!.x, b!.y - a!.y), angle: (Math.atan2(b!.y - a!.y, b!.x - a!.x) * 180) / Math.PI };
  };

  function down(e: PointerEvent) {
    canvas?.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 2) gesture = { ...pair(), scale, rotation };
  }
  function move(e: PointerEvent) {
    const prev = pointers.get(e.pointerId);
    if (!prev) return;
    const cur = { x: e.clientX, y: e.clientY };
    pointers.set(e.pointerId, cur);
    if (pointers.size === 1) {
      dx += cur.x - prev.x;
      dy += cur.y - prev.y;
    } else if (pointers.size === 2 && gesture) {
      const p = pair();
      scale = clampScale(gesture.scale * (p.dist / gesture.dist));
      rotation = gesture.rotation + (p.angle - gesture.angle);
    }
  }
  function up(e: PointerEvent) {
    pointers.delete(e.pointerId);
    if (pointers.size < 2) gesture = null;
  }
  function wheel(e: WheelEvent) {
    e.preventDefault();
    scale = clampScale(scale * Math.exp(-e.deltaY / 500));
  }
  const clampScale = (s: number) => Math.min(6, Math.max(1, s));
  const shownRotation = $derived(Math.round((((rotation % 360) + 540) % 360) - 180));

  async function done() {
    if (!img) return;
    busy = true;
    try {
      const out = new OffscreenCanvas(OUT, OUT);
      paint(out.getContext('2d')!, OUT);
      for (const q of [0.85, 0.72, 0.58]) {
        const blob = await out.convertToBlob({ type: 'image/jpeg', quality: q });
        if (blob.size <= 48_000) {
          const b = new Uint8Array(await blob.arrayBuffer());
          let s = '';
          for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
          return onDone(btoa(s));
        }
      }
      error = 'Не вдалося стиснути фото';
    } finally {
      busy = false;
    }
  }
</script>

<div class="overlay editor-overlay" role="presentation">
  <div class="card modal editor">
    <h2>Фото</h2>
    <p class="small muted">Посунь мишею чи пальцем, збільш колесом або двома пальцями.</p>
    {#if error}<p class="error">{error}</p>{/if}
    <canvas
      bind:this={canvas}
      style:width="{VIEW}px"
      style:height="{VIEW}px"
      onpointerdown={down}
      onpointermove={move}
      onpointerup={up}
      onpointercancel={up}
      onwheel={wheel}
    ></canvas>
    <label class="slider">
      <span class="small muted">Масштаб</span>
      <input type="range" min="1" max="6" step="0.01" bind:value={scale} />
    </label>
    <label class="slider">
      <span class="small muted">Поворот: {shownRotation}°</span>
      <input type="range" min="-180" max="180" step="1" value={shownRotation} oninput={(e) => (rotation = +(e.currentTarget as HTMLInputElement).value)} />
    </label>
    <div class="row wrap">
      <button onclick={() => (rotation -= 90)}>Вліво 90°</button>
      <button onclick={() => (rotation += 90)}>Вправо 90°</button>
      <button class="ghost" onclick={() => { scale = 1; rotation = 0; dx = 0; dy = 0; }}>Скинути</button>
    </div>
    <div class="row actions">
      <button class="ghost" onclick={onCancel}>Скасувати</button>
      <span class="spacer"></span>
      <button class="primary" onclick={done} disabled={busy || !img}>Готово</button>
    </div>
  </div>
</div>

<style>
  .editor-overlay { z-index: 60; }
  .editor { justify-items: center; }
  .editor h2, .editor p { justify-self: start; margin: 0; }
  canvas { touch-action: none; border-radius: 8px; background: #16202e; cursor: grab; max-width: 100%; }
  canvas:active { cursor: grabbing; }
  .slider { display: grid; gap: 4px; width: 100%; }
  .slider input { padding: 0; }
  .actions { width: 100%; }
</style>
