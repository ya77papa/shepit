// Тихі звуки у відкритій бесіді («надіслано», «отримано») — синтезовані, без файлів.
let ctx: AudioContext | null = null;

function blip(freqs: number[], dur: number, vol = 0.06) {
  try {
    ctx ??= new AudioContext();
    let t = ctx.currentTime + 0.01;
    for (const f of freqs) {
      const o = ctx.createOscillator();
      const g = ctx.createGain();
      o.type = 'sine';
      o.frequency.value = f;
      g.gain.setValueAtTime(0, t);
      g.gain.linearRampToValueAtTime(vol, t + 0.006);
      g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      o.connect(g).connect(ctx.destination);
      o.start(t);
      o.stop(t + dur + 0.02);
      t += dur;
    }
  } catch {
    // Браузер не дозволив звук (немає взаємодії зі сторінкою) — не страшно.
  }
}

export const playSent = () => blip([880, 1320], 0.07);
export const playReceived = () => blip([1175, 784], 0.11);
