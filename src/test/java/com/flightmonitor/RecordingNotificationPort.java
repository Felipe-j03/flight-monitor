package com.flightmonitor;

import com.flightmonitor.domain.port.NotificationPort;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Captures alerts instead of sending them, so the pipeline can be asserted end to end. */
public class RecordingNotificationPort implements NotificationPort {

    private final List<String> messages = new ArrayList<>();
    private boolean failNext;

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String channelName() {
        return "recording";
    }

    @Override
    public DeliveryResult send(String message) {
        messages.add(message);
        if (failNext) {
            failNext = false;
            return DeliveryResult.failed("simulated delivery failure");
        }
        return DeliveryResult.ok();
    }

    public List<String> messages() {
        return List.copyOf(messages);
    }

    public void clear() {
        messages.clear();
    }

    public void failNextDelivery() {
        this.failNext = true;
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public RecordingNotificationPort recordingNotificationPort() {
            return new RecordingNotificationPort();
        }
    }
}
