import { useEffect, useRef } from 'react';
import maplibregl, { type GeoJSONSource, type Map as MlMap, type StyleSpecification } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { TrackPoint, Zone } from '../api/types';
import type { PositionStore } from '../state/positions';
import { wrapLon } from '../state/positions';
import { graticule, labelPoint, unwrapLine, zonesGeoJson } from './geo';

interface Props {
  store: PositionStore;
  zones: Zone[];
  selected: string | null;
  track: TrackPoint[];
  onSelect(assetId: string): void;
  onViewport(bbox: [number, number, number, number]): void;
  /** Changing key re-centres the map on lon/lat (for example when an asset is picked from a list). */
  focus: { lon: number; lat: number; key: number } | null;
}

const FLUSH_MS = 250;

// A style with no external resources: no tiles, no sprite, no glyphs (text labels are HTML markers).
const STYLE: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: 'bg', type: 'background', paint: { 'background-color': '#0c1b2a' } }],
};

type IconImage = { width: number; height: number; data: Uint8Array };

/**
 * Asset symbols, drawn on a canvas (no sprite file is fetched) and registered as SDF images so the
 * map can tint them. Both point north; the layer rotates them by heading. AIR and SURFACE differ by
 * shape as well as colour, so the kind is readable without relying on colour alone.
 */
function drawIcon(draw: (ctx: CanvasRenderingContext2D) => void): IconImage {
  const size = 32;
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('2d canvas unavailable');
  ctx.fillStyle = '#ffffff';
  ctx.beginPath();
  draw(ctx);
  ctx.closePath();
  ctx.fill();
  const img = ctx.getImageData(0, 0, size, size);
  return { width: size, height: size, data: new Uint8Array(img.data.buffer) };
}

/** Aircraft: fuselage, swept wings and tailplane. */
function aircraftIcon(): IconImage {
  return drawIcon((c) => {
    c.moveTo(16, 1);
    c.lineTo(18, 5);
    c.lineTo(18, 11);
    c.lineTo(30, 18);
    c.lineTo(30, 21);
    c.lineTo(18, 17);
    c.lineTo(18, 25);
    c.lineTo(22, 28);
    c.lineTo(22, 31);
    c.lineTo(16, 29);
    c.lineTo(10, 31);
    c.lineTo(10, 28);
    c.lineTo(14, 25);
    c.lineTo(14, 17);
    c.lineTo(2, 21);
    c.lineTo(2, 18);
    c.lineTo(14, 11);
    c.lineTo(14, 5);
  });
}

/** Ship: pointed bow, straight sides, square stern. */
function shipIcon(): IconImage {
  return drawIcon((c) => {
    c.moveTo(16, 1);
    c.quadraticCurveTo(24, 9, 23, 16);
    c.lineTo(23, 30);
    c.lineTo(9, 30);
    c.lineTo(9, 16);
    c.quadraticCurveTo(8, 9, 16, 1);
  });
}

function featureCollection(store: PositionStore): GeoJSON.FeatureCollection {
  const features: GeoJSON.Feature[] = new Array(store.size);
  let i = 0;
  for (const p of store.values()) {
    features[i++] = {
      type: 'Feature',
      properties: { id: p.id, h: p.heading ?? 0, k: p.kind === 'AIR' ? 1 : 0, hh: p.heading === null ? 0 : 1 },
      geometry: { type: 'Point', coordinates: [p.lon, p.lat] },
    };
  }
  return { type: 'FeatureCollection', features };
}

export function MapView({ store, zones, selected, track, onSelect, onViewport, focus }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MlMap | null>(null);
  const ready = useRef(false);
  const handlers = useRef({ onSelect, onViewport });
  handlers.current = { onSelect, onViewport };

  // create the map once
  useEffect(() => {
    if (!container.current) return;
    const map = new maplibregl.Map({
      container: container.current,
      style: STYLE,
      center: [172.5, 36.5],
      zoom: 5,
      minZoom: 1.5,
      maxZoom: 14,
      attributionControl: false,
      renderWorldCopies: true,
      fadeDuration: 0,
    });
    mapRef.current = map;
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-left');
    map.addControl(new maplibregl.ScaleControl({ unit: 'nautical' }), 'bottom-left');

    map.on('load', () => {
      map.addImage('icon-air', aircraftIcon(), { sdf: true });
      map.addImage('icon-surface', shipIcon(), { sdf: true });
      map.addSource('graticule', { type: 'geojson', data: graticule(2) });
      map.addSource('zones', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      map.addSource('track', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      map.addSource('assets', { type: 'geojson', data: featureCollection(store), buffer: 16, tolerance: 0 });

      map.addLayer({ id: 'graticule', type: 'line', source: 'graticule', paint: { 'line-color': '#1d3349', 'line-width': 1 } });
      map.addLayer({
        id: 'zones-fill', type: 'fill', source: 'zones',
        paint: { 'fill-color': ['match', ['get', 'cls'], 'RESTRICTED', '#d93b3b', '#e0a526'], 'fill-opacity': 0.18 },
      });
      map.addLayer({
        id: 'zones-line', type: 'line', source: 'zones',
        paint: {
          'line-color': ['match', ['get', 'cls'], 'RESTRICTED', '#ff6b6b', '#ffc53d'],
          'line-width': 2,
          'line-dasharray': ['match', ['get', 'cls'], 'RESTRICTED', ['literal', [1, 0]], ['literal', [3, 2]]],
        },
      });
      map.addLayer({ id: 'track', type: 'line', source: 'track', paint: { 'line-color': '#7ce0ff', 'line-width': 2, 'line-opacity': 0.8 } });
      map.addLayer({
        id: 'assets', type: 'symbol', source: 'assets',
        layout: {
          'icon-image': ['case', ['==', ['get', 'k'], 1], 'icon-air', 'icon-surface'],
          'icon-rotate': ['get', 'h'],
          'icon-rotation-alignment': 'map',
          'icon-allow-overlap': true,
          'icon-ignore-placement': true,
          'icon-size': ['interpolate', ['linear'], ['zoom'], 3, 0.22, 6, 0.38, 9, 0.6, 13, 0.85],
        },
        paint: { 'icon-color': ['case', ['==', ['get', 'k'], 1], '#7ce0ff', '#f2f5f8'] },
      });
      map.addLayer({
        id: 'selected', type: 'circle', source: 'assets', filter: ['==', ['get', 'id'], ''],
        paint: { 'circle-radius': 12, 'circle-color': 'rgba(0,0,0,0)', 'circle-stroke-color': '#ffd400', 'circle-stroke-width': 3 },
      });

      map.on('click', 'assets', (e) => {
        const id = e.features?.[0]?.properties?.id;
        if (typeof id === 'string') handlers.current.onSelect(id);
      });
      map.on('mouseenter', 'assets', () => (map.getCanvas().style.cursor = 'pointer'));
      map.on('mouseleave', 'assets', () => (map.getCanvas().style.cursor = ''));
      ready.current = true;
      map.fire('zw-ready');
    });

    let viewportTimer: ReturnType<typeof setTimeout> | undefined;
    map.on('moveend', () => {
      clearTimeout(viewportTimer);
      viewportTimer = setTimeout(() => {
        const b = map.getBounds();
        const width = b.getEast() - b.getWest();
        const bbox: [number, number, number, number] =
          width >= 360
            ? [-180, Math.max(-90, b.getSouth()), 180, Math.min(90, b.getNorth())]
            : [wrapLon(b.getWest()), Math.max(-90, b.getSouth()), wrapLon(b.getEast()), Math.min(90, b.getNorth())];
        handlers.current.onViewport(bbox);
      }, 300);
    });

    // positions are pushed to the GPU on a fixed clock, never per update
    const flush = setInterval(() => {
      if (!ready.current || !store.takeDirty()) return;
      (map.getSource('assets') as GeoJSONSource | undefined)?.setData(featureCollection(store));
    }, FLUSH_MS);

    return () => {
      clearInterval(flush);
      clearTimeout(viewportTimer);
      ready.current = false;
      map.remove();
      mapRef.current = null;
    };
  }, [store]);

  // zones and their HTML labels
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const markers: maplibregl.Marker[] = [];
    const apply = () => {
      (map.getSource('zones') as GeoJSONSource | undefined)?.setData(zonesGeoJson(zones));
      for (const z of zones) {
        const el = document.createElement('div');
        el.className = `zone-label zone-${z.zoneClass.toLowerCase()}`;
        el.setAttribute('aria-hidden', 'true');
        el.textContent = `${z.name} · ${z.zoneClass === 'RESTRICTED' ? 'Restricted' : 'Advisory'}`;
        markers.push(new maplibregl.Marker({ element: el, anchor: 'bottom' }).setLngLat(labelPoint(z)).addTo(map));
      }
    };
    if (ready.current) apply();
    else map.once('zw-ready', apply);
    return () => markers.forEach((m) => m.remove());
  }, [zones]);

  // selection highlight
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const apply = () => map.setFilter('selected', ['==', ['get', 'id'], selected ?? '']);
    if (ready.current) apply();
    else map.once('zw-ready', apply);
  }, [selected]);

  // track of the selected asset
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const apply = () => {
      const coords = unwrapLine(track.map((t) => [t.lon, t.lat] as [number, number]));
      (map.getSource('track') as GeoJSONSource | undefined)?.setData({
        type: 'FeatureCollection',
        features: coords.length > 1 ? [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords } }] : [],
      });
    };
    if (ready.current) apply();
    else map.once('zw-ready', apply);
  }, [track]);

  useEffect(() => {
    if (focus) mapRef.current?.easeTo({ center: [focus.lon, focus.lat], duration: 400 });
  }, [focus]);

  return (
    <div className="map-wrap">
      <div
        ref={container}
        className="map"
      role="application"
        aria-label="Live map of assets and zones. Use arrow keys to pan and plus or minus to zoom. Use the Zones tab for a list of assets inside each zone."
      />
      <ul className="legend" aria-label="Map legend">
        <li><span className="sym sym-air" aria-hidden="true">✈</span> Air asset</li>
        <li><span className="sym sym-surface" aria-hidden="true">▲</span> Surface asset</li>
        <li><span className="swatch swatch-restricted" aria-hidden="true" /> Restricted zone</li>
        <li><span className="swatch swatch-advisory" aria-hidden="true" /> Advisory zone</li>
      </ul>
    </div>
  );
}
