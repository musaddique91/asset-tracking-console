# Runbook (one page)

Everything below uses only what exists inside the environment: `docker compose`, the logs, the
`zwctl` script in the application image and `psql` in the database container.
Shorthand: `dc` = `docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/<current>.env`.

**First look, whatever the symptom**
```
dc ps                                         # every service Up / healthy?  migrate must be "Exited (0)"
dc logs --since 10m backend-1 backend-2 lb    # JSON lines; filter by "level":"ERROR" or a requestId
dc exec backend-1 /app/bin/zwctl metrics      # zw_* counters of that instance (also backend-2)
```
Every response carries `X-Request-Id` (and `X-Served-By` from the load balancer); the same id is in
the nginx line and the backend line, so one request can be followed across both.

## Slow ingest
1. Rate per instance: `dc exec backend-1 /app/bin/zwctl metrics | grep -E 'zw_ingest_(lines|batch)'`.
   `zw_ingest_batch_seconds{quantile="0.95"}` above ~0.5 s means the database is the bottleneck.
2. Retries: `zw_ingest_batch_retries_total` growing means deadlocks / lock timeouts; look for
   `"batch attempt"` warnings in the logs.
3. Database: `dc exec db psql -U zonewatch -c "select pid, wait_event_type, wait_event, state, now()-query_start as age, left(query,80) from pg_stat_activity where datname='zonewatch' order by age desc nulls last"`.
   Many `Lock` waits on `asset_position` = two writers on the same assets (expected, short). Long
   `IO` waits = disk. `log_min_duration_statement=500` writes every slow statement to `dc logs db`.
4. Pool exhaustion: `zwctl prom | grep hikaricp_connections_pending` > 0 for long periods → raise
   `ZW_DB_POOL_SIZE` or find the slow statement.
5. Rejections are not slowness: `zw_ingest_rejections_total{code=...}`; inspect them with
   `GET /api/v1/rejections` (supervisor) or `select code, detail, left(raw,120) from rejected_line order by rejected_seq desc limit 20`.

## Console stops updating
1. Header says **"Live updates interrupted"**: the browser lost the stream. Check `dc ps lb` and
   `curl -sI http://<host>:8080/health` from the operator machine; the console reconnects by itself.
2. Header says **Live** but nothing moves: is data arriving? `GET /api/v1/stats` twice — `accepted` must grow.
   If not, the feed has stopped (check the sender, `dc logs lb | grep reports/batch`).
3. Data arrives but the map is stale: the change poller of the instance the browser is connected to.
   `GET /api/v1/ops/metrics` (supervisor; `X-Served-By` names the instance) shows `livePollLast` and
   `livePollMs`; a `livePollLast` older than a second, or `"live change poll failed"` in that instance's
   logs, means the poller cannot read the database. `liveClients` shows how many consoles it feeds.
4. A slow client is disconnected on purpose (`"live client ... too slow"`); it reconnects and re-syncs.

## A claim that hangs
1. The claim locks one alert row (`SELECT ... FOR UPDATE`); a hang means another transaction holds it.
   `dc exec db psql -U zonewatch -c "select pid, now()-xact_start as tx_age, state, left(query,80), pg_blocking_pids(pid) from pg_stat_activity where datname='zonewatch' and cardinality(pg_blocking_pids(pid))>0"`.
2. The blocker's pid is in `pg_blocking_pids`; look at its query. `lock_timeout` (10 s) and
   `statement_timeout` (30 s) end such waits with an error instead of a hang; the operator sees "Try again".
3. Last resort: `select pg_terminate_backend(<blocking pid>)` — the blocked claim then completes.
4. If requests hang before reaching the database: `zwctl prom | grep -E 'hikaricp_connections_(active|pending)'`
   — all connections busy points at a slow ingest or a stuck statement (see "Slow ingest").
