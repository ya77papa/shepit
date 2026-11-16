// Голосові дзвінки 1:1.
//
// - Звук: WebRTC (шифрування DTLS-SRTP вбудоване й обов'язкове).
// - Сигналізація (offer/answer) йде звичайними E2EE-повідомленнями Шепоту: сервер не бачить
//   параметрів з'єднання і не може непомітно стати посередником.
// - За замовчуванням — лише через ретранслятор (TURN): співрозмовник не бачить твою IP-адресу.

import { encode, type Body } from './content';
import type { Shepit } from './shepit';

export type CallState = 'idle' | 'outgoing' | 'incoming' | 'connecting' | 'active';
export type CallLog = (peer: string, text: string) => Promise<void>;

const RING_MS = 45_000;
const OFFER_MAX_AGE_MS = 60_000;
const GATHER_MS = 5_000;
const CONNECT_MS = 25_000;

export type RelayCheck = { url: string; ok: boolean; detail: string };

/**
 * Перевірити ретранслятор: для кожної адреси TURN окремо спробувати отримати relay-кандидата.
 * Показує, чи сервер досяжний, чи приймає логін і яку адресу він віддає.
 */
export async function checkRelay(me: Shepit): Promise<RelayCheck[]> {
  const ice = await me.iceServers();
  if (!ice) return [{ url: '—', ok: false, detail: 'Ретранслятор на сервері не налаштований' }];
  const { urls, username, credential } = ice[0] as { urls: string[]; username: string; credential: string };
  return Promise.all(
    (Array.isArray(urls) ? urls : [urls]).map(
      (url) =>
        new Promise<RelayCheck>((resolve) => {
          const pc = new RTCPeerConnection({ iceServers: [{ urls: url, username, credential }], iceTransportPolicy: 'relay' });
          const relays: string[] = [];
          const errors: string[] = [];
          pc.onicecandidate = (e) => {
            const c = e.candidate;
            if (c && c.type === 'relay') relays.push(`${c.address}:${c.port}`);
          };
          pc.onicecandidateerror = (e) => errors.push(`${(e as RTCPeerConnectionIceErrorEvent).errorCode} ${(e as RTCPeerConnectionIceErrorEvent).errorText}`.trim());
          const done = () => {
            pc.close();
            resolve(
              relays.length
                ? { url, ok: true, detail: `адреса ретранслятора ${relays.join(', ')}` }
                : { url, ok: false, detail: errors.length ? `помилка: ${errors.join('; ')}` : 'немає відповіді (порт закритий або сервер не працює)' },
            );
          };
          const t = setTimeout(done, 8000);
          pc.onicegatheringstatechange = () => {
            if (pc.iceGatheringState === 'complete') {
              clearTimeout(t);
              done();
            }
          };
          pc.createDataChannel('probe');
          pc.createOffer().then((o) => pc.setLocalDescription(o)).catch(() => done());
        }),
    ),
  );
}

/** Які типи кандидатів є в SDP (host/srflx/relay) — для діагностики. */
function candidateTypes(sdp: string): string {
  const types = [...sdp.matchAll(/ typ (\w+)/g)].map((m) => m[1]);
  return types.length ? [...new Set(types)].join(', ') : 'жодного';
}

function newId() {
  return Array.from(crypto.getRandomValues(new Uint8Array(8)), (b) => b.toString(16).padStart(2, '0')).join('');
}

export class CallManager {
  state: CallState = $state('idle');
  peer = $state('');
  muted = $state(false);
  startedAt = $state(0);
  relayed = $state(true);
  error = $state('');
  remote: MediaStream | null = $state(null);

  private id = '';
  private pc: RTCPeerConnection | null = null;
  private local: MediaStream | null = null;
  private pendingOffer: string | null = null;
  private ringTimer: ReturnType<typeof setTimeout> | null = null;
  private outgoing = false;
  private connectTimer: ReturnType<typeof setTimeout> | null = null;
  private localTypes = '';
  private remoteTypes = '';

  private watchConnect() {
    if (this.connectTimer) clearTimeout(this.connectTimer);
    this.connectTimer = setTimeout(() => {
      if (this.state !== 'active') this.hangupWithError(this.diagnose());
    }, CONNECT_MS);
  }

  private diagnose(): string {
    const ice = this.pc?.iceConnectionState ?? '—';
    if (!this.localTypes.includes('relay') && this.relayed) return `Не вдалося з'єднатися: ретранслятор не відповідає (твої кандидати: ${this.localTypes || 'жодного'}). Перевір «Перевірити дзвінки».`;
    if (!this.remoteTypes.includes('relay') && this.relayed) return `Не вдалося з'єднатися: у співрозмовника ретранслятор не відповідає (його кандидати: ${this.remoteTypes || 'жодного'}).`;
    return `Не вдалося з'єднатися (ICE: ${ice}; мої: ${this.localTypes}; співрозмовника: ${this.remoteTypes}).`;
  }

  private async hangupWithError(text: string) {
    const peer = this.peer;
    await this.signal(peer, { t: 'call', op: 'end', id: this.id, at: Date.now() }).catch(() => {});
    this.finish(text, true);
  }

  constructor(private me: () => Shepit | null, private log: CallLog) {}

  private async signal(peer: string, body: Body) {
    // Пропозиція дзвінка будить отримувача терміновим сповіщенням, навіть якщо застосунок закритий.
    await this.me()!.send(peer, encode(body), body.t === 'call' && body.op === 'offer' ? 'call' : undefined);
  }

  /** Мікрофони пристрою. На Android у Chrome вибір мікрофона перемикає й динамік (вухо/гучний). */
  inputs: MediaDeviceInfo[] = $state([]);
  inputId = $state('');

  private async listInputs() {
    try {
      this.inputs = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === 'audioinput' && d.deviceId);
    } catch {
      this.inputs = [];
    }
  }

  /** Знайти «розмовний» мікрофон (до вуха), якщо браузер його показує. */
  earpieceId(): string {
    return this.inputs.find((d) => /earpiece|навушник|телефон|receiver/i.test(d.label) && !/headset|гарнітур/i.test(d.label))?.deviceId ?? '';
  }

  speakerId(): string {
    return this.inputs.find((d) => /speaker|гучн|динамік/i.test(d.label))?.deviceId ?? '';
  }

  /** Перемкнути мікрофон (а з ним на Android — і вивід звуку) посеред розмови. */
  async switchInput(deviceId: string) {
    if (!this.pc || !deviceId) return;
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { deviceId: { exact: deviceId }, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    const track = stream.getAudioTracks()[0];
    track.enabled = !this.muted;
    const sender = this.pc.getSenders().find((s) => s.track?.kind === 'audio');
    await sender?.replaceTrack(track);
    this.local?.getTracks().forEach((t) => t.stop());
    this.local = stream;
    this.inputId = deviceId;
  }

  private async makePc(): Promise<RTCPeerConnection> {
    const ice = await this.me()!.iceServers();
    this.relayed = !!ice;
    if (!ice) console.warn('Ретранслятор не налаштований — пряме з\'єднання (IP видно співрозмовнику)');
    const pc = new RTCPeerConnection({ iceServers: ice ?? [], iceTransportPolicy: ice ? 'relay' : 'all' });
    pc.ontrack = (e) => (this.remote = e.streams[0] ?? new MediaStream([e.track]));
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'connected' && this.state !== 'active') {
        this.state = 'active';
        this.startedAt = Date.now();
      }
      if (pc.connectionState === 'failed') this.hangupWithError(this.diagnose());
    };
    this.local = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    // Після дозволу на мікрофон браузер показує назви пристроїв. Якщо є «до вуха» — почати з нього,
    // як у звичайному телефонному дзвінку.
    await this.listInputs();
    const ear = this.earpieceId();
    if (ear) {
      this.local.getTracks().forEach((t) => t.stop());
      this.local = await navigator.mediaDevices.getUserMedia({
        audio: { deviceId: { exact: ear }, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
      this.inputId = ear;
    } else {
      this.inputId = this.local.getAudioTracks()[0]?.getSettings().deviceId ?? '';
    }
    this.local.getTracks().forEach((t) => pc.addTrack(t, this.local!));
    this.pc = pc;
    return pc;
  }

  /** Дочекатися збору ICE-кандидатів (без trickle: одне повідомлення замість десятка). */
  private gathered(pc: RTCPeerConnection): Promise<string> {
    return new Promise((resolve) => {
      const done = () => resolve(pc.localDescription!.sdp);
      if (pc.iceGatheringState === 'complete') return done();
      const t = setTimeout(done, GATHER_MS);
      pc.addEventListener('icegatheringstatechange', () => {
        if (pc.iceGatheringState === 'complete') {
          clearTimeout(t);
          done();
        }
      });
    });
  }

  async start(peer: string) {
    if (this.state !== 'idle') return;
    this.error = '';
    this.state = 'outgoing';
    this.peer = peer;
    this.id = newId();
    this.outgoing = true;
    try {
      const pc = await this.makePc();
      await pc.setLocalDescription(await pc.createOffer());
      const sdp = await this.gathered(pc);
      this.localTypes = candidateTypes(sdp);
      if (this.state !== 'outgoing') return; // скасували, поки збирали кандидатів
      await this.signal(peer, { t: 'call', op: 'offer', id: this.id, at: Date.now(), sdp });
      this.ringTimer = setTimeout(() => this.hangup('Без відповіді'), RING_MS);
    } catch (e) {
      this.finish(e instanceof DOMException && e.name === 'NotAllowedError' ? 'Немає доступу до мікрофона' : String(e), true);
    }
  }

  /** Обробити службове повідомлення дзвінка від `from`. */
  async onSignal(from: string, b: Extract<Body, { t: 'call' }>) {
    if (b.op === 'offer') {
      if (Date.now() - b.at > OFFER_MAX_AGE_MS) {
        await this.log(from, '📞 Пропущений дзвінок');
        return;
      }
      if (this.state !== 'idle') {
        if (b.id !== this.id) await this.signal(from, { t: 'call', op: 'busy', id: b.id, at: Date.now() });
        return;
      }
      this.state = 'incoming';
      this.peer = from;
      this.id = b.id;
      this.outgoing = false;
      this.pendingOffer = b.sdp ?? null;
      this.ringTimer = setTimeout(() => this.finish('📞 Пропущений дзвінок'), RING_MS);
      return;
    }
    if (from !== this.peer || b.id !== this.id) return; // чужий або старий дзвінок
    if (b.op === 'answer' && this.state === 'outgoing' && this.pc && b.sdp) {
      if (this.ringTimer) clearTimeout(this.ringTimer);
      this.state = 'connecting';
      this.remoteTypes = candidateTypes(b.sdp);
      this.watchConnect();
      await this.pc.setRemoteDescription({ type: 'answer', sdp: b.sdp });
    } else if (b.op === 'reject') {
      this.finish('📞 Дзвінок відхилено');
    } else if (b.op === 'busy') {
      this.finish('📞 Співрозмовник зайнятий');
    } else if (b.op === 'end') {
      this.finish(this.summary(this.state === 'incoming' ? '📞 Пропущений дзвінок' : undefined));
    }
  }

  async accept() {
    if (this.state !== 'incoming' || !this.pendingOffer) return;
    if (this.ringTimer) clearTimeout(this.ringTimer);
    this.state = 'connecting';
    try {
      const pc = await this.makePc();
      this.remoteTypes = candidateTypes(this.pendingOffer);
      await pc.setRemoteDescription({ type: 'offer', sdp: this.pendingOffer });
      await pc.setLocalDescription(await pc.createAnswer());
      const sdp = await this.gathered(pc);
      this.localTypes = candidateTypes(sdp);
      this.watchConnect();
      await this.signal(this.peer, { t: 'call', op: 'answer', id: this.id, at: Date.now(), sdp });
    } catch (e) {
      await this.signal(this.peer, { t: 'call', op: 'end', id: this.id, at: Date.now() }).catch(() => {});
      this.finish(e instanceof DOMException && e.name === 'NotAllowedError' ? 'Немає доступу до мікрофона' : String(e), true);
    }
  }

  async reject() {
    if (this.state !== 'incoming') return;
    const peer = this.peer;
    await this.signal(peer, { t: 'call', op: 'reject', id: this.id, at: Date.now() }).catch(() => {});
    this.finish('📞 Ти відхилив(-ла) дзвінок');
  }

  async hangup(reason?: string) {
    if (this.state === 'idle') return;
    const peer = this.peer;
    await this.signal(peer, { t: 'call', op: 'end', id: this.id, at: Date.now() }).catch(() => {});
    this.finish(reason ? `📞 ${reason}` : this.summary());
  }

  toggleMute() {
    this.muted = !this.muted;
    this.local?.getAudioTracks().forEach((t) => (t.enabled = !this.muted));
  }

  private summary(fallback?: string): string {
    if (this.startedAt) {
      const s = Math.round((Date.now() - this.startedAt) / 1000);
      const d = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
      return `📞 ${this.outgoing ? 'Вихідний' : 'Вхідний'} дзвінок · ${d}`;
    }
    return fallback ?? (this.outgoing ? '📞 Скасований дзвінок' : '📞 Пропущений дзвінок');
  }

  private finish(text: string, isError = false) {
    const peer = this.peer;
    if (this.ringTimer) clearTimeout(this.ringTimer);
    if (this.connectTimer) clearTimeout(this.connectTimer);
    this.pc?.close();
    this.local?.getTracks().forEach((t) => t.stop());
    this.pc = null;
    this.local = null;
    this.remote = null;
    this.pendingOffer = null;
    this.state = 'idle';
    this.muted = false;
    this.startedAt = 0;
    if (isError) this.error = text;
    if (peer) this.log(peer, isError ? `📞 ${text}` : text).catch(() => {});
    this.peer = '';
  }
}
