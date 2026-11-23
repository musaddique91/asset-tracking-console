import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { ZoneOccupancy } from '../api/types';

/** The non-map way to see which assets are inside which zone. */
export function ZoneTable({ onSelectAsset }: { onSelectAsset(id: string): void }) {
  const [items, setItems] = useState<ZoneOccupancy[] | null>(null);
  const [updated, setUpdated] = useState<string>('');

  useEffect(() => {
    let alive = true;
    const load = () =>
      api.occupancy().then((r) => {
        if (!alive) return;
        setItems(r.items);
        setUpdated(new Date().toISOString().slice(11, 19) + 'Z');
      }).catch(() => {});
    load();
    const t = setInterval(load, 5000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, []);

  return (
    <section aria-labelledby="zones-title" className="zones">
      <h2 id="zones-title">Zones</h2>
      <p className="muted">Assets currently inside each zone. Refreshed every 5 seconds{updated && `, last at ${updated}`}.</p>
      {!items && <p>Loading…</p>}
      {items && (
        <table>
          <caption className="sr-only">Assets inside each zone</caption>
          <thead>
            <tr><th scope="col">Zone</th><th scope="col">Class</th><th scope="col">Assets inside</th></tr>
          </thead>
          <tbody>
            {items.map((z) => (
              <tr key={z.zoneId}>
                <th scope="row">{z.zoneId} {z.name}</th>
                <td>{z.zoneClass === 'RESTRICTED' ? 'Restricted' : 'Advisory'}</td>
                <td>
                  {z.count === 0 ? (
                    <span className="muted">none</span>
                  ) : (
                    <details>
                      <summary>{z.count} asset{z.count === 1 ? '' : 's'}</summary>
                      <ul className="chips">
                        {z.assets.map((a) => (
                          <li key={a.assetId}>
                            <button type="button" className="link" onClick={() => onSelectAsset(a.assetId)}>{a.assetId}</button>
                          </li>
                        ))}
                      </ul>
                      {z.truncated && <p className="muted">Showing the first {z.assets.length}.</p>}
                    </details>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
