import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api/client';
import { LiveStream, type ConnectionState } from '../api/stream';
import type { TrackPoint, Zone } from '../api/types';
import { MapView } from '../map/MapView';
import type { Session } from '../session';
import { AlertStore } from '../state/alerts';
import { PositionStore } from '../state/positions';
import { AlertPanel } from './AlertPanel';
import { Announcer } from './Announcer';
import { AssetDetail } from './AssetDetail';
import { Header } from './Header';
import { PerfMonitor } from './PerfMonitor';
import { ZoneTable } from './ZoneTable';

type Tab = 'alerts' | 'zones' | 'asset';
const WORLD: [number, number, number, number] = [-180, -90, 180, 90];

/**
 * Where state lives:
 *  - positions: PositionStore (mutable, outside React), drawn by the map on a 250 ms clock
 *  - alerts:    AlertStore (outside React, versioned), read with useSyncExternalStore, coalesced
 *  - UI state (tab, selection, filters, dialogs): React state in the components that own it
 *  - server truth: the API; after every (re)connect the console re-synchronises from it
 */
export function Console({ session, onSignOut }: { session: Session; onSignOut(reason?: string): void }) {
  const positions = useMemo(() => new PositionStore(), []);
  const alerts = useMemo(() => new AlertStore(), []);
  const [zones, setZones] = useState<Zone[]>([]);
  const [conn, setConn] = useState<{ state: ConnectionState; attempt: number; since: number | null }>({ state: 'connecting', attempt: 0, since: null });
  const [selected, setSelected] = useState<string | null>(null);
  const [track, setTrack] = useState<TrackPoint[]>([]);
  const [tab, setTab] = useState<Tab>('alerts');
  const [focus, setFocus] = useState<{ lon: number; lat: number; key: number } | null>(null);
  const [assetCount, setAssetCount] = useState(0);
  const viewportAbort = useRef<AbortController | null>(null);

  const resync = useCallback(() => {
    api.assets(WORLD, 5000).then((p) => {
      positions.applyRest(p.items);
      setAssetCount(positions.size);
    }).catch(() => {});
  }, [positions]);

  useEffect(() => {
    api.zones().then((r) => setZones(r.items)).catch(() => {});
    const stream = new LiveStream(() => api.getToken(), {
      onState: (state, d) => setConn({ state, attempt: d.attempt, since: d.since }),
      onConnected: () => resync(),
      onPositions: (items) => positions.applyTuples(items),
      onAlerts: (items) => alerts.applyChanges(items),
      onAlertsSnapshot: (items) => alerts.applySnapshot(items),
      onUnauthorized: () => onSignOut('Your session has expired. Please sign in again.'),
    });
    stream.start();
    const counter = setInterval(() => setAssetCount(positions.size), 5000);
    return () => {
      stream.stop();
      clearInterval(counter);
    };
  }, [positions, alerts, resync, onSignOut]);

  const onViewport = useCallback((bbox: [number, number, number, number]) => {
    viewportAbort.current?.abort();
    const ctl = new AbortController();
    viewportAbort.current = ctl;
    api.assets(bbox, 5000, ctl.signal).then((p) => positions.applyRest(p.items)).catch(() => {});
  }, [positions]);

  const select = useCallback((assetId: string) => {
    setSelected(assetId);
    setTab('asset');
    const p = positions.get(assetId);
    if (p) setFocus({ lon: p.lon, lat: p.lat, key: Date.now() });
  }, [positions]);

  const zoneNames = useMemo(() => new Map(zones.map((z) => [z.zoneId, z.name])), [zones]);

  return (
    <div className="console">
      <a href="#side-panel" className="skip">Skip to alert panel</a>
      <Header session={session} connection={conn} assetCount={assetCount} onSignOut={() => onSignOut()} />
      <main className="layout">
        <MapView store={positions} zones={zones} selected={selected} track={track} onSelect={select} onViewport={onViewport} focus={focus} />
        <aside id="side-panel" className="side" aria-label="Operator panel">
          <nav className="tabs main-tabs" aria-label="Panels">
            {(['alerts', 'zones', 'asset'] as Tab[]).map((t) => (
              <button key={t} type="button" aria-pressed={tab === t} onClick={() => setTab(t)}>
                {t === 'alerts' ? 'Alerts' : t === 'zones' ? 'Zones' : 'Asset'}
              </button>
            ))}
          </nav>
          <div hidden={tab !== 'alerts'}>
            <AlertPanel store={alerts} session={session} zoneNames={zoneNames} onSelectAsset={select} />
          </div>
          {tab === 'zones' && <ZoneTable onSelectAsset={select} />}
          {tab === 'asset' && <AssetDetail assetId={selected} store={positions} onSelect={select} onTrack={setTrack} />}
          <footer className="side-footer">
            <Announcer store={alerts} />
            <PerfMonitor />
          </footer>
        </aside>
      </main>
    </div>
  );
}
