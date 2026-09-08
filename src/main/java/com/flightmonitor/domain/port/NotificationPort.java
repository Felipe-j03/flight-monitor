package com.flightmonitor.domain.port;

/**
 * Outbound notification channel. Kept as a port so the alerting logic has no idea Telegram exists
 * and can be tested against a stub.
 */
public interface NotificationPort {

    /** True when the channel has everything it needs to actually deliver. */
    boolean configured();

    String channelName();

    DeliveryResult send(String message);

    record DeliveryResult(boolean delivered, String error) {

        public static DeliveryResult ok() {
            return new DeliveryResult(true, null);
        }

        public static DeliveryResult failed(String error) {
            return new DeliveryResult(false, error);
        }
    }
}
