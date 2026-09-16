-- Open-jaw trips land in one city and fly home from another (arrive in Rio, leave from Porto Alegre).
-- The outbound origin/destination columns cannot describe the return leg any more, so it gets its
-- own pair.
--
-- "alternative" records whether an offer used an alternative airport — landing somewhere other than
-- intended, or flying home from somewhere other than planned. Alerts compare an alternative only
-- against the best NON-alternative price; without this flag a cheap São Paulo return would count as
-- its own benchmark and could never look like a saving.

ALTER TABLE flight_offer ADD COLUMN return_origin_airport VARCHAR(4);
ALTER TABLE flight_offer ADD COLUMN return_destination_airport VARCHAR(4);
ALTER TABLE flight_offer ADD COLUMN alternative BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_flight_offer_trip_primary_price
    ON flight_offer (trip_id, accepted, alternative, current_price_gbp);
