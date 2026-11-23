import { useEffect, useState, type FormEvent } from 'react';
import { ApiError, api } from '../api/client';
import type { AssetPosition, TrackPoint } from '../api/types';
import type { PositionStore } from '../state/positions';

interface Props {
  assetId: string | null;
  store: PositionStore;
  onSelect(assetId: string): void;
  onTrack(points: TrackPoint[]): void;
}

const TRACK_WINDOW_MS = 10 * 60 * 1000;

export function AssetDetail({ assetId, store, onSelect, onTrack }: Props) {
  const [asset, setAsset] = useState<AssetPosition | null>(null);
  const [track, setTrack] = useState<TrackPoint[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [tick, setTick] = useState(0);

  // refresh when this asset moves (throttled), so the detail follows the live position
  useEffect(() => {
    if (!assetId) return;
    let pending = false;
    const off = store.onChanged((ids) => {
      if (ids.has(assetId) && !pending) {
        pending = true;
        setTimeout(() => {
          pending = false;
          setTick((t) => t + 1);
        }, 2000);
      }
    });
    return off;
  }, [assetId, store]);

  useEffect(() => {
    if (!assetId) {
      setAsset(null);
      setTrack([]);
      onTrack([]);
      return;
    }
    let alive = true;
    (async () => {
      try {
        const a = await api.asset(assetId);
        const from = new Date(Date.parse(a.observedAt) - TRACK_WINDOW_MS).toISOString();
        const t = await api.track(assetId, from, 1000);
        if (!alive) return;
        setAsset(a);
        setTrack(t.items);
        onTrack(t.items);
        setError(null);
      } catch (e) {
        if (!alive) return;
        setError(e instanceof ApiError && e.status === 404 ? `No asset ${assetId}.` : 'Could not load the asset.');
        setAsset(null);
        setTrack([]);
        onTrack([]);
      }
    })();
    return () => {
      alive = false;
    };
  }, [assetId, tick, onTrack]);

  function find(e: FormEvent) {
    e.preventDefault();
    const id = query.trim().toUpperCase();
    if (id) onSelect(id);
  }

  const recent = track.slice(-8).reverse();
  return (
    <section aria-labelledby="asset-title" className="asset">
      <h2 id="asset-title">Asset</h2>
      <form onSubmit={find} className="inline">
        <label htmlFor="asset-search">Find asset</label>
        <input id="asset-search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="AST-00417" />
        <button type="submit" className="secondary">Show</button>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
      {!assetId && <p className="muted">Select an asset on the map, in an alert or in the zone list.</p>}
      {asset && (
        <>
          <h3>{asset.assetId}</h3>
          <dl className="kv">
            <dt>Kind</dt><dd>{asset.kind ?? '–'}</dd>
            <dt>Observed</dt><dd>{asset.observedAt}</dd>
            <dt>Position</dt><dd>{asset.lat.toFixed(5)}, {asset.lon.toFixed(5)}</dd>
            <dt>Speed</dt><dd>{asset.speedKn ?? '–'} kn</dd>
            <dt>Heading</dt><dd>{asset.headingDeg ?? '–'}°</dd>
            <dt>Source</dt><dd>{asset.source ?? '–'}{recent[0]?.sourceType ? ` (${recent[0].sourceType})` : ''}</dd>
            <dt>Report</dt><dd className="mono">{asset.reportId}</dd>
          </dl>
          <h3>Recent track <span className="muted">({track.length} points, last 10 minutes)</span></h3>
          <table className="track">
            <caption className="sr-only">Most recent reports of {asset.assetId}, newest first</caption>
            <thead>
              <tr><th scope="col">Observed (UTC)</th><th scope="col">Lat</th><th scope="col">Lon</th><th scope="col">Source</th></tr>
            </thead>
            <tbody>
              {recent.map((p) => (
                <tr key={p.reportId}>
                  <td>{p.observedAt.slice(11, 23)}</td>
                  <td>{p.lat.toFixed(4)}</td>
                  <td>{p.lon.toFixed(4)}</td>
                  <td>{p.source ?? '–'}{p.sourceType ? ` · ${p.sourceType}` : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
