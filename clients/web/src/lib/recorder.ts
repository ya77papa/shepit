// Запис голосу: Opus з низьким бітрейтом — добре звучить і на поганому зв'язку.

export const MAX_VOICE_MS = 30_000;
const BITRATE = 16_000;

function pickMime(): string {
  for (const m of ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/mp4']) {
    if (typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(m)) return m;
  }
  return '';
}

export type Recording = { blob: Blob; mime: string; dur: number };

export class VoiceRecorder {
  private rec: MediaRecorder | null = null;
  private stream: MediaStream | null = null;
  private chunks: Blob[] = [];
  private started = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private done: ((r: Recording | null) => void) | null = null;

  get active() {
    return this.rec?.state === 'recording';
  }

  /** Почати запис. `onAutoStop` — коли досягнуто ліміту тривалості. */
  async start(onAutoStop: () => void): Promise<void> {
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    const mime = pickMime();
    this.rec = new MediaRecorder(this.stream, { ...(mime ? { mimeType: mime } : {}), audioBitsPerSecond: BITRATE });
    this.chunks = [];
    this.rec.ondataavailable = (e) => e.data.size && this.chunks.push(e.data);
    this.rec.onstop = () => {
      const dur = Date.now() - this.started;
      const type = this.rec?.mimeType || mime || 'audio/webm';
      this.stream?.getTracks().forEach((t) => t.stop());
      const blob = new Blob(this.chunks, { type });
      this.done?.(dur < 400 || !blob.size ? null : { blob, mime: type, dur });
      this.done = null;
    };
    this.started = Date.now();
    this.rec.start(250);
    this.timer = setTimeout(onAutoStop, MAX_VOICE_MS);
  }

  /** Зупинити й отримати запис (null — надто короткий або скасовано). */
  stop(cancel = false): Promise<Recording | null> {
    if (this.timer) clearTimeout(this.timer);
    if (!this.rec || this.rec.state !== 'recording') return Promise.resolve(null);
    return new Promise((resolve) => {
      this.done = cancel ? () => resolve(null) : resolve;
      this.rec!.stop();
    });
  }
}
