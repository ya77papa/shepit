// Фото профілю, групи чи каналу: квадрат до 512×512, JPEG ≤ 48 КБ.
// Стискається тут, у браузері; далі йде лише всередині зашифрованих повідомлень
// (для зашифрованих каналів — зашифрованим описом каналу).

const SIZES = [512, 384, 256, 192];

export async function makeAvatar(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) throw new Error('Це не зображення');
  const bmp = await createImageBitmap(file);
  const side = Math.min(bmp.width, bmp.height);
  try {
    for (const size of SIZES) {
      const canvas = new OffscreenCanvas(size, size);
      // Квадрат з центру зображення.
      canvas.getContext('2d')!.drawImage(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, 0, 0, size, size);
      for (const q of [0.82, 0.7, 0.58]) {
        const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: q });
        if (blob.size <= 48_000) return toB64(new Uint8Array(await blob.arrayBuffer()));
      }
    }
  } finally {
    bmp.close();
  }
  throw new Error('Не вдалося стиснути фото');
}

function toB64(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(s);
}

export const avatarSrc = (b64?: string) => (b64 ? `data:image/jpeg;base64,${b64}` : '');
