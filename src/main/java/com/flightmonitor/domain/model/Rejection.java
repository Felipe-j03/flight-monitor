package com.flightmonitor.domain.model;

/**
 * A single reason an offer failed, with the concrete value that triggered it so the log line reads
 * {@code OFFER_REJECTED reason=BLOCKED_COUNTRY country=Qatar} rather than just a category.
 */
public record Rejection(RejectionReason reason, String detail) {

    public static Rejection of(RejectionReason reason, String detail) {
        return new Rejection(reason, detail);
    }

    @Override
    public String toString() {
        return detail == null ? reason.name() : reason.name() + "(" + detail + ")";
    }
}
