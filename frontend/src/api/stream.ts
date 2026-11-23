import type { AlertChange, PositionTuple } from './types';

export type ConnectionState = 'connecting' | 'live' | 'reconnecting' | 'stopped';

export interface StreamHandlers {
  onState(state: ConnectionState, detail: { attempt: number; since: number | null }): void;
  /** Called after every successful (re)connect: the console re-synchronises its state here. */
  onConnected(instance: string): void;
  onPositions(items: PositionTuple[]): void;
  onAlerts(items: AlertChange[]): void;
  onAlertsSnapshot(items: AlertChange[], truncated: boolean): void;
  onUnauthorized(): void;
}

const SILENCE_MS = 12_000; // the server sends a heartbeat every 5 s
const MAX_BACKOFF_MS = 8_000;

/**
 * Server-sent events read through fetch (EventSource cannot send a bearer token). A watchdog aborts
 * a connection that has gone silent, which is how a cable pull or a Wi-Fi drop is detected; the
 * stream then reconnects with backoff and the console re-synchronises, without a page reload.
 */
export class LiveStream {
  private stopped = false;
  private controller: AbortController | null = null;
  private attempt = 0;
  private interruptedSince: number | null = null;

  constructor(
    private readonly token: () => string | null,
    private readonly handlers: StreamHandlers,
  ) {}

  start(): void {
    this.stopped = false;
    void this.loop();
  }

  stop(): void {
    this.stopped = true;
    this.controller?.abort();
    this.handlers.onState('stopped', { attempt: 0, since: null });
  }

  private async loop(): Promise<void> {
    while (!this.stopped) {
      this.handlers.onState(this.attempt === 0 ? 'connecting' : 'reconnecting', {
        attempt: this.attempt,
        since: this.interruptedSince,
      });
      try {
        await this.connectOnce();
      } catch {
        // network error, abort by the watchdog, or the server closed the stream
      }
      if (this.stopped) return;
      this.interruptedSince ??= Date.now();
      this.attempt += 1;
      this.handlers.onState('reconnecting', { attempt: this.attempt, since: this.interruptedSince });
      const delay = Math.min(MAX_BACKOFF_MS, 500 * 2 ** Math.min(this.attempt, 5)) * (0.75 + Math.random() * 0.5);
      await new Promise((r) => setTimeout(r, delay));
    }
  }

  private async connectOnce(): Promise<void> {
    const controller = new AbortController();
    this.controller = controller;
    let watchdog = setTimeout(() => controller.abort(), SILENCE_MS);
    const kick = () => {
      clearTimeout(watchdog);
      watchdog = setTimeout(() => controller.abort(), SILENCE_MS);
    };
    try {
      const token = this.token();
      const res = await fetch('/api/v1/stream', {
        headers: { Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        signal: controller.signal,
        cache: 'no-store',
      });
      if (res.status === 401) {
        this.stopped = true;
        this.handlers.onUnauthorized();
        return;
      }
      if (!res.ok || !res.body) throw new Error(`stream answered ${res.status}`);
      const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) return;
        kick();
        buffer += value;
        let sep: number;
        while ((sep = buffer.indexOf('\n\n')) >= 0) {
          const block = buffer.slice(0, sep);
          buffer = buffer.slice(sep + 2);
          this.dispatch(block);
        }
      }
    } finally {
      clearTimeout(watchdog);
    }
  }

  private dispatch(block: string): void {
    let event = 'message';
    const data: string[] = [];
    for (const raw of block.split('\n')) {
      const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw;
      if (line.startsWith(':')) continue; // heartbeat comment
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (data.length === 0) return;
    const payload: unknown = JSON.parse(data.join('\n'));
    switch (event) {
      case 'hello': {
        this.attempt = 0;
        this.interruptedSince = null;
        this.handlers.onState('live', { attempt: 0, since: null });
        this.handlers.onConnected((payload as { instance: string }).instance);
        break;
      }
      case 'positions':
        this.handlers.onPositions((payload as { items: PositionTuple[] }).items);
        break;
      case 'alerts':
        this.handlers.onAlerts((payload as { items: AlertChange[] }).items);
        break;
      case 'alerts-snapshot': {
        const p = payload as { items: AlertChange[]; truncated: boolean };
        this.handlers.onAlertsSnapshot(p.items, p.truncated);
        break;
      }
      default:
        break;
    }
  }
}
