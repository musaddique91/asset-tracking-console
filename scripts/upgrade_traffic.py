#!/usr/bin/env python3
"""Steady mixed traffic for a rolling upgrade: counts every failed request (standard library only).

  python3 scripts/upgrade_traffic.py --base-url http://localhost:8080 --feed-password '...' \
      --operator op01 --operator-password '...' --seconds 600

Threads: ingest (fresh reports, new reportIds, 100 lines per batch), viewport queries, alert pages,
track reads (which carry sourceType on v1.1.0), stats. A request fails when it gets a status
outside 2xx or a connection error. Ends with a summary; exit code 1 if anything failed.
Stop early with Ctrl+C (the summary is still printed).
"""
import argparse, http.client, json, random, threading, time, uuid
from datetime import datetime, timezone
from urllib.parse import urlparse

ap = argparse.ArgumentParser()
ap.add_argument("--base-url", default="http://localhost:8080")
ap.add_argument("--feed-user", default="feed")
ap.add_argument("--feed-password", required=True)
ap.add_argument("--operator", default="op01")
ap.add_argument("--operator-password", required=True)
ap.add_argument("--seconds", type=int, default=600)
a = ap.parse_args()
u = urlparse(a.base_url)
stop = threading.Event()
lock = threading.Lock()
stats = {}
failures = []


def conn():
    return http.client.HTTPConnection(u.hostname, u.port or 80, timeout=15)


def login(user, pw):
    c = conn()
    c.request("POST", "/api/v1/auth/login", json.dumps({"username": user, "password": pw}), {"Content-Type": "application/json"})
    r = c.getresponse()
    body = r.read()
    if r.status != 200:
        raise SystemExit("login failed for %s: %d" % (user, r.status))
    return json.loads(body)["accessToken"]


def record(kind, ok, detail=None):
    with lock:
        s = stats.setdefault(kind, [0, 0])
        s[0 if ok else 1] += 1
        if not ok and len(failures) < 50:
            line = "%s %s %s" % (datetime.now().strftime("%H:%M:%S.%f")[:12], kind, detail)
            failures.append(line)
            print("  FAILED", line, flush=True)


def loop(kind, token, make):
    c = conn()
    while not stop.is_set():
        method, path, body, ctype = make()
        headers = {"Authorization": "Bearer " + token}
        if ctype:
            headers["Content-Type"] = ctype
        try:
            c.request(method, path, body, headers)
            r = c.getresponse()
            r.read()
            record(kind, 200 <= r.status < 300, r.status)
        except (OSError, http.client.HTTPException) as e:
            record(kind, False, repr(e))
            c.close()
            c = conn()
        time.sleep(0.02)


feed = login(a.feed_user, a.feed_password)
op = login(a.operator, a.operator_password)
asset_ids = ["AST-%05d" % random.randint(1, 3000) for _ in range(50)]


def ingest():
    now = datetime.now(timezone.utc)
    lines = []
    for _ in range(100):
        lines.append(json.dumps({"reportId": "up-" + uuid.uuid4().hex[:24], "assetId": "UPG-%03d" % random.randint(1, 200),
                                 "kind": "SURFACE", "observedAt": now.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                                 "lat": round(random.uniform(31, 42), 5), "lon": round(random.uniform(167, 179), 5),
                                 "speedKn": 10.0, "headingDeg": 90.0, "source": random.choice(["SAT-1", "GND-2", "MOB-1", "XYZ"])}))
    return "POST", "/api/v1/reports/batch", "\n".join(lines) + "\n", "application/x-ndjson"


def viewport():
    lon = random.uniform(166.5, 172); lat = random.uniform(31, 37)
    return "GET", "/api/v1/assets?bbox=%.3f,%.3f,%.3f,%.3f&limit=1000" % (lon, lat, lon + 6, lat + 4), None, None


threads = [
    threading.Thread(target=loop, args=("ingest", feed, ingest)),
    threading.Thread(target=loop, args=("ingest", feed, ingest)),
    threading.Thread(target=loop, args=("viewport", op, viewport)),
    threading.Thread(target=loop, args=("viewport", op, viewport)),
    threading.Thread(target=loop, args=("alerts", op, lambda: ("GET", "/api/v1/alerts?status=OPEN&limit=50", None, None))),
    threading.Thread(target=loop, args=("track", op, lambda: ("GET", "/api/v1/assets/%s/track?limit=50" % random.choice(asset_ids), None, None))),
    threading.Thread(target=loop, args=("stats", op, lambda: ("GET", "/api/v1/stats", None, None))),
]
for t in threads:
    t.daemon = True
    t.start()
start = time.time()
try:
    while time.time() - start < a.seconds:
        time.sleep(5)
        with lock:
            total = sum(s[0] + s[1] for s in stats.values()); bad = sum(s[1] for s in stats.values())
        print("%5ds  requests=%d failed=%d" % (time.time() - start, total, bad), flush=True)
except KeyboardInterrupt:
    pass
stop.set()
time.sleep(1)
with lock:
    print(json.dumps({k: {"ok": v[0], "failed": v[1]} for k, v in stats.items()}))
    for f in failures:
        print("  FAILED", f)
    bad = sum(s[1] for s in stats.values())
print("TOTAL failed requests: %d" % bad)
raise SystemExit(1 if bad else 0)
