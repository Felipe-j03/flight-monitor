package com.flightmonitor.notification.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.flightmonitor.config.TelegramConfig;
import com.flightmonitor.domain.port.NotificationPort;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Telegram Bot API delivery. Deliberately knows nothing about flights: it takes a formatted string
 * and posts it, which keeps the alerting rules and the message wording independently testable.
 *
 * <p>The bot token never appears in code or in a log line — it comes from the environment and is
 * only ever interpolated into the request URL.
 */
@Service
public class TelegramNotificationService implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    /** Telegram rejects anything longer; messages are trimmed rather than dropped. */
    private static final int MAX_MESSAGE_LENGTH = 4096;

    private final TelegramConfig config;
    private final RestTemplate http;

    public TelegramNotificationService(TelegramConfig config, RestTemplateBuilder builder) {
        this.config = config;
        this.http = builder
                .setConnectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }

    @Override
    public boolean configured() {
        return config.configured();
    }

    @Override
    public String channelName() {
        return "Telegram";
    }

    @Override
    public DeliveryResult send(String message) {
        if (!configured()) {
            String reason = "Telegram is not configured "
                    + "(set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID)";
            log.warn("ALERT_NOT_SENT {}", reason);
            return DeliveryResult.failed(reason);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", config.chatId());
        payload.put("text", trim(message));
        payload.put("parse_mode", "HTML");
        payload.put("disable_web_page_preview", true);

        try {
            var headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            JsonNode response = http.postForObject(
                    config.sendMessageUrl(),
                    new org.springframework.http.HttpEntity<>(payload, headers),
                    JsonNode.class);

            if (response != null && response.path("ok").asBoolean(false)) {
                log.info("ALERT_SENT channel=Telegram chars={}", trim(message).length());
                return DeliveryResult.ok();
            }
            String description = response == null
                    ? "empty response"
                    : response.path("description").asText("unknown error");
            log.error("ALERT_SEND_FAILED channel=Telegram reason={}", description);
            return DeliveryResult.failed(description);
        } catch (RuntimeException e) {
            log.error("ALERT_SEND_FAILED channel=Telegram error={}", e.toString());
            return DeliveryResult.failed(e.toString());
        }
    }

    private static String trim(String message) {
        return message.length() <= MAX_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
    }
}
