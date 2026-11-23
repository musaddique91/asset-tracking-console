import { describe, expect, it } from 'vitest';
import { PositionStore, displayLon, newer, wrapLon } from './positions';

const p = (observedAt: string, reportId: string) => ({
  id: 'A', lat: 0, lon: 0, heading: null, kind: null, observedAt, reportId, speedKn: null,
});

describe('PositionStore', () => {
  it('keeps the newest report whatever the arrival order (R2 on the client)', () => {
    const s = new PositionStore();
    s.upsert(p('2026-11-02T06:00:02.000Z', 'b'));
    expect(s.upsert(p('2026-11-02T06:00:01.000Z', 'z'))).toBe(false);
    expect(s.upsert(p('2026-11-02T06:00:02.000Z', 'a'))).toBe(false);
    expect(s.upsert(p('2026-11-02T06:00:02.000Z', 'c'))).toBe(true);
    expect(s.get('A')?.reportId).toBe('c');
  });

  it('reports dirtiness once per batch', () => {
    const s = new PositionStore();
    s.upsert(p('2026-11-02T06:00:02.000Z', 'b'));
    expect(s.takeDirty()).toBe(true);
    expect(s.takeDirty()).toBe(false);
  });
});

describe('longitudes', () => {
  it('unwraps west longitudes for display and wraps back', () => {
    expect(displayLon(-179.5)).toBe(180.5);
    expect(displayLon(170)).toBe(170);
    expect(wrapLon(181.5)).toBeCloseTo(-178.5);
    expect(newer({ observedAt: 'b', reportId: '1' }, { observedAt: 'a', reportId: '9' })).toBe(true);
  });
});
