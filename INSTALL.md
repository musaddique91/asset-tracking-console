# Installing Zone Watch (offline)

Target: Linux x86_64 with Docker Engine and the Compose plugin. Every command below uses only
`docker`, `docker compose`, `tar`, `gzip`, `cat` and `sha256sum`. Nothing is downloaded or built:
every image is in the bundle, and the compose file never pulls (`pull_policy: never`).

| Release file | Content |
|---|---|
| `zonewatch-images-1.0.0.tar.gz` | images of v1.0.0 (`docker save`, linux/amd64): app 1.0.0, postgres, nginx |
| `zonewatch-images-1.1.0.tar.gz` | images of v1.1.0: app 1.1.0, postgres, nginx |
| `zonewatch-deploy-1.1.0.tar.gz` | `zonewatch/`: compose file, nginx configuration, env files, this file, runbook, change record, contract |
| `INSTALL.md` | this file |
| `SHA256SUMS` | checksums of every other file |

Run every command from the directory that holds the release files.

**Users file (deploy-time secret).** A JSON array of `{"username", "role", "password"}` with roles
`INGEST`, `OPERATOR`, `SUPERVISOR` (template: `zonewatch/config/users.json.example`). It goes to
**`zonewatch/config/users.json`** and is mounted read-only into the API instances; it is in no image.
Passwords are stored as BCrypt hashes. Changing the file and restarting the instances updates the users.

**Console: http://&lt;host&gt;:8080/** · API: `http://<host>:8080/api/v1/…` · readiness: `http://<host>:8080/health`.
The database password and the token signing key are generated inside the environment on first start.

## (a) Start v1.1.0 on an empty database

```
sha256sum -c SHA256SUMS
tar -xzf zonewatch-deploy-1.1.0.tar.gz
docker load -i zonewatch-images-1.1.0.tar.gz
cat /path/to/users.json > zonewatch/config/users.json
docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/v1.1.0.env up -d --wait
```

## (b) Start v1.0.0 on an empty database

```
sha256sum -c SHA256SUMS
tar -xzf zonewatch-deploy-1.1.0.tar.gz
docker load -i zonewatch-images-1.0.0.tar.gz
cat /path/to/users.json > zonewatch/config/users.json
docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/v1.0.0.env up -d --wait
```

## (c) Upgrade a running v1.0.0 to v1.1.0, one instance at a time

For a v1.0.0 started as in (b). Traffic keeps flowing; the load balancer sends requests to the
instance that is up. Each step is explained — locks, checks, rollback — in `zonewatch/docs/CR-1.md`.

```
docker load -i zonewatch-images-1.1.0.tar.gz
docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/upgrade-1-expand.env run --rm migrate
docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/upgrade-2-backend-1.env up -d --no-deps --wait backend-1
docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/upgrade-3-backend-2.env up -d --no-deps --wait backend-2
docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/upgrade-3-backend-2.env run --rm backfill
docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/v1.1.0.env run --rm migrate
```

1. load the v1.1.0 images (nothing running changes);
2. **expand**: schema V2 — new nullable column, trigger and unvalidated checks; v1.0.0 keeps working;
3. backend-1 → v1.1.0 (backend-2 serves meanwhile);
4. backend-2 → v1.1.0;
5. **backfill** the source type of existing rows, throttled; the last log line must say `rows still NULL: 0`;
6. **validate and contract**: schema V3 + V4 — the column becomes `NOT NULL`.

Rolling back: to return an instance to v1.0.0 after step 3 or 4, run the step-2 or step-3 command
with the env file of the previous step (`upgrade-1-expand.env` for backend-1, `upgrade-2-backend-1.env`
for backend-2). v1.0.0 runs on every schema state of the upgrade. The backfill can be stopped at any
time and run again. See `zonewatch/docs/CR-1.md`.

## Operating

With `dc` = `docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/<current>.env`:

- status: `dc ps` · logs: `dc logs -f backend-1 backend-2 lb` · metrics: `dc exec backend-1 /app/bin/zwctl metrics`
- stop, keeping the data: `dc down` · stop and **delete all data**: `dc down -v`
- port 8080 taken: prefix the `up` command with `ZW_HTTP_PORT=18080`

Diagnosis: `zonewatch/docs/RUNBOOK.md`.
