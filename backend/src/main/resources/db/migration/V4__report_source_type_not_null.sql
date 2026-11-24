-- CR-1, step 4 of 4: CONTRACT. The column becomes mandatory in the schema itself.
-- PostgreSQL (12+) sees the validated CHECK (source_type IS NOT NULL) and skips the table scan,
-- so the ACCESS EXCLUSIVE lock lasts milliseconds; lock_timeout keeps it from queueing.
SET lock_timeout = '3s';
ALTER TABLE report ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE report DROP CONSTRAINT report_source_type_not_null;
