<script lang="ts">
  import type { Photo } from './lib/db';
  let { image, caption, onopen }: { image: Photo; caption?: string; onopen: (url: string) => void } = $props();
  let url = $state('');
  $effect(() => {
    const u = URL.createObjectURL(image.blob);
    url = u;
    return () => URL.revokeObjectURL(u);
  });
  // Висота прев'ю за пропорціями, щоб стрічка не стрибала під час завантаження.
  const ratio = $derived(Math.min(1.6, image.h / image.w));
</script>

<button class="photo" onclick={() => onopen(url)} title="Відкрити фото">
  <img src={url} alt="Фото" style:aspect-ratio={`1 / ${ratio}`} />
</button>
{#if caption}<div class="caption">{caption}</div>{/if}

<style>
  .photo { display: block; padding: 0; border: 0; background: none; cursor: zoom-in; width: min(280px, 60vw); }
  img { display: block; width: 100%; object-fit: cover; border-radius: 12px; }
  .caption { margin-top: 6px; white-space: pre-wrap; word-break: break-word; }
</style>
