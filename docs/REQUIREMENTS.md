# Requirements tracker

Every requirement of the candidate brief (v1.1) and where it stands. ✅ done and verified ·
⚠️ partly done · ❌ not done · ➖ optional ("Could"). Updated as work lands; the README links here.

## Section 4 — rules that must always hold (priority: Must)

| Req | Status | Mechanism | Evidence |
|---|---|---|---|
| Line outcomes: accepted / duplicate / rejected, line + reason | ✅ | `ReportValidator`, `IngestService` | `ReportValidatorTest` (34), `IngestRulesIT.r6_*` |
| Late report stored in track only | ✅ | `(observedAt, reportId)` comparison under row lock | `IngestRulesIT.r2_lateReport…` |
| R1 exactly-once effect | ✅ | `INSERT … ON CONFLICT DO NOTHING RETURNING` | `r1_*` (12 parallel deliveries); full feed replayed twice, DB unchanged |
| R2 event-time order | ✅ | sorted `SELECT … FOR UPDATE` per asset, reportId tie-break | `r2_*` (400 shuffled reports, 40 parallel batches) |
| R3 one active alert | ✅ | partial unique index `alert_one_active_idx` | `r3_*` incl. concurrent entries, DB refuses a second |
| R4 one owner | ✅ | `SELECT … FOR UPDATE` on the alert | `r4_twentyOperatorsClaim…` |
| R5 audit in same transaction, append-only | ✅ | same `@Transactional`; trigger blocks UPDATE/DELETE/TRUNCATE | `r5_*` |
| R6 bad input isolated, inspectable | ✅ | per-line validation; `rejected_line`; `GET /api/v1/rejections` | `r6_*` |
| Alert rule: holes, multipart, overlaps, ADVISORY, re-entry after resolve | ✅ | `ZoneShape` (ray casting, antimeridian unwrap) | `ZoneShapeTest`, `IngestRulesIT.alertRule_*`, `r3_afterResolve…` |
| Alert lifecycle table 6 (claim/release/resolve, idempotent repeats, codes) | ✅ | `AlertService` | `AlertWorkflowIT` |
| Holds with two instances on one database | ✅ | locks/constraints in PostgreSQL, nothing in memory | two-instance compose replay: 5,064 lines/s, 0 errors |

## Section 5 — fixed contract and performance

| Req | Status | Evidence |
|---|---|---|
| All fixed endpoints, shapes, problem codes | ✅ | `ContractIT` (15 tests) |
| Ingest ≥ 2,000 lines/s, replay max speed, 4 connections | ✅ | 5,064 lines/s (amd64 emulated on Apple Silicon) — **to re-measure natively for README** |
| Viewport p95 < 100 ms up to 1,000 matches | ✅ | p95 7.7 ms idle (`scripts/measure_viewport.py`) — **⚠️ re-measure under ingest load, keep EXPLAIN in README** |

## Section 6 — frontend

| Req | Status | Evidence / gap |
|---|---|---|
| Live map: all assets + zones, heading, class + name | ✅ | browser check at 5x |
| Asset detail: latest report + recent track | ✅ | browser check |
| Alert panel, claim/release/resolve, lost race explained | ✅ | browser check incl. real race |
| Sign-in, who is signed in | ✅ | user menu |
| Connection state; correct again after 30 s loss without reload | ✅ | LB paused 33 s; counts matched server |
| No main-thread task > 200 ms at 5x — **evidence** | ✅ | trace: longest 28.9 ms, none > 50 ms (`docs/evidence/responsiveness-5x.md`) |
| WCAG 2.1 AA alert workflow — **evidence** | ✅ | Lighthouse accessibility 100 (sign-in, console dark + light: `docs/evidence/`); keyboard checks of tabs, menu, dialog |
| TypeScript strict, API types follow contract | ✅ | `tsconfig.json` strict + `noUncheckedIndexedAccess` |

## Section 7 — air-gapped delivery

| Req | Status | Gap |
|---|---|---|
| One command: DB, 2 instances, LB, console; API on 8080 | ✅ | |
| No external calls (backend + browser) | ✅ | verified with the network switched off |
| Offline bundle: `docker save` of v1.0.0 **and** v1.1.0, compose, templates, INSTALL.md; script in repo | ✅ | `scripts/build-bundle.sh` → `bundle/` (2 × 247 MB + deploy + SHA256SUMS) |
| linux/amd64, 2 CPU / 6 GB / 20 GB | ⚠️ | amd64 ✅, memory ≈ 4 GB ✅; not run on 2 CPUs |
| INSTALL.md (a) v1.1.0 ≤ 5 commands (b) v1.0.0 (c) rolling upgrade; users file; console address | ✅ | |
| Least privilege | ✅ | |
| Config and secrets at deploy time | ✅ | |
| Observability: health, structured logs, metrics | ✅ | |
| Runbook (one page) | ✅ | `docs/RUNBOOK.md` |
| Building inside the air gap (half page) | ✅ | `docs/AIRGAP-BUILD.md` |
| **Test the bundle**: no local images, network off, different users file | ✅ | no local images + different users file: (a), (b)→(c) under traffic, 0 failed; network off on the candidate's machine: started and worked |

## Section 8 — CR-1 (v1.1.0)

| Req | Status |
|---|---|
| `source_type` stored, mandatory for old and new rows (SAT/GND/MOB/UNKNOWN) | ✅ V2–V4, backfill job, `Cr1MigrationIT` |
| Track returns `sourceType`, accepts `?sourceType=` | ✅ `SourceTypeIT` |
| Asset detail shows it | ✅ |
| openapi.yaml → 1.1.0 | ✅ |
| Migration scripts: expand / throttled backfill / validate / contract, no long lock, rollback at every step | ✅ |
| `docs/CR-1.md` (≤ 2 pages): steps, locks, verification, rollback, preconditions, 500 M backfill time, throttling | ✅ |
| INSTALL upgrade commands match the change record; zero failed requests under traffic | ✅ rehearsed: 35,115 requests, 0 failed (with local images; repeat from the bundle) |

## Section 9 — technology and house conventions

| Convention | Status |
|---|---|
| Java 21, Spring Boot 3, Maven; PostgreSQL 16; React 18 + TS; Compose; pinned versions | ✅ |
| Errors problem+json, no internals | ✅ |
| UTC, ISO-8601 ms Z, timestamptz | ✅ |
| Flyway forward-only, ORM never alters schema | ✅ (JDBC; JPA question open, see below) |
| Transactions in services, controllers no data access, no network call in a transaction | ✅ |
| Every list bounded and paged | ✅ |
| Config/secrets from environment or mounted files | ✅ (fixed in abab5a4) |
| JSON logs with request id; X-Request-Id echoed; no credentials in logs | ✅ |
| Tests for every rule incl. parallel | ✅ |
| Small commits, tags v1.0.0 / v1.1.0 | ✅ |
| Frontend strict, no `any`, no other host | ✅ |
| MariaDB note (½ page) in README | ✅ |

## Sections 10–12 — submission

| Deliverable | Status |
|---|---|
| Public GitHub repo, neutral name/description, full history, tags | ✅ `asset-tracking-console` (see push) |
| Release v1.1.0 with bundle + `SHA256SUMS`, files < 2 GB | ✅ published; all five assets verified against `SHA256SUMS` (GitHub digests) |
| README: run online/offline, architecture + data flow, rule table, 5 decisions + rejected alternatives, data findings, evidence, MariaDB note, limitations, **actual effort**, **AI disclosure (≥ 3 cases)** | ✅ except **actual effort** (candidate fills in) |
| Tests + command + report/console output | ✅ commands in README §1; reports in `backend/target/*-reports` after a run |
| Source builds from a clean checkout | ✅ fresh clone: `./mvnw verify` 101 tests pass; `npm ci`, typecheck, tests, build pass |

## Open decisions

- Spring Data JPA: keep JDBC, or JPA for alerts/reads with native SQL for ingest (asked, not answered).
- Per-alert audit history in the console (proposed, small; not required).
- ~~`root` (local developer password) in history~~ — removed from every commit before the first push (history rewritten; final trees and tags unchanged).
