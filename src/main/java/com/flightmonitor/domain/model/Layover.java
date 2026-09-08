package com.flightmonitor.domain.model;

import java.time.Duration;

/** A connection stop. {@code airport} is the IATA code of the airport actually transited. */
public record Layover(String airport, Duration duration, boolean overnight) {

    public Layover {
        airport = airport == null ? null : airport.trim().toUpperCase();
    }
}
