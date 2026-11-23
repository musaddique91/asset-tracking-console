import type { Alert, AlertChange } from '../api/types';

interface Entry {
  alert: Alert;
  version: number;
}

const RECENT_RESOLVED = 200;
const NOTIFY_MS = 250;

/**
 * Live alerts, held outside React and read through useSyncExternalStore. Every change carries the
 * server's per-alert version, so a stale event (or a snapshot read before a newer event) never
 * overwrites newer state. Notifications to React are coalesced to at most four per second.
 */
export class AlertStore {
  private active = new Map<string, Entry>();
  private resolved = new Map<string, Entry>();
  private snapshot: { active: Alert[]; resolved: Alert[]; revision: number } = { active: [], resolved: [], revision: 0 };
  private readonly listeners = new Set<() => void>();
  private timer: ReturnType<typeof setTimeout> | null = null;
  private newSinceAnnounce = 0;
  private synced = false;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = () => this.snapshot;

  applySnapshot(items: AlertChange[]): void {
    const seen = new Set<string>();
    for (const c of items) {
      seen.add(c.alert.alertId);
      this.apply(c, false);
    }
    // active alerts the snapshot no longer lists were resolved while we were disconnected
    for (const id of [...this.active.keys()]) {
      if (!seen.has(id)) this.active.delete(id);
    }
    this.synced = true;
    this.schedule();
  }

  applyChanges(items: AlertChange[]): void {
    for (const c of items) this.apply(c, this.synced);
    this.schedule();
  }

  /** Applies the answer to our own command at once, unless the stream has already delivered something newer. */
  applyLocal(alert: Alert, versionAtStart: number | undefined): void {
    const cur = this.entry(alert.alertId);
    if (cur && versionAtStart !== undefined && cur.version > versionAtStart) return;
    this.apply({ alert, version: (cur?.version ?? 0) + 0.5 }, false);
    this.schedule();
  }

  version(alertId: string): number | undefined {
    return this.entry(alertId)?.version;
  }

  takeNewCount(): number {
    const n = this.newSinceAnnounce;
    this.newSinceAnnounce = 0;
    return n;
  }

  private entry(id: string): Entry | undefined {
    return this.active.get(id) ?? this.resolved.get(id);
  }

  private apply(c: AlertChange, countNew: boolean): void {
    const id = c.alert.alertId;
    const cur = this.entry(id);
    if (cur && cur.version >= c.version) return;
    if (!cur && countNew && c.alert.status === 'OPEN') this.newSinceAnnounce += 1;
    if (c.alert.status === 'RESOLVED') {
      this.active.delete(id);
      this.resolved.set(id, c);
      if (this.resolved.size > RECENT_RESOLVED) {
        const oldest = this.resolved.keys().next().value;
        if (oldest !== undefined) this.resolved.delete(oldest);
      }
    } else {
      this.resolved.delete(id);
      this.active.set(id, c);
    }
  }

  private schedule(): void {
    if (this.timer !== null) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      const active = [...this.active.values()].map((e) => e.alert).sort(byOpened);
      const resolved = [...this.resolved.values()].map((e) => e.alert).reverse();
      this.snapshot = { active, resolved, revision: this.snapshot.revision + 1 };
      this.listeners.forEach((l) => l());
    }, NOTIFY_MS);
  }
}

function byOpened(a: Alert, b: Alert): number {
  if (a.openedAt !== b.openedAt) return a.openedAt < b.openedAt ? -1 : 1;
  return a.alertId < b.alertId ? -1 : 1;
}
