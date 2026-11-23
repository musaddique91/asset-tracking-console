#!/usr/bin/env python3
"""Measures GET /api/v1/assets latency for viewport-sized boxes (standard library only).

  python3 scripts/measure_viewport.py --base-url http://localhost:8080 --username op01 --password '...'

Random boxes inside the feed's area, sized so that up to ~1,000 assets match; prints p50/p95/p99
and the number of matched assets. Run it while replay.py is running to measure under ingest load.
"""
import argparse, http.client, json, random, statistics, time
from urllib.parse import urlparse

ap = argparse.ArgumentParser()
ap.add_argument("--base-url", default="http://localhost:8080")
ap.add_argument("--username", default="op01")
ap.add_argument("--password", required=True)
ap.add_argument("--requests", type=int, default=500)
ap.add_argument("--limit", type=int, default=1000)
a = ap.parse_args()
u = urlparse(a.base_url)
c = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=10)
c.request("POST", "/api/v1/auth/login", json.dumps({"username": a.username, "password": a.password}), {"Content-Type": "application/json"})
token = json.loads(c.getresponse().read())["accessToken"]
rnd = random.Random(1)
lat_ms, counts, truncated = [], [], 0
for i in range(a.requests):
    w = rnd.uniform(3.0, 11.0); h = w * 0.7
    lon = rnd.uniform(166.5, max(166.6, 179.5 - w)); lat = rnd.uniform(30.5, max(30.6, 42.0 - h))
    path = "/api/v1/assets?bbox=%.4f,%.4f,%.4f,%.4f&limit=%d" % (lon, lat, lon + w, lat + h, a.limit)
    t = time.perf_counter()
    c.request("GET", path, headers={"Authorization": "Bearer " + token})
    r = c.getresponse(); body = r.read()
    lat_ms.append((time.perf_counter() - t) * 1000)
    d = json.loads(body); counts.append(len(d["items"])); truncated += d["truncated"]
lat_ms.sort()
q = lambda p: lat_ms[min(len(lat_ms) - 1, int(p * len(lat_ms)))]
print("requests=%d matched: mean=%d max=%d truncated=%d" % (a.requests, statistics.mean(counts), max(counts), truncated))
print("latency ms: p50=%.1f p95=%.1f p99=%.1f max=%.1f" % (q(0.5), q(0.95), q(0.99), lat_ms[-1]))
