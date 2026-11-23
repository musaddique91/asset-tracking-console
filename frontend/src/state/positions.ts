import type { AssetPosition, PositionTuple } from '../api/types';

export interface Pos {
  id: string;
  lat: number;
  lon: number;
  heading: number | null;
  kind: string | null;
  observedAt: string;
  reportId: string;
  speedKn: number | null;
}

/**
 * Current positions, held outside React. Thousands of updates per second only touch this map; the
 * map layer reads it on its own clock, so no React component re-renders per position update.
 *
 * Merge rule mirrors the server (R2): a position replaces another only when its (observedAt, reportId)
 * is greater. That makes the order of REST snapshots and stream events irrelevant.
 */
export class PositionStore {
  private readonly byId = new Map<string, Pos>();
  private dirty = false;
  private readonly listeners = new Set<(ids: ReadonlySet<string>) => void>();
  private changed = new Set<string>();

  get size(): number {
    return this.byId.size;
  }

  get(id: string): Pos | undefined {
    return this.byId.get(id);
  }

  values(): IterableIterator<Pos> {
    return this.byId.values();
  }

  upsert(p: Pos): boolean {
    const cur = this.byId.get(p.id);
    if (cur && !newer(p, cur)) return false;
    this.byId.set(p.id, p);
    this.dirty = true;
    this.changed.add(p.id);
    return true;
  }

  applyTuples(items: PositionTuple[]): void {
    for (const [id, lat, lon, heading, kind, observedAt, reportId, speedKn] of items) {
      this.upsert({ id, lat, lon, heading, kind, observedAt, reportId, speedKn });
    }
  }

  applyRest(items: AssetPosition[]): void {
    for (const a of items) {
      this.upsert({
        id: a.assetId,
        lat: a.lat,
        lon: a.lon,
        heading: a.headingDeg ?? null,
        kind: a.kind ?? null,
        observedAt: a.observedAt,
        reportId: a.reportId,
        speedKn: a.speedKn ?? null,
      });
    }
  }

  /** Returns true once per batch of changes; the caller then redraws. */
  takeDirty(): boolean {
    if (!this.dirty) return false;
    this.dirty = false;
    if (this.listeners.size > 0) {
      const ids = this.changed;
      this.changed = new Set();
      this.listeners.forEach((l) => l(ids));
    } else {
      this.changed.clear();
    }
    return true;
  }

  onChanged(listener: (ids: ReadonlySet<string>) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
}

export function newer(a: { observedAt: string; reportId: string }, b: { observedAt: string; reportId: string }): boolean {
  // the API always writes yyyy-MM-ddTHH:mm:ss.SSSZ, so string order is time order
  if (a.observedAt !== b.observedAt) return a.observedAt > b.observedAt;
  return a.reportId > b.reportId;
}

/** Longitudes west of 0 are drawn east of 180, so the area around the antimeridian is contiguous. */
export function displayLon(lon: number): number {
  return lon < 0 ? lon + 360 : lon;
}

/** Converts a display longitude (possibly above 180) back to -180..180. */
export function wrapLon(lon: number): number {
  let l = lon;
  while (l > 180) l -= 360;
  while (l < -180) l += 360;
  return l;
}
