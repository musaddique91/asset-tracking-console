# Implementation plan

Source of truth: the candidate brief (v1.1) and `openapi.yaml`. AI-assisted development is
permitted with disclosure (brief section 12), so the README must carry a disclosure section.

## 1. Repository layout

```
backend/     Spring Boot 3.5, Java 21, Maven, JDBC (no ORM schema), Flyway
frontend/    React 18 + TypeScript strict + Vite + MapLibre GL (no basemap, no glyphs)
deploy/      compose.yaml, nginx.conf, env/*.env (image tags + migration targets)
scripts/     build-images.sh, build-bundle.sh, measure-viewport.sh
docs/        PLAN.md, CR-1.md, RUNBOOK.md, AIRGAP-BUILD.md
pack/        the candidate-pack files the build and tests need (zones, openapi, replay.py)
README.md, INSTALL.md
```

## 2. Existing code — what changes

| Existing | Problem | Action |
|---|---|---|
| `application.yml` JWT secret | secret in repo | remove; secret from env or generated once into DB |
| `users-seed.json` in resources | passwords in repo / image | delete; read from mounted `ZW_USERS_FILE` |
| `ddl-auto: update`, JPA entity | convention: ORM never alters schema | drop JPA, Flyway migrations, `JdbcClient` |
| `AuthResponse(token)` | contract wants `accessToken, tokenType, expiresInSec, user` | rewrite |
| `ReportingSource` enum | does not compile (nested enum) | delete |
| JWT filter hits DB per request | slow on ingest path | trust signed claims (sub + role) |

## 3. Schema (Flyway V1)

- `app_user(username pk, password_hash, role)`
- `app_secret(name pk, value)` — JWT key generated once, shared by both instances
- `zone(zone_id pk, name, zone_class, geojson)` — loaded from zones.geojson at start-up (upsert)
- `report(report_id pk, asset_id, kind, observed_at timestamptz, lat, lon, speed_kn, heading_deg, source, received_at)`
  index `(asset_id, observed_at, report_id)` for the track
- `asset_position(asset_id pk, report_id, observed_at, lat, lon, ..., version, changed_at)`
  GiST index on `point(lon,lat)` for bbox; index on `changed_at` for the live stream
- `alert(alert_id pk, asset_id, zone_id, status, opened_at, trigger_*, claimed_*, resolved_*, note)`
  **partial unique index `(asset_id, zone_id) WHERE status IN ('OPEN','CLAIMED')` → R3**
- `alert_audit(...)` append-only, UPDATE/DELETE/TRUNCATE blocked by trigger → R5
- `rejected_line(id, received_at, request_id, line_no, code, detail, raw)` → R6
- `report_conflict(...)` same reportId re-delivered with different content (data finding)
- `ingest_batch(id, at, received, accepted, duplicates, rejected)` → /stats totals without hot-row contention

## 4. Rule enforcement

| Rule | Mechanism |
|---|---|
| R1 | `INSERT ... ON CONFLICT (report_id) DO NOTHING RETURNING` — only rows really inserted drive position/alerts |
| R2 | per-asset row lock (`SELECT ... FOR UPDATE` in sorted order, new assets inserted first); order key `(observed_at, report_id)`; late = key ≤ current |
| R3 | partial unique index + `ON CONFLICT DO NOTHING`; transition-based raise (outside/none → inside) |
| R4 | `SELECT ... FOR UPDATE` on the alert row, then conditional transition |
| R5 | audit insert in the same transaction; append-only trigger |
| R6 | per-line validation in memory; rejects stored in `rejected_line` in the same request; one bad line never aborts the batch |

Deadlock / serialization failures → whole batch transaction retried (bounded).
Zone containment: pure Java ray casting with holes, multipolygons, antimeridian unwrap.

## 5. Data decisions

- Accept: offsets (`+04:00`) normalised to UTC, missing fraction, unknown source, extra fields,
  heading 360, speed -1 (stored as given), 1970 timestamps (simply late).
- Reject: naive timestamps (no zone → not an instant), epoch numbers, non-ISO strings,
  `observedAt` more than `ZW_MAX_FUTURE_SKEW` (default 1h) in the future (2031 sentinel would pin an asset forever),
  string/bool/object coordinates, lowercase or injected asset ids, non-object lines (`"heartbeat"`).
- Same reportId with different content: first stored wins (contract), conflict recorded in `report_conflict`.

## 6. API (fixed contract + additions)

Fixed endpoints exactly per openapi.yaml. Additions:
`GET /api/v1/zones`, `GET /api/v1/zones/occupancy` (non-map view), `GET /api/v1/rejections` (R6 inspection),
`GET /api/v1/stream` (SSE: positions + alerts), `GET /api/v1/ops/metrics`.
Errors: problem+json with `code`; 401/403 from security handlers; no stack traces.
Logging: Spring Boot structured JSON (logstash format), `X-Request-Id` accepted/echoed, MDC.

## 7. Live updates across two instances

Each instance polls `asset_position.changed_at` / `alert.changed_at` every 250 ms with a 5 s
overlap window, de-duplicates by per-row `version`, and fans out to its own SSE clients.
No broker, no extra component. The client re-syncs (bbox + alerts) on every (re)connect;
heartbeat every 5 s; client watchdog reconnects after 12 s of silence.

## 8. Frontend

- Sign-in, signed-in user + role, sign-out.
- MapLibre: zones (fill by class, HTML labels), assets as a symbol layer with rotated arrow icon
  (canvas-generated, no sprite/glyph fetch). Positions in a mutable store outside React,
  flushed to the GeoJSON source at most every 250 ms → no React re-render per update.
- Asset detail panel: latest report, track polyline (`/track`), sourceType (v1.1.0).
- Alert panel: status tabs, cursor "load more", claim/release/resolve, 409/403 explained,
  native `<dialog>` for resolve note, polite live region with throttled summaries.
- Zone occupancy table (non-map view). Connection indicator.
- Longitudes shown unwrapped (negative + 360) so the antimeridian region is contiguous.

## 9. Delivery

- Images (linux/amd64, pinned): `postgres:16.4-alpine`, `zw-backend:<ver>`, `zw-console:<ver>` (nginx + static).
- Compose: `secrets-init` (generates DB password into a volume), `db`, `migrate` (one-shot Flyway),
  `backend-1`, `backend-2`, `lb` (nginx: console + API on :8080, passive failover, SSE unbuffered).
- Users file mounted read-only from the bundle directory.
- `scripts/build-bundle.sh` → `docker save` per version + compose + env + INSTALL.md + SHA256SUMS.

## 10. CR-1 (v1.1.0) — expand / backfill / contract

V2 add nullable `source_type` + BEFORE INSERT trigger + `CHECK NOT VALID` (lock_timeout 3 s) →
roll instances one at a time → throttled keyset backfill job (one-shot container) →
V3 `VALIDATE CONSTRAINT` → V4 `SET NOT NULL` (no scan, uses the valid check) + drop check.
Track returns `sourceType`, accepts `?sourceType=`. Console shows it. openapi → 1.1.0.

## 11. Tests

Testcontainers PostgreSQL (same pinned image). One or more tests per rule, including parallel ones:
R1 parallel re-delivery, R2 shuffled delivery, R3 concurrent entries, R4 20 parallel claimers,
R5 audit vs state consistency + append-only, R6 mixed batch; plus validation table, zone geometry
(holes, multipolygon, antimeridian), contract shape tests, CR-1 derivation.

## 12. Order of work

1. Backend foundation (config, errors, logging, auth, migrations) 2. Ingest 3. Assets/track
4. Alerts 5. Stream, zones, ops 6. Tests 7. Frontend 8. Docker/compose/bundle
9. Docs (README, INSTALL, RUNBOOK, AIRGAP) → git tag v1.0.0 10. CR-1 → tag v1.1.0
