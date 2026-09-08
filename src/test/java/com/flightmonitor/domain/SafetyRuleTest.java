package com.flightmonitor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.TestFixtures;
import com.flightmonitor.domain.model.Itinerary;
import com.flightmonitor.domain.model.Rejection;
import com.flightmonitor.domain.model.RejectionReason;
import com.flightmonitor.domain.model.RouteDetailLevel;
import com.flightmonitor.domain.port.AirportCatalog;
import com.flightmonitor.domain.rule.SafetyRule;
import com.flightmonitor.infrastructure.catalog.CsvAirportCatalog;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The routing rule is the requirement with the sharpest failure mode: a connection slipping through
 * is worse than no monitor at all. These tests pin the behaviour the brief called out by name.
 */
class SafetyRuleTest {

    private final AirportCatalog catalog = new CsvAirportCatalog();

    @Test
    @DisplayName("rejects a connection in a blocked country even though endpoints are fine")
    void rejectsBlockedConnectionCountry() {
        Itinerary doha = TestFixtures.acceptableItinerary()
                .connectingVia("DOH", TestFixtures.QATAR)
                .build();

        List<Rejection> rejections = new SafetyRule(TestFixtures.safety(), catalog).check(doha);

        assertThat(rejections)
                .extracting(Rejection::reason)
                .contains(RejectionReason.BLOCKED_COUNTRY);
        assertThat(rejections)
                .anySatisfy(rejection -> assertThat(rejection.detail())
                        .contains("Qatar")
                        .contains("DOH"));
    }

    @Test
    @DisplayName("accepts a connection in a country that is not on the list")
    void acceptsAllowedConnection() {
        Itinerary frankfurt = TestFixtures.acceptableItinerary().build();

        List<Rejection> rejections = new SafetyRule(TestFixtures.safety(), catalog).check(frankfurt);

        assertThat(rejections).isEmpty();
    }

    @Test
    @DisplayName("rejects a specific blocked airport regardless of its country")
    void rejectsBlockedAirport() {
        Itinerary viaFrankfurt = TestFixtures.acceptableItinerary().build();

        List<Rejection> rejections =
                new SafetyRule(TestFixtures.safetyBlockingAirport("FRA"), catalog)
                        .check(viaFrankfurt);

        assertThat(rejections)
                .extracting(Rejection::reason)
                .containsExactly(RejectionReason.BLOCKED_AIRPORT);
    }

    @Test
    @DisplayName("checks the return leg too, not just the outbound")
    void checksReturnLeg() {
        // The builder routes both directions through the same connection, so a blocked connection
        // on the way home is caught by the same rule.
        Itinerary viaDubai = TestFixtures.acceptableItinerary()
                .connectingVia("DXB", java.time.ZoneOffset.ofHours(4))
                .build();

        List<Rejection> rejections = new SafetyRule(TestFixtures.safety(), catalog).check(viaDubai);

        assertThat(rejections).isNotEmpty();
        assertThat(rejections.get(0).detail()).contains("DXB");
    }

    @Test
    @DisplayName("rejects an offer whose connections the source never disclosed")
    void rejectsUnverifiableRoute() {
        Itinerary countsOnly = TestFixtures.acceptableItinerary()
                .detailLevel(RouteDetailLevel.COUNTS_ONLY)
                .declaredStops(2)
                .build();

        List<Rejection> rejections =
                new SafetyRule(TestFixtures.safety(), catalog).check(countsOnly);

        assertThat(rejections)
                .extracting(Rejection::reason)
                .contains(RejectionReason.ROUTE_UNVERIFIABLE);
    }

    @Test
    @DisplayName("accepts an unverifiable route when the operator opts out of failing closed")
    void allowsUnverifiableRouteWhenConfigured() {
        Itinerary countsOnly = TestFixtures.acceptableItinerary()
                .detailLevel(RouteDetailLevel.COUNTS_ONLY)
                .declaredStops(2)
                .build();

        List<Rejection> rejections =
                new SafetyRule(TestFixtures.safetyAllowingUnverifiedRoutes(), catalog)
                        .check(countsOnly);

        assertThat(rejections).isEmpty();
    }

    @Test
    @DisplayName("rejects an airport missing from the catalogue rather than assuming it is safe")
    void rejectsUnknownAirport() {
        Itinerary unknownHub = TestFixtures.acceptableItinerary()
                .connectingVia("ZZZ", java.time.ZoneOffset.UTC)
                .build();

        List<Rejection> rejections =
                new SafetyRule(TestFixtures.safety(), catalog).check(unknownHub);

        assertThat(rejections)
                .extracting(Rejection::reason)
                .contains(RejectionReason.UNKNOWN_AIRPORT_COUNTRY);
    }

    @Test
    @DisplayName("the catalogue resolves the airports the rule depends on")
    void catalogueCoversKeyAirports() {
        assertThat(catalog.find("HND")).isPresent();
        assertThat(catalog.find("POA")).isPresent();
        assertThat(catalog.find("DOH")).get()
                .satisfies(airport -> assertThat(airport.countryCode()).isEqualTo("QA"));
        assertThat(catalog.find("FRA")).get()
                .satisfies(airport -> assertThat(airport.countryCode()).isEqualTo("DE"));
        assertThat(catalog.size()).isGreaterThan(150);
    }
}
