-- Zone Watch baseline schema (v1.0.0).
-- Forward-only: never edit this file once applied; add a new version instead.

CREATE TABLE app_user (
    username      text PRIMARY KEY,
    password_hash text        NOT NULL,
    role          text        NOT NULL CHECK (role IN ('INGEST', 'OPERATOR', 'SUPERVISOR')),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- Values generated inside the environment (for example the token signing key) so that both
-- instances share them without a secret in the image, the repository or the compose file.
CREATE TABLE app_secret (
    name       text PRIMARY KEY,
    value      text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Reference data, loaded from zones.geojson at start-up (upsert). Geometry is evaluated in the
-- application; the table keeps the loaded definition for audit and for foreign keys.
CREATE TABLE zone (
    zone_id    text PRIMARY KEY,
    name       text        NOT NULL,
    zone_class text        NOT NULL CHECK (zone_class IN ('RESTRICTED', 'ADVISORY')),
    geojson    text        NOT NULL,
    loaded_at  timestamptz NOT NULL DEFAULT now()
);

-- Every accepted report: the track. report_id is the identity of a report (R1).
CREATE TABLE report (
    report_id   text PRIMARY KEY CHECK (char_length(report_id) BETWEEN 1 AND 64),
    asset_id    text             NOT NULL,
    kind        text,
    observed_at timestamptz      NOT NULL,
    lat         double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon         double precision NOT NULL CHECK (lon BETWEEN -180 AND 180),
    speed_kn    double precision,
    heading_deg double precision,
    source      text,
    received_at timestamptz      NOT NULL DEFAULT now()
);
CREATE INDEX report_track_idx ON report (asset_id, observed_at, report_id);

-- Current position: one row per asset, always the accepted report with the newest
-- (observed_at, report_id) (R2). version increases on every change; changed_at feeds the live stream.
CREATE TABLE asset_position (
    asset_id    text PRIMARY KEY,
    report_id   text             NOT NULL,
    kind        text,
    observed_at timestamptz      NOT NULL,
    lat         double precision NOT NULL,
    lon         double precision NOT NULL,
    speed_kn    double precision,
    heading_deg double precision,
    source      text,
    version     bigint           NOT NULL DEFAULT 1,
    changed_at  timestamptz      NOT NULL DEFAULT clock_timestamp()
);
-- Viewport queries: GiST on a built-in point, no PostGIS needed.
CREATE INDEX asset_position_point_idx ON asset_position USING gist (point(lon, lat));
CREATE INDEX asset_position_changed_idx ON asset_position (changed_at);

CREATE TABLE alert (
    alert_seq           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id            text        NOT NULL UNIQUE,
    asset_id            text        NOT NULL,
    zone_id             text        NOT NULL REFERENCES zone (zone_id),
    status              text        NOT NULL CHECK (status IN ('OPEN', 'CLAIMED', 'RESOLVED')),
    opened_at           timestamptz NOT NULL,
    trigger_report_id   text        NOT NULL,
    trigger_observed_at timestamptz NOT NULL,
    claimed_by          text,
    claimed_at          timestamptz,
    resolved_by         text,
    resolved_at         timestamptz,
    resolution_note     text CHECK (resolution_note IS NULL OR char_length(resolution_note) BETWEEN 1 AND 500),
    version             bigint      NOT NULL DEFAULT 1,
    changed_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    -- R4: a CLAIMED alert has exactly one owner, an OPEN one has none (a RESOLVED one keeps its last owner).
    CONSTRAINT alert_owner_consistent CHECK (status <> 'CLAIMED' OR claimed_by IS NOT NULL),
    CONSTRAINT alert_open_unowned CHECK (status <> 'OPEN' OR claimed_by IS NULL),
    CONSTRAINT alert_resolution_consistent CHECK ((status = 'RESOLVED') = (resolved_by IS NOT NULL AND resolution_note IS NOT NULL))
);
-- R3: at most one active (OPEN or CLAIMED) alert per asset and zone, enforced by the database.
CREATE UNIQUE INDEX alert_one_active_idx ON alert (asset_id, zone_id) WHERE status IN ('OPEN', 'CLAIMED');
CREATE INDEX alert_status_idx ON alert (status, alert_seq);
CREATE INDEX alert_asset_idx ON alert (asset_id, alert_seq);
CREATE INDEX alert_zone_idx ON alert (zone_id, alert_seq);
CREATE INDEX alert_changed_idx ON alert (changed_at);

-- R5: append-only audit trail, written in the same transaction as the state change.
CREATE TABLE alert_audit (
    audit_seq   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id    text        NOT NULL REFERENCES alert (alert_id),
    at          timestamptz NOT NULL,
    actor       text        NOT NULL,
    action      text        NOT NULL CHECK (action IN ('OPENED', 'CLAIMED', 'RELEASED', 'RESOLVED')),
    from_status text,
    to_status   text        NOT NULL
);
CREATE INDEX alert_audit_alert_idx ON alert_audit (alert_id, audit_seq);

CREATE FUNCTION alert_audit_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'alert_audit is append-only (% refused)', TG_OP USING ERRCODE = 'insufficient_privilege';
END;
$$;
CREATE TRIGGER alert_audit_no_update_delete
    BEFORE UPDATE OR DELETE ON alert_audit
    FOR EACH ROW EXECUTE FUNCTION alert_audit_append_only();
CREATE TRIGGER alert_audit_no_truncate
    BEFORE TRUNCATE ON alert_audit
    FOR EACH STATEMENT EXECUTE FUNCTION alert_audit_append_only();

-- R6: every rejected line can be inspected later: what it was, why, and when.
CREATE TABLE rejected_line (
    rejected_seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    received_at  timestamptz NOT NULL DEFAULT now(),
    request_id   text,
    line_no      integer     NOT NULL,
    code         text        NOT NULL,
    detail       text,
    raw          text        NOT NULL,
    raw_truncated boolean    NOT NULL DEFAULT false
);
CREATE INDEX rejected_line_code_idx ON rejected_line (code, rejected_seq);

-- A reportId delivered again with different content. The first stored version wins (the
-- contract says the same reportId is the same report); the conflict is kept for inspection.
CREATE TABLE report_conflict (
    conflict_seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_id    text        NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now(),
    request_id   text,
    raw          text        NOT NULL
);

-- One row per processed batch: running totals without a single hot counter row.
CREATE TABLE ingest_batch (
    batch_seq   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    instance    text        NOT NULL,
    request_id  text,
    received    integer     NOT NULL,
    accepted    integer     NOT NULL,
    duplicates  integer     NOT NULL,
    rejected    integer     NOT NULL,
    duration_ms integer     NOT NULL
);
