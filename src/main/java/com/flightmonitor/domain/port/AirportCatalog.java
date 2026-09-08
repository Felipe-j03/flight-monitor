package com.flightmonitor.domain.port;

import com.flightmonitor.domain.model.Airport;
import java.util.Optional;

/**
 * Maps IATA codes to airports and, crucially, to countries.
 *
 * <p>Deliberately a local catalogue rather than a provider field: the blocked-country rule must not
 * be steerable by whatever a remote API decides to call a country.
 */
public interface AirportCatalog {

    Optional<Airport> find(String iata);

    int size();
}
