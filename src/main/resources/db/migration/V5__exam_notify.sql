-- "Results published" mail to students: when it was last sent and to how many addresses.
alter table exam add column notified_at    timestamptz;
alter table exam add column notified_count integer;
