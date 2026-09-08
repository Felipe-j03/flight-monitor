package com.flightmonitor.notification;

import com.flightmonitor.application.AlertType;
import com.flightmonitor.application.PriceChange;
import com.flightmonitor.config.TripConfig;
import com.flightmonitor.domain.model.DurationBand;
import com.flightmonitor.domain.model.OfferEvaluation;
import com.flightmonitor.domain.model.PriceBand;
import com.flightmonitor.domain.port.AirportCatalog;
import com.flightmonitor.infrastructure.persistence.FlightOfferEntity;
import com.flightmonitor.infrastructure.persistence.OfferSourceEntity;
import com.flightmonitor.providers.fixture.FixtureFlightProvider;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Builds the Telegram message for one alert.
 *
 * <p>Separated from delivery so the wording can be asserted in tests without touching HTTP, and so
 * a second channel could reuse it. Output is Telegram HTML, and every value that comes from a
 * provider is escaped before it goes in.
 *
 * <p>Anything the sources did not disclose is stated as unavailable rather than omitted, and offers
 * produced by the fixture provider carry an unmissable banner so simulated data can never be
 * mistaken for a real fare.
 */
@Component
public class AlertMessageFormatter {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final AirportCatalog catalog;

    public AlertMessageFormatter(AirportCatalog catalog) {
        this.catalog = catalog;
    }

    public String format(
            AlertType type,
            FlightOfferEntity offer,
            List<OfferSourceEntity> sources,
            OfferEvaluation evaluation,
            PriceChange change,
            TripConfig trip,
            List<String> providerCodes) {

        StringBuilder message = new StringBuilder();

        if (providerCodes.contains(FixtureFlightProvider.CODE)) {
            message.append("⚠️ <b>DADOS SIMULADOS (provider de teste)</b> ⚠️\n")
                    .append("Este alerta NÃO representa um preço real.\n\n");
        }

        message.append(header(type)).append("\n");
        message.append(route(offer)).append("\n");
        message.append(dates(offer)).append("\n\n");

        message.append(priceSection(type, offer, change)).append("\n");
        message.append(flightSection(offer)).append("\n");
        message.append(bandLine(evaluation, trip)).append("\n");

        List<String> notes = new ArrayList<>(evaluation.warnings());
        if (!notes.isEmpty()) {
            message.append("\n⚠️ ").append(escape(String.join("\n⚠️ ", notes))).append("\n");
        }

        message.append("\n").append(sourceLine(sources, providerCodes));
        return message.toString();
    }

    private String header(AlertType type) {
        return switch (type) {
            case PRICE_DROP -> "✈️ <b>QUEDA DE PREÇO</b>";
            case NEW_ALL_TIME_LOW -> "🔥 <b>NOVO MENOR PREÇO</b>";
            case EXCELLENT_PRICE -> "🚨 <b>EXCELENTE PREÇO</b>";
            case NEW_OFFER_WITHIN_BUDGET -> "🆕 <b>NOVA OPÇÃO DENTRO DO ORÇAMENTO</b>";
        };
    }

    private String route(FlightOfferEntity offer) {
        return escape(cityOf(offer.originAirport) + " (" + offer.originAirport + ")"
                + " → " + cityOf(offer.destinationAirport) + " (" + offer.destinationAirport + ")");
    }

    /**
     * Dates as printed on the ticket. Uses the stored local calendar values rather than the
     * instants, because Postgres returns those in UTC and a 07:00 departure from Tokyo would be
     * shown as the previous day.
     */
    private String dates(FlightOfferEntity offer) {
        String outbound = brazilianDate(offer.departureLocalDate, offer.departureAt);
        String inbound = offer.returnDepartureLocalDate == null && offer.returnDepartureAt == null
                ? "só ida"
                : brazilianDate(offer.returnDepartureLocalDate, offer.returnDepartureAt);
        String back = arrivalLine(offer);
        return outbound + " → " + inbound + (back == null ? "" : "\n" + back);
    }

    private static String brazilianDate(String localDate, java.time.OffsetDateTime fallback) {
        if (localDate != null && localDate.length() >= 10) {
            return localDate.substring(8, 10) + "/" + localDate.substring(5, 7)
                    + "/" + localDate.substring(0, 4);
        }
        return fallback == null ? "?" : DATE.format(fallback);
    }

    private static String arrivalLine(FlightOfferEntity offer) {
        if (offer.returnArrivalLocalTime != null && offer.returnArrivalLocalTime.length() >= 16) {
            String value = offer.returnArrivalLocalTime;
            return "🛬 Chega ao destino final: " + value.substring(8, 10) + "/"
                    + value.substring(5, 7) + " " + value.substring(11, 16) + " (hora local)";
        }
        return offer.returnArrivalAt == null
                ? null
                : "🛬 Chega ao destino final: " + DATE_TIME.format(offer.returnArrivalAt);
    }

    private String priceSection(AlertType type, FlightOfferEntity offer, PriceChange change) {
        StringBuilder section = new StringBuilder();
        section.append("💰 Agora: <b>").append(money(offer.currentPriceGbp)).append("</b>\n");

        if (change.previousGbp() != null && change.isDrop()) {
            section.append("📉 Antes: ").append(money(change.previousGbp())).append("\n");
            section.append("💸 Economia: ").append(money(change.dropGbp()))
                    .append(" (-").append(percent(change.dropPercent())).append(")\n");
        } else if (change.firstSighting()) {
            section.append("👀 Primeira observação deste itinerário "
                    + "(ainda sem histórico para comparar)\n");
        }

        if (type == AlertType.NEW_ALL_TIME_LOW && change.previousLowestGbp() != null) {
            section.append("📊 Menor anterior: ")
                    .append(money(change.previousLowestGbp())).append("\n");
        }
        section.append("📈 Menor já registrado: ").append(money(offer.lowestPriceGbp))
                .append(" · observações: ").append(change.observationCount());
        return section.toString();
    }

    private String flightSection(FlightOfferEntity offer) {
        StringBuilder section = new StringBuilder();
        section.append("\n✈️ Companhias: ")
                .append(offer.airlines == null || offer.airlines.isBlank()
                        ? "não informado"
                        : escape(offer.airlines.replace(",", " + ")))
                .append("\n");
        section.append("🔄 Escalas: ").append(offer.outboundStops)
                .append(offer.inboundStops == null ? "" : " (ida) / " + offer.inboundStops + " (volta)")
                .append("\n");
        section.append("⏱️ Trecho mais longo: ").append(durationText(offer.longestLegMinutes))
                .append("\n");
        if (offer.layovers != null && !offer.layovers.isBlank()) {
            section.append("🛫 Conexões: ").append(escape(offer.layovers)).append("\n");
        }
        section.append("🧳 Bagagem: ").append(baggageText(offer));
        return section.toString();
    }

    private String baggageText(FlightOfferEntity offer) {
        if (!offer.baggageKnown) {
            return "Baggage information unavailable";
        }
        List<String> parts = new ArrayList<>();
        if (offer.baggageCheckedPieces != null) {
            parts.add(offer.baggageCheckedPieces == 0
                    ? "sem mala despachada incluída"
                    : offer.baggageCheckedPieces + " mala(s) despachada(s) incluída(s)");
        }
        if (Boolean.TRUE.equals(offer.baggageCabinIncluded)) {
            parts.add("bagagem de mão incluída");
        }
        if (parts.isEmpty() && offer.baggageDescription != null) {
            parts.add(offer.baggageDescription);
        }
        return parts.isEmpty()
                ? "Baggage information unavailable"
                : escape(String.join(", ", parts));
    }

    private String bandLine(OfferEvaluation evaluation, TripConfig trip) {
        String price = switch (evaluation.priceBand() == null
                ? PriceBand.ABOVE_BUDGET : evaluation.priceBand()) {
            case EXCELLENT -> "🟢 <b>EXCELENTE</b> — abaixo de " + money(trip.targetMinPriceGbp());
            case WITHIN_BUDGET -> "🟡 <b>DENTRO DO ORÇAMENTO</b>";
            case ABOVE_BUDGET -> "🔴 <b>ACIMA DO ORÇAMENTO</b>";
        };
        String duration = switch (evaluation.durationBand() == null
                ? DurationBand.UNKNOWN : evaluation.durationBand()) {
            case EXCELLENT -> "duração excelente";
            case ACCEPTABLE -> "duração aceitável";
            case LONG -> "viagem longa";
            case VERY_LONG -> "viagem muito longa";
            case REJECTED -> "acima do limite";
            case UNKNOWN -> "duração não informada";
        };
        return "\n" + price + " · " + duration
                + " · score " + String.format(Locale.ROOT, "%.1f", evaluation.score());
    }

    private String sourceLine(List<OfferSourceEntity> sources, List<String> providerCodes) {
        StringBuilder line = new StringBuilder("🔎 Fonte: ")
                .append(escape(String.join(", ", providerCodes)));

        String bookingUrl = sources.stream()
                .map(source -> source.bookingUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);
        String searchUrl = sources.stream()
                .map(source -> source.searchUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);

        if (bookingUrl != null) {
            line.append("\n🔗 <a href=\"").append(escape(bookingUrl)).append("\">Ver passagem</a>");
        } else if (searchUrl != null) {
            line.append("\n🔗 <a href=\"").append(escape(searchUrl))
                    .append("\">Abrir busca no Google Flights</a>")
                    .append("\n<i>(link de busca, não de uma tarifa específica)</i>");
        } else {
            line.append("\n🔗 Sem link direto disponível nesta fonte");
        }
        return line.toString();
    }

    private String cityOf(String iata) {
        return catalog.find(iata).map(airport -> airport.city()).orElse(iata);
    }

    private static String durationText(Integer minutes) {
        if (minutes == null) {
            return "não informado";
        }
        Duration duration = Duration.ofMinutes(minutes);
        long hours = duration.toHours();
        long remainder = duration.toMinutesPart();
        return remainder == 0 ? hours + "h" : hours + "h " + remainder + "min";
    }

    private static String money(BigDecimal amount) {
        if (amount == null) {
            return "?";
        }
        NumberFormat format = NumberFormat.getNumberInstance(PT_BR);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return "£" + format.format(amount);
    }

    private static String percent(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(PT_BR);
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return format.format(value) + "%";
    }

    /** Telegram HTML only needs these three escaped. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Used by the status endpoint and the startup banner. */
    public String formatStartupSummary(TripConfig trip, int providerCount, String providerNames) {
        return "🤖 <b>Flight Monitor iniciado</b>\n"
                + escape(trip.name()) + "\n"
                + "Origens: " + escape(String.join(", ", trip.originAirports())) + "\n"
                + "Destinos: " + escape(String.join(", ", trip.destinationAirports())) + "\n"
                + "Ida alvo: " + DATE.format(trip.targetDepartureDate())
                + " (±" + trip.departureFlexDays() + "d)\n"
                + "Retorno até: " + DATE_TIME.format(trip.latestReturnArrival()) + "\n"
                + "Orçamento: " + money(trip.targetMinPriceGbp())
                + " – " + money(trip.targetMaxPriceGbp()) + "\n"
                + "Providers ativos (" + providerCount + "): " + escape(providerNames);
    }

    /** Kept for parity with the offer formatter when a run produced nothing at all. */
    public String formatProviderOutage(String providerCode, String error) {
        return "🛑 <b>Provider indisponível</b>\n"
                + escape(providerCode) + "\n"
                + escape(error);
    }
}
