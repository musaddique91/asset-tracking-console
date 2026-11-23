import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { ConnectionState } from '../api/stream';
import type { Stats } from '../api/types';
import type { Session } from '../session';
import { Radar } from './Radar';
import { UserMenu } from './UserMenu';

interface Props {
  session: Session;
  connection: { state: ConnectionState; attempt: number; since: number | null };
  assetCount: number;
  onSignOut(): void;
}

export function Header({ session, connection, assetCount, onSignOut }: Props) {
  const [stats, setStats] = useState<Stats | null>(null);
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    let alive = true;
    const load = () => api.stats().then((s) => alive && setStats(s)).catch(() => {});
    load();
    const t = setInterval(() => {
      load();
      setNow(Date.now());
    }, 5000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, []);

  const interrupted = connection.state === 'reconnecting';
  const seconds = connection.since ? Math.round((now - connection.since) / 1000) : 0;
  const label =
    connection.state === 'live' ? 'Live' : connection.state === 'connecting' ? 'Connecting…' : interrupted ? 'Live updates interrupted' : 'Stopped';

  return (
    <header className="topbar">
      <h1>Zone Watch</h1>
      <div className={`conn conn-${connection.state}`} role="status" aria-live="polite">
        <Radar size={22} active={connection.state === 'live'} className="radar-mini" />
        {label}
        {interrupted && <span className="muted"> — reconnecting{seconds > 0 ? ` (${seconds}s)` : ''}. The map and alerts may be out of date.</span>}
      </div>
      <dl className="stats" aria-label="System totals">
        <div><dt>Assets</dt><dd>{(stats?.assets ?? assetCount).toLocaleString()}</dd></div>
        <div><dt>Open</dt><dd>{stats?.alerts.open.toLocaleString() ?? '–'}</dd></div>
        <div><dt>Claimed</dt><dd>{stats?.alerts.claimed.toLocaleString() ?? '–'}</dd></div>
        <div><dt>Reports</dt><dd>{stats?.accepted.toLocaleString() ?? '–'}</dd></div>
        <div><dt>Rejected</dt><dd>{stats?.rejected.toLocaleString() ?? '–'}</dd></div>
      </dl>
      <UserMenu session={session} onSignOut={onSignOut} />
    </header>
  );
}
