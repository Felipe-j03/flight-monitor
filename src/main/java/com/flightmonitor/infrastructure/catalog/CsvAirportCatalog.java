package com.flightmonitor.infrastructure.catalog;

import com.flightmonitor.domain.model.Airport;
import com.flightmonitor.domain.port.AirportCatalog;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Loads {@code airports.csv} once at startup into an in-memory index keyed by IATA code. */
@Component
public class CsvAirportCatalog implements AirportCatalog {

    private static final Logger log = LoggerFactory.getLogger(CsvAirportCatalog.class);
    private static final String RESOURCE = "airports.csv";

    private final Map<String, Airport> byIata;

    public CsvAirportCatalog() {
        this.byIata = load();
        log.info("Airport catalogue loaded: {} airports", byIata.size());
    }

    private static Map<String, Airport> load() {
        Map<String, Airport> index = new HashMap<>();
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try (InputStream in = resource.getInputStream();
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#") || line.startsWith("iata,")) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length < 6) {
                    log.warn("Skipping malformed airports.csv line {}: {}", lineNumber, line);
                    continue;
                }
                Airport airport = new Airport(
                        parts[0].trim(),
                        parts[1].trim(),
                        parts[2].trim(),
                        parts[3].trim(),
                        parts[4].trim(),
                        parseZone(parts[5].trim(), parts[0].trim()));
                index.put(airport.iata(), airport);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
        if (index.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " produced no airports");
        }
        return Map.copyOf(index);
    }

    private static ZoneId parseZone(String zone, String iata) {
        try {
            return ZoneId.of(zone);
        } catch (RuntimeException e) {
            log.warn("Unknown timezone '{}' for airport {}, falling back to UTC", zone, iata);
            return ZoneId.of("UTC");
        }
    }

    @Override
    public Optional<Airport> find(String iata) {
        if (iata == null || iata.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byIata.get(iata.trim().toUpperCase()));
    }

    @Override
    public int size() {
        return byIata.size();
    }
}
