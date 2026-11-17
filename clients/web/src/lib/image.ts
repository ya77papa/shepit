// Підготовка фото до надсилання: зменшити до 1600 px, перекодувати в JPEG.
// Перекодування через canvas відкидає EXIF (геолокацію, модель телефону, час зйомки).

/** Найбільша сторона фото. */
export const MAX_SIDE = 1600;
/** Ціль за розміром файлу: так фото вміщається в один зашифрований конверт. */
export const MAX_BYTES = 600 * 1024;

export type Prepared = { blob: Blob; w: number; h: number };

async function decodeImage(file: Blob): Promise<ImageBitmap> {
  try {
    return await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    throw new Error('Не вдалося відкрити фото. Підтримуються JPEG, PNG, WebP, GIF.');
  }
}

function encode(c: HTMLCanvasElement, q: number): Promise<Blob> {
  return new Promise((ok, fail) => c.toBlob((b) => (b ? ok(b) : fail(new Error('Не вдалося стиснути фото'))), 'image/jpeg', q));
}

export async function prepareImage(file: Blob): Promise<Prepared> {
  const bmp = await decodeImage(file);
  let side = MAX_SIDE;
  try {
    for (;;) {
      const k = Math.min(1, side / Math.max(bmp.width, bmp.height));
      const w = Math.max(1, Math.round(bmp.width * k));
      const h = Math.max(1, Math.round(bmp.height * k));
      const c = document.createElement('canvas');
      c.width = w;
      c.height = h;
      const ctx = c.getContext('2d')!;
      ctx.fillStyle = '#fff'; // прозорість PNG → білий фон
      ctx.fillRect(0, 0, w, h);
      ctx.drawImage(bmp, 0, 0, w, h);
      for (const q of [0.82, 0.72, 0.6, 0.5]) {
        const blob = await encode(c, q);
        if (blob.size <= MAX_BYTES) return { blob, w, h };
      }
      side = Math.round(side * 0.75);
      if (side < 320) throw new Error('Фото надто велике');
    }
  } finally {
    bmp.close();
  }
}
