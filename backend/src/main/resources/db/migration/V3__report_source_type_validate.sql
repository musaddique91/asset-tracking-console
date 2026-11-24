-- CR-1, step 3 of 4: VALIDATE, after the backfill has filled every existing row.
-- VALIDATE CONSTRAINT scans the table but holds only SHARE UPDATE EXCLUSIVE: reads and writes
-- continue. If a row is still NULL it fails and changes nothing: run the backfill again.
ALTER TABLE report VALIDATE CONSTRAINT report_source_type_not_null;
ALTER TABLE report VALIDATE CONSTRAINT report_source_type_known;
