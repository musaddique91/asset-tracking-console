import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { ApiError, api } from '../api/client';
import type { Alert } from '../api/types';
import type { Session } from '../session';
import type { AlertStore } from '../state/alerts';
import { ResolveDialog } from './ResolveDialog';

interface Props {
  store: AlertStore;
  session: Session;
  zoneNames: Map<string, string>;
  onSelectAsset(assetId: string): void;
}

type View = 'active' | 'mine' | 'resolved';
type Message = { kind: 'error' | 'info'; text: string };

const PAGE = 25;

function time(iso: string | null | undefined): string {
  return iso ? new Date(iso).toISOString().slice(11, 19) + 'Z' : '';
}

/** Turns a refusal into a sentence for the operator; a lost race is an expected outcome, not a failure. */
async function explain(e: unknown, alertId: string, me: string): Promise<{ text: string; fresh: Alert | null }> {
  let fresh: Alert | null = null;
  try {
    fresh = await api.alert(alertId);
  } catch {
    // keep the explanation even if the refresh fails
  }
  if (!(e instanceof ApiError)) return { text: 'The server could not be reached. Nothing was changed. Try again.', fresh };
  const owner = fresh?.claimedBy ?? 'another operator';
  switch (e.code) {
    case 'ALERT_ALREADY_CLAIMED':
      return { text: `${owner === me ? 'You' : owner} claimed this alert first. It is theirs now; nothing was changed for you.`, fresh };
    case 'NOT_ALERT_OWNER':
      return { text: `Only ${owner} or a supervisor can do this.`, fresh };
    case 'INVALID_STATE':
      return {
        text: fresh
          ? `This alert changed in the meantime: it is now ${fresh.status.toLowerCase()}${fresh.status === 'RESOLVED' && fresh.resolvedBy ? ` by ${fresh.resolvedBy}` : fresh.claimedBy ? ` by ${fresh.claimedBy}` : ''}. Nothing was changed.`
          : 'This alert changed in the meantime. Nothing was changed.',
        fresh,
      };
    case 'NOT_FOUND':
      return { text: 'This alert no longer exists.', fresh };
    case 'FORBIDDEN':
      return { text: 'Your role may not do this.', fresh };
    default:
      return { text: 'The server could not complete the request. Nothing was changed. Try again.', fresh };
  }
}

export function AlertPanel({ store, session, zoneNames, onSelectAsset }: Props) {
  const snap = useSyncExternalStore(store.subscribe, store.getSnapshot);
  const [view, setView] = useState<View>('active');
  const [zoneFilter, setZoneFilter] = useState('');
  const [assetFilter, setAssetFilter] = useState('');
  const [page, setPage] = useState(0);
  const [messages, setMessages] = useState<Map<string, Message>>(new Map());
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const [resolving, setResolving] = useState<Alert | null>(null);
  const [resolveError, setResolveError] = useState<string | null>(null);
  const [history, setHistory] = useState<{ items: Alert[]; cursor: string | null; loading: boolean }>({ items: [], cursor: null, loading: false });
  const headingRef = useRef<HTMLHeadingElement>(null);
  const opener = useRef<HTMLElement | null>(null);
  const me = session.username;
  const supervisor = session.role === 'SUPERVISOR';

  const setMessage = useCallback((id: string, m: Message | null) => {
    setMessages((prev) => {
      const next = new Map(prev);
      if (m) next.set(id, m);
      else next.delete(id);
      return next;
    });
  }, []);

  const loadHistory = useCallback(async (cursor: string | null) => {
    setHistory((h) => ({ ...h, loading: true }));
    try {
      const r = await api.alerts({ status: 'RESOLVED', limit: 100, cursor, zoneId: zoneFilter || undefined, assetId: assetFilter.trim() || undefined });
      setHistory((h) => ({ items: cursor ? [...h.items, ...r.items] : r.items, cursor: r.nextCursor, loading: false }));
    } catch {
      setHistory((h) => ({ ...h, loading: false }));
    }
  }, [zoneFilter, assetFilter]);

  useEffect(() => {
    if (view === 'resolved') void loadHistory(null);
  }, [view, loadHistory]);

  useEffect(() => setPage(0), [view, zoneFilter, assetFilter]);

  const rows = useMemo(() => {
    let list: Alert[];
    if (view === 'resolved') {
      const seen = new Set<string>();
      list = [...snap.resolved, ...history.items].filter((a) => (seen.has(a.alertId) ? false : (seen.add(a.alertId), true)));
    } else {
      list = view === 'mine' ? snap.active.filter((a) => a.claimedBy === me) : snap.active;
    }
    const asset = assetFilter.trim().toUpperCase();
    return list.filter((a) => (!zoneFilter || a.zoneId === zoneFilter) && (!asset || a.assetId.includes(asset)));
  }, [snap, view, me, zoneFilter, assetFilter, history.items]);

  const pages = Math.max(1, Math.ceil(rows.length / PAGE));
  const current = Math.min(page, pages - 1);
  const visible = rows.slice(current * PAGE, current * PAGE + PAGE);

  async function command(a: Alert, action: 'claim' | 'release') {
    const versionAtStart = store.version(a.alertId);
    setBusy((b) => new Set(b).add(a.alertId));
    setMessage(a.alertId, null);
    try {
      const updated = action === 'claim' ? await api.claim(a.alertId) : await api.release(a.alertId);
      store.applyLocal(updated, versionAtStart);
      setMessage(a.alertId, { kind: 'info', text: action === 'claim' ? 'You own this alert now.' : 'Released. The alert is open again.' });
    } catch (e) {
      const { text, fresh } = await explain(e, a.alertId, me);
      if (fresh) store.applyLocal(fresh, versionAtStart);
      setMessage(a.alertId, { kind: 'error', text });
    } finally {
      setBusy((b) => {
        const n = new Set(b);
        n.delete(a.alertId);
        return n;
      });
    }
  }

  async function resolve(note: string) {
    const a = resolving;
    if (!a) return;
    const versionAtStart = store.version(a.alertId);
    setBusy((b) => new Set(b).add(a.alertId));
    setResolveError(null);
    try {
      const updated = await api.resolve(a.alertId, note);
      store.applyLocal(updated, versionAtStart);
      setMessage(a.alertId, { kind: 'info', text: 'Resolved.' });
      closeDialog();
    } catch (e) {
      if (e instanceof ApiError && e.code === 'VALIDATION_FAILED') {
        setResolveError('The note must contain text and be at most 500 characters.');
      } else {
        const { text, fresh } = await explain(e, a.alertId, me);
        if (fresh) store.applyLocal(fresh, versionAtStart);
        setMessage(a.alertId, { kind: 'error', text });
        closeDialog();
      }
    } finally {
      setBusy((b) => {
        const n = new Set(b);
        n.delete(a.alertId);
        return n;
      });
    }
  }

  function closeDialog() {
    setResolving(null);
    setResolveError(null);
    // the row may have left the list (resolved): fall back to the panel heading
    const target = opener.current;
    requestAnimationFrame(() => (target && document.body.contains(target) ? target.focus() : headingRef.current?.focus()));
  }

  const zoneOptions = useMemo(() => [...zoneNames.entries()].sort((x, y) => x[0].localeCompare(y[0])), [zoneNames]);

  return (
    <section className="alerts" aria-labelledby="alerts-title">
      <h2 id="alerts-title" ref={headingRef} tabIndex={-1}>
        Alerts <span className="muted">({snap.active.length} active)</span>
      </h2>
      <div className="tabs" role="group" aria-label="Which alerts to show">
        {(['active', 'mine', 'resolved'] as View[]).map((v) => (
          <button key={v} type="button" aria-pressed={view === v} onClick={() => setView(v)}>
            {v === 'active' ? 'Active' : v === 'mine' ? 'Mine' : 'Resolved'}
          </button>
        ))}
      </div>
      <div className="filters">
        <label>
          Zone
          <select value={zoneFilter} onChange={(e) => setZoneFilter(e.target.value)}>
            <option value="">All zones</option>
            {zoneOptions.map(([id, name]) => (
              <option key={id} value={id}>
                {id} {name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Asset
          <input value={assetFilter} onChange={(e) => setAssetFilter(e.target.value)} placeholder="AST-…" />
        </label>
      </div>
      <p className="muted" aria-live="off">
        {rows.length} alert{rows.length === 1 ? '' : 's'}
        {pages > 1 ? `, page ${current + 1} of ${pages}` : ''}
      </p>
      <ul className="alert-list">
        {visible.map((a) => {
          const mine = a.claimedBy === me;
          const canWork = a.status === 'CLAIMED' && (mine || supervisor);
          const msg = messages.get(a.alertId);
          const isBusy = busy.has(a.alertId);
          const zone = zoneNames.get(a.zoneId) ?? a.zoneId;
          return (
            <li key={a.alertId} className={`alert alert-${a.status.toLowerCase()}`} aria-labelledby={`al-${a.alertId}`}>
              <div className="alert-head">
                <span className={`badge badge-${a.status.toLowerCase()}`}>{a.status}</span>
                <span id={`al-${a.alertId}`}>
                  <button type="button" className="link" onClick={() => onSelectAsset(a.assetId)} aria-label={`Show asset ${a.assetId} on the map`}>
                    {a.assetId}
                  </button>{' '}
                  in <strong>{zone}</strong>
                </span>
              </div>
              <div className="alert-meta muted">
                opened {time(a.openedAt)}
                {a.claimedBy && ` · owner ${a.claimedBy === me ? 'you' : a.claimedBy}`}
                {a.resolvedBy && ` · resolved by ${a.resolvedBy} ${time(a.resolvedAt)}`}
              </div>
              {a.resolutionNote && <p className="note">{a.resolutionNote}</p>}
              {a.status !== 'RESOLVED' && (
                <div className="actions">
                  {a.status === 'OPEN' && (
                    <button type="button" disabled={isBusy} onClick={() => void command(a, 'claim')}>
                      Claim<span className="sr-only"> alert for {a.assetId} in {zone}</span>
                    </button>
                  )}
                  {canWork && (
                    <>
                      <button type="button" className="secondary" disabled={isBusy} onClick={() => void command(a, 'release')}>
                        Release<span className="sr-only"> alert for {a.assetId}</span>
                      </button>
                      <button
                        type="button"
                        disabled={isBusy}
                        onClick={(e) => {
                          opener.current = e.currentTarget;
                          setResolving(a);
                        }}
                      >
                        Resolve…<span className="sr-only"> alert for {a.assetId}</span>
                      </button>
                    </>
                  )}
                </div>
              )}
              {msg && (
                <p className={msg.kind === 'error' ? 'error' : 'info'} role={msg.kind === 'error' ? 'alert' : 'status'}>
                  {msg.text}
                </p>
              )}
            </li>
          );
        })}
      </ul>
      {pages > 1 && (
        <nav className="pager" aria-label="Alert pages">
          <button type="button" className="secondary" disabled={current === 0} onClick={() => setPage(current - 1)}>
            Previous
          </button>
          <button type="button" className="secondary" disabled={current >= pages - 1} onClick={() => setPage(current + 1)}>
            Next
          </button>
        </nav>
      )}
      {view === 'resolved' && history.cursor && (
        <button type="button" className="secondary" disabled={history.loading} onClick={() => void loadHistory(history.cursor)}>
          {history.loading ? 'Loading…' : 'Load older resolved alerts'}
        </button>
      )}
      <ResolveDialog
        alert={resolving}
        zoneName={resolving ? (zoneNames.get(resolving.zoneId) ?? resolving.zoneId) : ''}
        error={resolveError}
        busy={resolving ? busy.has(resolving.alertId) : false}
        onCancel={closeDialog}
        onResolve={(note) => void resolve(note)}
      />
    </section>
  );
}
