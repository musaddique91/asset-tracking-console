import { describe, expect, it } from 'vitest';
import { unwrapLine, unwrapRing } from './geo';

describe('antimeridian', () => {
  it('unwraps a ring written across 180 degrees', () => {
    const ring = [[179.3, 34], [-179.4, 34], [-179.4, 35], [179.3, 35], [179.3, 34]];
    expect(unwrapRing(ring).map((c) => c[0])).toEqual([179.3, 180.6, 180.6, 179.3, 179.3]);
  });
  it('leaves an ordinary ring alone', () => {
    const ring = [[168, 38], [168.8, 38], [168.8, 38.6], [168, 38]];
    expect(unwrapRing(ring)).toBe(ring);
  });
  it('keeps tracks continuous', () => {
    expect(unwrapLine([[179.9, 0], [-179.9, 0], [-179.8, 0]]).map((c) => Math.round(c[0] * 10) / 10)).toEqual([179.9, 180.1, 180.2]);
  });
});
