import { useEffect, useState } from 'react';

/**
 * Evidence for the responsiveness target: records every main-thread task longer than 50 ms
 * (Long Tasks API) and shows the longest one of the last minute.
 */
export function PerfMonitor() {
  const [longest, setLongest] = useState<{ ms: number; count: number }>({ ms: 0, count: 0 });

  useEffect(() => {
    if (typeof PerformanceObserver === 'undefined' || !PerformanceObserver.supportedEntryTypes?.includes('longtask')) return;
    const entries: { at: number; ms: number }[] = [];
    const obs = new PerformanceObserver((list) => {
      for (const e of list.getEntries()) entries.push({ at: performance.now(), ms: e.duration });
    });
    obs.observe({ type: 'longtask', buffered: true });
    const t = setInterval(() => {
      const cutoff = performance.now() - 60_000;
      while (entries.length && (entries[0]?.at ?? 0) < cutoff) entries.shift();
      setLongest({ ms: Math.round(Math.max(0, ...entries.map((e) => e.ms))), count: entries.length });
      (window as unknown as { __zwLongTasks?: unknown }).__zwLongTasks = [...entries];
    }, 2000);
    return () => {
      obs.disconnect();
      clearInterval(t);
    };
  }, []);

  return (
    <p className={`perf ${longest.ms > 200 ? 'error' : 'muted'}`}>
      Longest main-thread task, last 60 s: {longest.ms} ms ({longest.count} over 50 ms)
    </p>
  );
}
