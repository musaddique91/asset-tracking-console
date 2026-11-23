import type { Zone } from '../api/types';

type Ring = number[][];

/**
 * A ring whose consecutive longitudes jump by more than 180 degrees crosses the antimeridian
 * (Z-08 is written 179.3 .. -179.4). Shifting its west longitudes by +360 gives the intended small
 * polygon; MapLibre draws coordinates above 180 in the next world copy.
 */
export function unwrapRing(ring: Ring): Ring {
  let crosses = false;
  for (let i = 1; i < ring.length; i++) {
    const a = ring[i - 1];
    const b = ring[i];
    if (a && b && Math.abs((b[0] ?? 0) - (a[0] ?? 0)) > 180) crosses = true;
  }
  if (!crosses) return ring;
  return ring.map(([lon = 0, lat = 0]) => [lon < 0 ? lon + 360 : lon, lat]);
}

/** Makes a line continuous across the antimeridian (for asset tracks). */
export function unwrapLine(coords: [number, number][]): [number, number][] {
  const out: [number, number][] = [];
  let offset = 0;
  let prev: number | null = null;
  for (const [lon, lat] of coords) {
    if (prev !== null) {
      const d = lon + offset - prev;
      if (d > 180) offset -= 360;
      else if (d < -180) offset += 360;
    }
    const l = lon + offset;
    out.push([l, lat]);
    prev = l;
  }
  return out;
}

export function zonesGeoJson(zones: Zone[]): GeoJSON.FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: zones.map((z) => {
      const geometry: GeoJSON.Geometry =
        z.geometry.type === 'Polygon'
          ? { type: 'Polygon', coordinates: z.geometry.coordinates.map(unwrapRing) }
          : { type: 'MultiPolygon', coordinates: z.geometry.coordinates.map((p) => p.map(unwrapRing)) };
      return { type: 'Feature', properties: { id: z.zoneId, name: z.name, cls: z.zoneClass }, geometry };
    }),
  };
}

/** Label anchor: centre of the bounding box of the largest outer ring. */
export function labelPoint(z: Zone): [number, number] {
  const outers: Ring[] =
    z.geometry.type === 'Polygon'
      ? [unwrapRing(z.geometry.coordinates[0] ?? [])]
      : z.geometry.coordinates.map((p) => unwrapRing(p[0] ?? []));
  let best: [number, number] = [0, 0];
  let bestArea = -1;
  for (const ring of outers) {
    const lons = ring.map((c) => c[0] ?? 0);
    const lats = ring.map((c) => c[1] ?? 0);
    const w = Math.max(...lons) - Math.min(...lons);
    const h = Math.max(...lats) - Math.min(...lats);
    if (w * h > bestArea) {
      bestArea = w * h;
      best = [(Math.max(...lons) + Math.min(...lons)) / 2, Math.max(...lats)];
    }
  }
  return best;
}

export function graticule(step: number): GeoJSON.FeatureCollection {
  const features: GeoJSON.Feature[] = [];
  for (let lon = -180; lon <= 180; lon += step) {
    features.push({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[lon, -80], [lon, 80]] } });
  }
  for (let lat = -80; lat <= 80; lat += step) {
    features.push({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[-180, lat], [180, lat]] } });
  }
  return { type: 'FeatureCollection', features };
}
