# Console responsiveness at 5x replay

Setup: final offline bundle (v1.1.0 images, linux/amd64 under emulation on Apple Silicon), two instances
behind nginx; `replay.py --speed 5` (≈ 1,500 position updates per second); 3,000 assets on the map;
console in Chrome (DevTools performance trace, no CPU or network throttling).

Interaction: 45 s of continuous input from a script on the map canvas — 79 drag pans (20 mouse moves
each, 16 ms apart) and 79 zoom gestures (6 wheel steps each) — while live updates kept arriving.

| Measure | Result |
|---|---|
| Main-thread tasks in the trace (59 s) | 43,335 |
| Longest main-thread task | **28.9 ms** (next: 9.9, 9.2, 8.8, 8.7 ms) |
| Tasks over 50 ms (Long Tasks API, in the page) | **0** |
| Tasks over 200 ms (target) | **0** |
| Layout shift (CLS) | 0.00 |
| Connection indicator | Live throughout |

Why it holds: positions are kept outside React and pushed to MapLibre's GPU symbol layer on a fixed
250 ms clock (one `setData` per tick, not per update); alert changes are coalesced to at most four React
renders per second; no React component re-renders per position update.

The raw trace (`trace-5x.json.gz`, 68 MB) is not committed; it is reproduced with the steps above.
