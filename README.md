# Zone Watch

A position feed for about 3,000 moving assets comes in over HTTP, at least once and out of order,
on several connections at the same time. Zone Watch stores it, keeps every asset's current position
and track, raises an alert when an asset enters a restricted zone, and lets operators claim, release
and resolve those alerts on a live map. It runs with no internet connection: the delivery is an
offline bundle of container images.

- **Backend**: Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway, plain JDBC (`backend/`)
- **Console**: React 18, TypeScript (strict), MapLibre GL, Vite (`frontend/`)
- **Delivery**: Docker Compose — database, two API instances, nginx load balancer on :8080 (`deploy/`)

Documents: [INSTALL.md](INSTALL.md) · [docs/RUNBOOK.md](docs/RUNBOOK.md) ·
[docs/AIRGAP-BUILD.md](docs/AIRGAP-BUILD.md) · [docs/CR-1.md](docs/CR-1.md) (v1.1.0 change record) ·
[docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) (status
of every requirement) · [docs/PLAN.md](docs/PLAN.md) · contract: [pack/openapi.yaml](pack/openapi.yaml)

---

## 1. Running it

### Offline (the delivery)

See [INSTALL.md](INSTALL.md). In short: verify checksums, unpack, `docker load`, put the users file
at `zonewatch/config/users.json`, `docker compose … up -d --wait`. Console and API on port 8080.

### From source (online)

Requirements: Docker (with Compose), Java 21, Node 22, Python 3.9+ for the replay script.

```bash
# build the image (linux/amd64) from the working tree and pull the pinned base images
scripts/build-images.sh dev
# run it: put a users file (JSON array of {username, role, password}) in place first
cp /path/to/users.json deploy/config/users.json
cd deploy && ZW_BACKEND_1_VERSION=dev ZW_BACKEND_2_VERSION=dev ZW_MIGRATE_VERSION=dev ZW_FLYWAY_TARGET=latest \
  docker compose up -d --wait
# feed it
FEED_PASSWORD='…' python3 pack/replay.py --base-url http://localhost:8080 --file /path/to/feed.ndjson.gz --speed 5
```

`ZW_HTTP_PORT=18080` publishes on another port if 8080 is taken.

### Development

- Backend from the IDE: main class `com.zonewatch.ZoneWatchApplication`. Local settings go in
  `backend/config/local.yml` (git-ignored, see `local.yml.example`): datasource, `spring.flyway.enabled: true`,
  `zw.users-file`. Environment variables (`ZW_DB_URL`, `ZW_DB_USER`, `ZW_DB_PASSWORD`, `ZW_USERS_FILE`, …) override it.
- Console: `cd frontend && npm ci && npm run dev` → http://localhost:5173. The dev server proxies the API to
  `ZW_API` from `frontend/.env` (default `http://localhost:8080`).

### Tests

```bash
cd backend && ./mvnw verify        # 56 unit + 45 integration tests; needs Docker (Testcontainers PostgreSQL)
cd frontend && npm test            # unit tests (merge rules, antimeridian helpers)
cd frontend && npm run typecheck   # TypeScript strict
```

Reports: `backend/target/surefire-reports`, `backend/target/failsafe-reports`.

---

## 2. Architecture and data flow

```
 feed ──POST /reports/batch──┐                                  ┌── console (browser)
                             ▼                                  │   GET /assets, /alerts, /zones …
                    ┌────────────────┐  proxy, failover         │   GET /stream  (server-sent events)
                    │ nginx  :8080   │◄─────────────────────────┘
                    └──────┬─────────┘
             ┌─────────────┴─────────────┐
     ┌───────▼───────┐           ┌───────▼───────┐   each instance: API + console static files
     │  backend-1    │           │  backend-2    │   + change poller → its own live clients
     └───────┬───────┘           └───────┬───────┘
             └─────────────┬─────────────┘
                   ┌───────▼───────┐
                   │ PostgreSQL 16 │  all shared state, all rule enforcement
                   └───────────────┘
```

**A report, from the HTTP request to the browser.** `IngestController` reads at most 5 MB and
1,000 non-empty lines. `ReportValidator` judges each line alone (no database). `IngestService` then
runs one transaction per batch:

1. `INSERT INTO report … ON CONFLICT (report_id) DO NOTHING RETURNING report_id` — only rows really
   inserted go further (R1).
2. New assets get their position row (`INSERT … ON CONFLICT DO NOTHING`, sorted); then
   `SELECT … FROM asset_position WHERE asset_id = ANY(?) ORDER BY asset_id FOR UPDATE` locks every
   asset of the batch in one fixed order, so the two instances serialise per asset without deadlock.
3. Per asset, the accepted reports are applied in event-time order `(observedAt, reportId)`. A report
   newer than the current position moves it and is tested against the restricted zones; an older one
   is late and stays in the track only (R2).
4. Entries into restricted zones open alerts:
   `INSERT INTO alert … ON CONFLICT (asset_id, zone_id) WHERE status IN ('OPEN','CLAIMED') DO NOTHING`
   with the OPENED audit row in the same statement (R3, R5).
5. Rejected lines (`rejected_line`), conflicting re-deliveries (`report_conflict`) and the batch totals
   (`ingest_batch`) are written; commit.

A deadlock or serialisation failure rolls back the batch, which is retried; every step is idempotent.
Each instance polls `asset_position` / `alert` for rows changed in the last 2 s (overlap window,
de-duplicated by a per-row version) every 250 ms and pushes them to its own SSE clients — so a change
made through either instance reaches every console. The console merges positions by
`(observedAt, reportId)` and alerts by version, so order of arrival never matters.

**A claim, from the click to the audit trail.** The console calls `POST /alerts/{id}/claim`.
`AlertService.claim` (one transaction) runs `SELECT … FROM alert WHERE alert_id = ? FOR UPDATE`,
decides on the locked row (OPEN → claim; CLAIMED by the caller → 200 unchanged; by someone else →
409 `ALERT_ALREADY_CLAIMED`; RESOLVED → 409 `INVALID_STATE`), updates it and inserts the CLAIMED audit
row, then commits. The change poller picks it up and every console updates; the losing operator's
console explains who won.

---

## 3. The rules and how they are enforced

| Rule | Mechanism (where it cannot be bypassed) | Tests |
|---|---|---|
| **R1** exactly-once effect | primary key on `report.report_id` + `ON CONFLICT DO NOTHING RETURNING`; only returned rows move positions or open alerts | `IngestRulesIT.r1_sameBatchDeliveredInParallelManyTimesIsStoredOnce` (12 parallel deliveries), `r1_redeliveryLaterChangesNothing`, `r1_duplicateWithinOneRequest…`, `r1_sameReportIdWithDifferentContent…` |
| **R2** event-time order | row lock on `asset_position` in asset order; comparison on `(observed_at, report_id)` under the lock | `r2_shuffledParallelDeliveryEndsAtTheNewestReport` (400 reports, 40 parallel batches), `r2_lateReportIsStoredButNeverMovesThePositionNorAlerts`, `r2_equalObservedAt…`, `r2_reportsInsideOneBatch…` |
| **R3** one active alert | partial unique index `alert_one_active_idx (asset_id, zone_id) WHERE status IN ('OPEN','CLAIMED')` | `r3_concurrentEntriesNeverCreateASecondActiveAlert`, `r3_afterResolve…`, `r3_theDatabaseRefusesASecondActiveAlert` |
| **R4** one owner | `SELECT … FOR UPDATE` on the alert row; check constraints owner ⇔ CLAIMED | `AlertWorkflowIT.r4_twentyOperatorsClaimAtTheSameMomentAndExactlyOneWins` |
| **R5** audit trail | audit row written in the same transaction as the change; trigger refuses UPDATE, DELETE, TRUNCATE on `alert_audit` | `r5_auditTrailIsAppendOnlyInTheDatabase`, `r5_underConcurrentCommandsTheAuditReplaysToTheFinalState…` |
| **R6** bad input isolated | each line validated on its own before the transaction; rejects stored in `rejected_line` (raw, code, detail, time, request id) and readable via `GET /api/v1/rejections` | `r6_badLinesAreRejectedOneByOne…`, `r6_aBatchOfOnlyBadLines…InParallel…`, `r6_aStringThatPostgresCannotStore…`, `ReportValidatorTest` |
| Alert rule | `ZoneShape`: ray casting with holes, multipolygons, boundary = inside, antimeridian unwrap; transition-based (outside or no position → inside) | `ZoneShapeTest`, `IngestRulesIT.alertRule_*` |

All rules are enforced in PostgreSQL, so they hold with any number of instances; nothing relies on
memory inside one instance.

---

## 4. Five decisions and the alternatives I rejected

1. **The database enforces the rules.** Unique key, partial unique index, row locks and an
   append-only trigger. *Rejected:* application-level locks or a distributed lock (Redis) — one more
   component to operate, and an instance-local lock does not protect the other instance; optimistic
   retries on R4 — they work but turn a race into repeated work and a harder explanation.
2. **Set-based ingest with plain JDBC.** Each batch is ~6 statements over arrays (`unnest`), not one
   per line. Measured 5,064 lines/s against a 2,000 target. *Rejected:* JPA `save()` per report — a
   select and an insert per row, and a duplicate key aborts the whole transaction (R6); a queue that
   accepts first and processes later — the contract requires the per-line outcome in the 200 answer.
3. **Live updates by polling the database, no broker.** Each instance reads changed rows (2 s overlap
   window, version de-duplication) and serves its own SSE clients; the console re-synchronises on
   every reconnect. *Rejected:* Kafka/Redis pub-sub (extra component in the air gap), PostgreSQL
   `LISTEN/NOTIFY` (8 KB payloads, lost while an instance is down), sticky sessions (a restart still
   loses updates).
4. **Geometry in Java, no PostGIS.** Twelve zones are reference data; containment is a pure function
   evaluated in memory on both instances, and the viewport uses a GiST index on the built-in `point`
   type. *Rejected:* PostGIS — a larger image and a PostgreSQL-only dependency for a problem this small,
   and it would not have fixed the antimeridian zone on its own.
5. **One image, three modes; every instance also serves the console.** `serve`, `migrate` (one-shot
   Flyway) and, from v1.1.0, the backfill job. nginx re-resolves instance names, so instances can be
   replaced one at a time without touching the load balancer. *Rejected:* migrations on application
   start (two instances racing, no control of when a step runs), a separate console container (one
   more thing to version during a rolling upgrade), an nginx image per release (replacing the load
   balancer drops connections).

Also deliberate: positions live outside React (`PositionStore`) and reach the GPU on a 250 ms clock
as one MapLibre symbol layer; alerts are versioned and coalesced to four renders per second. The map
has no basemap: no tiles, sprites, glyphs or fonts are fetched.

---

## 5. Data findings and what I did

| Where | Finding | Decision |
|---|---|---|
| zones | **Z-08 crosses the 180° meridian** (`179.3 … -179.4`). Read naively it covers 358.7° of longitude — nearly every asset would be "inside". | Rings with a jump > 180° are unwrapped (+360) and points are tested at `lon` and `lon + 360`. Tests cover inside both sides and outside far away. |
| zones | Z-02 is wound clockwise (RFC 7946 wants counter-clockwise outer rings). | Even-odd ray casting is winding-independent; noted. |
| zones | Z-03 has a hole; Z-06 is a MultiPolygon; Z-04/Z-05 overlap; Z-07 is concave; Z-11 is 3 km wide; Z-12 lies west of 180°. | Holes excluded, every part counts, overlaps raise one alert per zone; boundary counts as inside. |
| feed | 8,968 repeated reportIds (re-delivery). | Duplicates (R1). |
| feed | **5 reportIds re-delivered with a different position** (+0.5° lat, −0.5° lon). | The contract says same id = same report: the first stored wins; the conflicting copy is kept in `report_conflict` and counted (`zw_ingest_conflicting_duplicates`). |
| feed | 261 truncated JSON lines, 20 `"heartbeat"` lines. | `MALFORMED_JSON`. |
| feed | Missing/null/empty required fields (≈ 230). | `MISSING_FIELD`. |
| feed | Coordinates as `"N/A"`, `"--"`, `{}`, `[]`, `true`; out of range (`lat 210.6`, `lon -761.2`). | `INVALID_COORDINATE`; a numeric string is not a number. |
| feed | Timestamps `yesterday`, `02/11/2026 06:00:05`, `2026-13-45T99:00:00Z`, epoch millis, local time without zone. | `INVALID_TIMESTAMP`: a time without zone is not an instant, and guessing UTC would rewrite data. |
| feed | Offsets such as `+04:00`. | Accepted, stored as the same instant in UTC. |
| feed | **`2031-01-01T00:00:00Z`** (future sentinel). | Rejected as `INVALID_TIMESTAMP` beyond `ZW_MAX_FUTURE_SKEW` (1 h): accepted, it would be the asset's current position for five years and hide every real report. |
| feed | `1970-01-01T00:00:00Z` (zero sentinel). | Accepted as a valid but late report — it changes nothing but the track. |
| feed | Asset ids `ast-00017`, `AST-éè`, `<script>alert(1)</script>`, `AST-1'; DROP TABLE reports;--`. | `INVALID_FIELD`. All SQL is parameterised; the console renders text only. |
| feed | Extra fields `altM`, `meta`. | Ignored. |
| feed | Unknown source `XYZ-9`, missing source. | Accepted (source is optional); CR-1 maps them to `UNKNOWN`. |
| feed | `headingDeg` 360, `speedKn` −1. | Accepted and stored as received (no rule forbids them); the map treats 360 as 0. |
| feed | ≈ 11,600 reports older than an already delivered one of the same asset. | Late reports (R2). |
| feed | 51 extra asset ids (`AST-030xx`) appear only in bad lines. | They never become assets: 3,000 assets after a full replay. |

Full replay of the supplied feed: 225,748 lines → 215,962 accepted, 8,920 duplicates, 866 rejected
(MALFORMED_JSON 281, INVALID_COORDINATE 222, MISSING_FIELD 220, INVALID_TIMESTAMP 113, INVALID_FIELD 30),
3,000 assets, 253 alerts. A second replay changed nothing.

---

## 6. Evidence

All on a MacBook (Apple Silicon, 18 cores) with the **linux/amd64 images running under emulation**,
the full compose stack (two instances behind nginx).

**Ingest.** `replay.py --speed max` (4 connections, 250 lines per request): **5,064 lines/s**, 0 HTTP
errors, 0 retries. Second replay (8 connections): 67,221 lines/s, 0 accepted, nothing changed
(reports, assets, alerts, audit rows and the sum of position versions identical). At `--speed 5`:
1,500 lines/s sustained while the console was open.

**Viewport query.** `scripts/measure_viewport.py`, 500 random boxes, up to 1,000 matches
(mean 714, 192 truncated at 1,000): **p50 4.6 ms, p95 7.7 ms, p99 10.9 ms**. Plan:

```
Limit -> Sort (asset_id)
  -> Bitmap Heap Scan on asset_position  Recheck Cond: (point(lon, lat) <@ box)
     -> Bitmap Index Scan on asset_position_point_idx
Execution Time: 1.418 ms   (350 rows)
```

**Console.** With 3,000 assets at 5x, 45 s of continuous panning and zooming (79 drags, 79 zoom
gestures), Chrome performance trace: 43,335 main-thread tasks, **longest 28.9 ms, none over 50 ms**
(target: none over 200 ms) — [docs/evidence/responsiveness-5x.md](docs/evidence/responsiveness-5x.md).
At initial load, two tasks of 149 ms and 178 ms (map creation and the first snapshot).
The console shows its own long-task meter (bottom of the side panel). Only the console's own origin
is requested (checked with the resource timing list). After the load balancer was paused for 33 s the
header showed "Live updates interrupted", then "Live" again without a reload, and the active-alert
count matched the server exactly.

**Accessibility (built).** Keyboard-only operation (native controls, WAI-ARIA tabs and menu button,
modal dialog with focus return), visible focus ring in both themes, new-alert announcements
summarised at most every 10 s with an off switch, a lost race announced with `role="alert"`, asset
kind by shape and colour, reduced motion respected, and the **Zones** tab as the non-map view of which
assets are inside which zone.

**Tests.** `./mvnw verify`: 56 unit + 45 integration tests, all passing.

**Rolling upgrade v1.0.0 → v1.1.0 under traffic** (CR-1, see below), from the offline bundle with a
different users file, the six INSTALL (c) commands run back to back: **0 failed requests**; plus 5 cycles of
replacing both instances back to back under traffic: 0 failed. (An earlier run exposed a load-balancer race,
fixed — see section 11, case 6.)

**Accessibility audit.** Lighthouse accessibility **100** on the sign-in page and on the signed-in
console in both themes; reports in `docs/evidence/`.

---

## 7. Version 1.1.0 — change request CR-1

Every report gets `source_type` (SAT → SATELLITE, GND → GROUND, MOB → MOBILE, else UNKNOWN); the
track returns it as `sourceType` and filters on `?sourceType=`; the console's asset detail shows it;
`openapi.yaml` is 1.1.0. Delivered against a live database with an **expand → backfill → contract**
migration so v1.0.0 and v1.1.0 run side by side with no long lock:

- `V2` adds a nullable column, the derivation function, a `BEFORE INSERT` trigger (fills the value for
  v1.0.0 writers) and `NOT VALID` checks — catalogue changes only, guarded by `lock_timeout`;
- instances are replaced one at a time;
- `docker compose run --rm backfill` fills old rows in throttled keyset batches (resumable);
- `V3` validates without blocking writes; `V4` sets `NOT NULL` without a scan.

Rehearsed on a loaded v1.0.0 stack with mixed traffic: 35,115 requests, 0 failed; backfill ≈ 33,000
rows/s with its default throttle (≈ 4–8 h for 500 million rows). Steps, locks, verification, rollback
and the backfill plan: [docs/CR-1.md](docs/CR-1.md). Commands: [INSTALL.md](INSTALL.md) (c).
Test: `Cr1MigrationIT` runs the whole sequence on a v1.0.0 database, including v1.0.0 writes after the
last step (the rollback path). Traffic generator: `scripts/upgrade_traffic.py`.

---

## 8. Design on another database (MariaDB / MySQL)

PostgreSQL-specific parts and their equivalents:

- **`ON CONFLICT DO NOTHING RETURNING`** (R1) → `INSERT IGNORE` (or `ON DUPLICATE KEY UPDATE id = id`)
  followed by `SELECT … WHERE report_id IN (…)` comparing a batch marker, since MariaDB has no
  `RETURNING` for inserts that are ignored (MariaDB 10.5+ has `INSERT … RETURNING`, but not for ignored rows).
- **Partial unique index** (R3) → MariaDB has none. Use a generated column
  `active_key = IF(status IN ('OPEN','CLAIMED'), CONCAT(asset_id,'|',zone_id), NULL)` with a unique
  index: NULLs do not collide, so only active alerts are unique.
- **`SELECT … FOR UPDATE`** (R2, R4) works the same in InnoDB; with REPEATABLE READ gap locks, keep
  the sorted lock order and use READ COMMITTED for the batch transaction.
- **Append-only trigger** (R5) → `BEFORE UPDATE/DELETE` triggers with `SIGNAL SQLSTATE '45000'`;
  TRUNCATE cannot be trapped by a trigger, so revoke `DROP` from the application user instead.
- **`unnest` arrays** → multi-row `VALUES` lists or JSON_TABLE; **GiST on `point`** → a `POINT`
  column with a `SPATIAL` index and `MBRContains`; **`timestamptz`** → `TIMESTAMP(3)` with the
  session time zone fixed to `+00:00`; **Flyway** supports MariaDB.

---

## 9. Limitations and incomplete areas

- **Live stream window.** A change whose transaction commits more than 2 s after it was written
  would not be pushed; the next change of that asset or alert, or a reconnect, corrects the console.
  Batches take ~50–200 ms, so this has not been seen.
- **Alert paging during writes.** The cursor is the alert sequence; an alert created by a transaction
  that commits after a later one may be skipped by a page that has already passed it. The contract's
  "every alert exactly once" holds for a quiescent system.
- **Console scale.** The console loads all positions (`limit=5000`) and receives every update; past
  ~5,000 assets it should subscribe by viewport.
- **Measurements** are on emulated amd64 on a laptop; native numbers will differ (higher).
- **Rejected input** is stored truncated to 8 KB per line.
- **No TLS** between browser and load balancer: inside an air-gapped site it would be terminated by
  the site's own proxy or added to nginx with a site certificate.
- **No rate limiting** of login attempts.
- **CR-1 trigger stays.** The trigger that fills `source_type` for v1.0.0 writers is kept after the
  upgrade (it is what makes a rollback to v1.0.0 safe); a later release can drop it once no v1.0.0 can return.
- **Not done (optional):** Kubernetes manifests, CI pipeline, basemap.

---

## 10. Actual effort

_To be completed by the candidate: hours spent, and on what._

---

## 11. AI-assisted development

Tools: **Claude Code** (Anthropic, Claude Opus model) in the desktop app, working in this repository:
reading the brief and the pack, analysing the data, writing code, tests and documents, running the
build, tests, containers and the replay, and checking the console in a browser. Every change was
reviewed, run and tested; I can explain each part.

Instructions given to the agent: the brief, the candidate pack, and direction in conversation; the
agent wrote [docs/PLAN.md](docs/PLAN.md) first and followed it. No separate rules file was used.

Cases where generated output was wrong, unsafe or not good enough:

1. **A logged password.** The first container run wrote Spring Security's *generated security
   password* to the log. Found by searching the container logs for "password" after start-up. Fixed by
   excluding `UserDetailsServiceAutoConfiguration` (users come only from the users file).
2. **A secret committed by accident.** A commit picked up local edits to `application.yml`: a
   database password default and a machine-specific path. Besides being a secret in the repository, it
   replaced the generated password of the compose stack, so the containers could not connect. Found by
   reviewing `git status`/history; fixed by restoring environment-only defaults and moving developer
   values to a git-ignored `local.yml` (b5e9883).
3. **Migration mode did not start.** The one-shot `migrate` container failed because the security
   configuration required a web context. Found at the first compose start; fixed by making web and
   security configuration conditional on a web application.
4. **Infinite recursion in a row mapper.** A generated `AlertRepository` mapper called itself through an
   overload. Found in review before it ran; rewritten as a single mapper with a column offset.
5. **A 413 the client could not read.** Rejecting a large body by `Content-Length` before reading it
   made Tomcat close the connection; the client saw a broken response instead of the problem body.
   Found by the contract test; fixed by a bounded read of at most 5 MB + 1 byte.
6. **A rolling upgrade that dropped requests.** The first rehearsal of the upgrade passed with 0 failed
   requests, but repeating it from the offline bundle gave 227 failures in a few seconds. Cause: a
   recreated instance got a new IP address, and nginx (which re-resolved names every 2 s and took a failed
   instance out for 3 s) had no usable instance when the second one was stopped right after the first
   came back. Found by repeating the test with the steps back to back and reading nginx's own log
   (`upstream: "backends"`); fixed with fixed instance addresses on the compose network, no re-resolution
   and `max_fails=0`, then verified with 5 cycles of back-to-back replacement under traffic: 0 failures.
   Lesson kept: one clean run of a race is not evidence.
7. **Theme colours one step behind.** The map read its colours in an effect that ran before the page's
   theme attribute changed (child effects run first). Found by switching themes in the browser; fixed
   by applying the theme synchronously.
