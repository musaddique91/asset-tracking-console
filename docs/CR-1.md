# Change record CR-1 — report source type (v1.0.0 → v1.1.0)

**Change.** Every stored report gets `source_type`, derived from `source` by prefix: `SAT` → SATELLITE,
`GND` → GROUND, `MOB` → MOBILE, anything else or no source → UNKNOWN. Mandatory for every row, old
and new. `GET /api/v1/assets/{id}/track` returns `sourceType` on every point and accepts `?sourceType=`;
the console's asset detail shows it; `openapi.yaml` is 1.1.0.

**Constraints.** 500 million rows under constant writes; instances upgraded one at a time, so v1.0.0
and v1.1.0 run together; no downtime, no failed request, no long lock; stop and roll back after every step.

**Approach: expand → backfill → contract.** The derivation rule lives once in the database
(`report_source_type(text)`). A `BEFORE INSERT` trigger fills the column for writers that do not know
it (v1.0.0); v1.1.0 writes it itself. Reads of rows not yet backfilled derive it with the same function.
Every DDL statement runs with `lock_timeout = 3s`: if a long transaction holds the table, the step fails
at once instead of queueing behind it and blocking all writers — and is simply run again.

## Steps (the commands are in INSTALL.md, section "Upgrade")

| # | Step | Locks, and for how long | Verify | Roll back |
|---|---|---|---|---|
| 0 | `docker load` the v1.1.0 images | none | `docker image ls zonewatch/app` | nothing to undo |
| 1 | **V2 expand** (`migrate`, target 2): add nullable column; function; trigger; `CHECK (source_type IS NOT NULL) NOT VALID`; `CHECK (source_type IN …) NOT VALID` | ACCESS EXCLUSIVE / SHARE ROW EXCLUSIVE, catalogue only: **milliseconds** (no rewrite, no scan). Bounded by `lock_timeout` 3 s | `flyway_schema_history` has V2; a v1.0.0 insert gets a value (trigger) | leave it: v1.0.0 ignores the column and the trigger keeps rows valid. To remove: a forward migration dropping trigger, checks and column |
| 2 | **backend-1 → 1.1.0** (`up -d --no-deps --wait backend-1`) | none. Graceful shutdown (in-flight requests finish, live streams close); nginx retries refused connections on backend-2 | `ps` healthy; `X-Served-By`; track has `sourceType` | same command with `upgrade-1-expand.env` (backend-1 back to 1.0.0) |
| 3 | **backend-2 → 1.1.0** | as step 2 | as step 2 | `upgrade-2-backend-1.env` |
| 4 | **backfill** (`run --rm backfill`): keyset batches of 2,000 rows, own transaction each, `lock_timeout` 2 s, `statement_timeout` 30 s, 50 ms pause | row locks on ≤ 2,000 rows for one short transaction (~10–60 ms); no table lock | log ends `rows still NULL: 0`; `select count(*) … where source_type is null` = 0 | stop it (Ctrl+C / `docker stop`): rows already filled are correct either way. Resume with `ZW_BACKFILL_FROM=<last key logged>` |
| 5 | **V3 validate + V4 contract** (`migrate`, target latest) | V3 `VALIDATE CONSTRAINT`: **SHARE UPDATE EXCLUSIVE** for the scan — reads and writes continue. V4 `SET NOT NULL`: ACCESS EXCLUSIVE for **milliseconds** (the validated check proves no NULL, so no scan); bounded by `lock_timeout` | column `is_nullable = NO`; no `NOT VALID` constraint left | instances: back to 1.0.0 per step 2/3 — they keep working (the trigger fills the column). Schema: `ALTER … DROP NOT NULL` in a forward migration (instant) |

**Before the final step (5) must be true:** V2 applied; the backfill finished with 0 NULL rows (V3
otherwise fails safely and changes nothing); no planned rollback to a writer that inserts an explicit
NULL (none exists: v1.0.0 omits the column). Both instances need not be on v1.1.0 for step 5, but the
order above keeps the mixed period short.

## Measured (rehearsal on a loaded v1.0.0 system, under traffic)

Compose stack on a laptop (linux/amd64 images under emulation), 410,000 reports without source type,
`scripts/upgrade_traffic.py` running ingest, viewport, alert, track and stats requests throughout:

| Step | Duration | Requests during the whole upgrade |
|---|---|---|
| 1 V2 expand | 7.8 s (container start included; DDL itself < 50 ms) | **35,115 requests, 0 failed** |
| 2 backend-1 | 11.4 s until healthy | (ingest 8,885 · viewport 10,337 · track 5,649 · alerts 5,707 · stats 5,501) |
| 3 backend-2 | 11.4 s until healthy | |
| 4 backfill | 27 s for 888,213 keys scanned / 409,962 filled — **≈ 33,000 rows/s** with the default throttle | |
| 5 V3 + V4 | 8 s | |

The same sequence is an automated test: `Cr1MigrationIT` (V1 data → V2 → v1.0.0 writes → V3 refused
before the backfill → backfill twice → V3 + V4 → v1.0.0 still writes).

## The backfill at 500 million rows

- **Time.** At the measured 33,000 rows/s: 500,000,000 / 33,000 ≈ **4.2 hours**. On production hardware
  the raw rate is higher, but it is deliberately throttled; planning figure **4–8 hours** at a rate that
  leaves headroom, run outside peak hours. It is resumable, so it can be split over several nights.
- **Keeping it from hurting live traffic.** Small batches (`ZW_BACKFILL_BATCH`) in short transactions,
  so no lock is held longer than one batch and no long transaction holds back vacuum; a pause between
  batches (`ZW_BACKFILL_PAUSE`) sets the rate; `lock_timeout` makes the backfill, not the ingest, wait
  and give up on contention; one connection only (`ZW_DB_POOL_SIZE=2`); keyset on the primary key
  (no OFFSET, no sequential scan); only NULL rows are written. Watch while it runs: ingest batch p95
  (`zw_ingest_batch_seconds`), lock waits (`pg_stat_activity`), WAL volume and, with replicas,
  replication lag — raise the pause if any of them moves.
- **Side effects to plan for.** Every row is rewritten once: roughly the table's size again in WAL and,
  until vacuum reclaims them, in dead tuples. Let autovacuum keep up (or `VACUUM` between nights);
  the column is not indexed, so updates can be HOT where pages have free space.
- `VALIDATE CONSTRAINT` (step 5) reads all 500 million rows (tens of minutes) but blocks no reads or writes.
