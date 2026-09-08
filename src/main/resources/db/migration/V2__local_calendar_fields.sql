-- The instants in flight_offer are correct, but they cannot answer "what date is on the ticket".
--
-- Postgres stores TIMESTAMP WITH TIME ZONE as an instant and hands it back in UTC, so the original
-- offset is gone by the time anything reads the row. A 07:00 departure from Tokyo comes back as
-- 22:00 the previous day, and every display of it — dashboard, alert, API — is off by a day.
--
-- These columns carry the calendar values as they appear on the ticket, in the local time of the
-- airport concerned, exactly as the provider reported them. They are text on purpose: they are
-- labels to display, not instants to compute with. The timestamptz columns remain the source of
-- truth for every comparison (the "home before 23 Jan" rule included).

ALTER TABLE flight_offer ADD COLUMN departure_local_date VARCHAR(10);
ALTER TABLE flight_offer ADD COLUMN arrival_local_date VARCHAR(10);
ALTER TABLE flight_offer ADD COLUMN return_departure_local_date VARCHAR(10);
ALTER TABLE flight_offer ADD COLUMN return_arrival_local_time VARCHAR(16);
