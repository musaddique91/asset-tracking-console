# Installing Zone Watch (offline)

Target: Linux x86_64 with Docker Engine and the Compose plugin. Only `docker`, `docker compose`,
`tar`, `gzip`, `cat` and `sha256sum` are used. Nothing is downloaded: every image is in the bundle.

The release holds:

| File | Content |
|---|---|
| `zonewatch-images-1.0.0.tar.gz` | images of v1.0.0 (`docker save`, linux/amd64) |
| `zonewatch-deploy-1.0.0.tar.gz` | compose file, configuration, env files, this file, runbook |
| `SHA256SUMS` | checksums of every other file |

Run every command from the directory that holds the release files.

## The users file (deploy-time secret)

A JSON array of `{"username", "role", "password"}` with roles `INGEST`, `OPERATOR`, `SUPERVISOR`
(see `zonewatch/config/users.json.example`). It is mounted read-only into the API instances from
**`zonewatch/config/users.json`**; it is not in any image. Passwords are stored as BCrypt hashes.

## Start v1.0.0 on an empty database

```
sha256sum -c SHA256SUMS
tar -xzf zonewatch-deploy-1.0.0.tar.gz
docker load -i zonewatch-images-1.0.0.tar.gz
cat /path/to/users.json > zonewatch/config/users.json
docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/v1.0.0.env up -d --wait
```

- Console: **http://&lt;host&gt;:8080/** — sign in with an operator or supervisor account.
- API: **http://&lt;host&gt;:8080/api/v1/...**, readiness at **http://&lt;host&gt;:8080/health**.
- Status: `docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/v1.0.0.env ps`
- Stop (keeps data): `docker compose -f zonewatch/compose.yaml --env-file zonewatch/env/v1.0.0.env down`
- Stop and delete all data: add `-v` to the command above.

The database password is generated inside the environment on first start (volume `zw-secrets`);
the token signing key is generated once and kept in the database. Neither needs configuring.

Diagnosis: see `zonewatch/docs/RUNBOOK.md`.
