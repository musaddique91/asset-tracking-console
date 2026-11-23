import { useEffect, useState } from 'react';
import type { AlertStore } from '../state/alerts';

const INTERVAL_MS = 10_000;

/**
 * Screen-reader announcements for new alerts, summarised at most once every 10 seconds so a busy
 * feed never floods the user. Can be switched off.
 */
export function Announcer({ store }: { store: AlertStore }) {
  const [enabled, setEnabled] = useState(true);
  const [text, setText] = useState('');

  useEffect(() => {
    const t = setInterval(() => {
      const n = store.takeNewCount();
      if (!enabled || n === 0) return;
      const active = store.getSnapshot().active.length;
      setText(`${n} new alert${n === 1 ? '' : 's'}. ${active} active in total.`);
    }, INTERVAL_MS);
    return () => clearInterval(t);
  }, [store, enabled]);

  return (
    <div className="announcer">
      <label>
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> Announce new alerts
      </label>
      <div className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {text}
      </div>
    </div>
  );
}
