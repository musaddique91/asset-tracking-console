-- CR-1, step 1 of 4: EXPAND. Safe while v1.0.0 instances keep writing.
-- Every statement here only changes the catalogue: no table rewrite, no scan. The locks they take
-- (ACCESS EXCLUSIVE for ADD COLUMN / ADD CONSTRAINT, SHARE ROW EXCLUSIVE for CREATE TRIGGER) last
-- milliseconds, but would queue behind a long transaction and block everyone behind them; lock_timeout
-- makes the migration fail fast instead, and it can simply be run again.
SET lock_timeout = '3s';

-- Nullable, no default: PostgreSQL adds it without touching the 500 million existing rows.
ALTER TABLE report ADD COLUMN source_type text;

-- The derivation rule, once, in the database: SAT -> SATELLITE, GND -> GROUND, MOB -> MOBILE, else UNKNOWN.
CREATE FUNCTION report_source_type(src text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$
SELECT CASE
           WHEN src LIKE 'SAT%' THEN 'SATELLITE'
           WHEN src LIKE 'GND%' THEN 'GROUND'
           WHEN src LIKE 'MOB%' THEN 'MOBILE'
           ELSE 'UNKNOWN'
       END
$$;

-- v1.0.0 does not know the column: rows it inserts get the value here. v1.1.0 sets it itself.
CREATE FUNCTION report_fill_source_type() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.source_type IS NULL THEN
        NEW.source_type := report_source_type(NEW.source);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER report_fill_source_type
    BEFORE INSERT ON report
    FOR EACH ROW EXECUTE FUNCTION report_fill_source_type();

-- Enforced for every new row at once; existing rows are checked later (V3) without blocking writes.
ALTER TABLE report ADD CONSTRAINT report_source_type_not_null CHECK (source_type IS NOT NULL) NOT VALID;
ALTER TABLE report ADD CONSTRAINT report_source_type_known
    CHECK (source_type IN ('SATELLITE', 'GROUND', 'MOBILE', 'UNKNOWN')) NOT VALID;
