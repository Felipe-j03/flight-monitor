package com.flightmonitor.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.flightmonitor.config.TelegramConfig;
import com.flightmonitor.domain.port.NotificationPort;
import com.flightmonitor.notification.telegram.TelegramNotificationService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

/** Telegram delivery against a stubbed Bot API — no token, no network, no real chat. */
class TelegramNotificationServiceTest {

    private static final String TOKEN = "123456:TEST-TOKEN";
    private static final String CHAT_ID = "-1001234567890";

    private WireMockServer telegram;

    @BeforeEach
    void startStub() {
        telegram = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        telegram.start();
    }

    @AfterEach
    void stopStub() {
        telegram.stop();
    }

    private TelegramNotificationService serviceWith(boolean enabled, String token, String chatId) {
        TelegramConfig config = new TelegramConfig(
                enabled, token, chatId, "http://localhost:" + telegram.port(), 5);
        return new TelegramNotificationService(config, new RestTemplateBuilder());
    }

    @Test
    @DisplayName("posts the message to sendMessage and reports success")
    void sendsMessage() {
        telegram.stubFor(post(urlEqualTo("/bot" + TOKEN + "/sendMessage"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":{\"message_id\":42}}")));

        NotificationPort.DeliveryResult result =
                serviceWith(true, TOKEN, CHAT_ID).send("<b>QUEDA DE PRECO</b>");

        assertThat(result.delivered()).isTrue();
        assertThat(result.error()).isNull();

        telegram.verify(postRequestedFor(urlEqualTo("/bot" + TOKEN + "/sendMessage"))
                .withRequestBody(matchingJsonPath("$.chat_id", equalTo(CHAT_ID)))
                .withRequestBody(matchingJsonPath("$.parse_mode"))
                .withRequestBody(matchingJsonPath("$.text")));
    }

    @Test
    @DisplayName("reports the API's description when Telegram rejects the message")
    void reportsApiError() {
        telegram.stubFor(post(urlEqualTo("/bot" + TOKEN + "/sendMessage"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"description\":\"chat not found\"}")));

        NotificationPort.DeliveryResult result = serviceWith(true, TOKEN, CHAT_ID).send("hello");

        assertThat(result.delivered()).isFalse();
        assertThat(result.error()).isEqualTo("chat not found");
    }

    @Test
    @DisplayName("reports a transport failure instead of throwing into the search run")
    void survivesTransportFailure() {
        telegram.stubFor(post(urlEqualTo("/bot" + TOKEN + "/sendMessage"))
                .willReturn(aResponse().withStatus(500)));

        NotificationPort.DeliveryResult result = serviceWith(true, TOKEN, CHAT_ID).send("hello");

        assertThat(result.delivered()).isFalse();
        assertThat(result.error()).isNotBlank();
    }

    @Test
    @DisplayName("does not call the API at all when no token is configured")
    void skipsWhenUnconfigured() {
        NotificationPort.DeliveryResult result = serviceWith(true, "", CHAT_ID).send("hello");

        assertThat(result.delivered()).isFalse();
        assertThat(result.error()).contains("TELEGRAM_BOT_TOKEN");
        assertThat(telegram.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("reports itself as unconfigured when notifications are switched off")
    void disabledIsNotConfigured() {
        assertThat(serviceWith(false, TOKEN, CHAT_ID).configured()).isFalse();
        assertThat(serviceWith(true, TOKEN, CHAT_ID).configured()).isTrue();
    }

    @Test
    @DisplayName("trims a message longer than Telegram accepts rather than failing")
    void trimsOverlongMessage() {
        telegram.stubFor(post(urlEqualTo("/bot" + TOKEN + "/sendMessage"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));

        String huge = "x".repeat(5000);
        assertThat(serviceWith(true, TOKEN, CHAT_ID).send(huge).delivered()).isTrue();

        telegram.verify(postRequestedFor(urlEqualTo("/bot" + TOKEN + "/sendMessage"))
                .withRequestBody(matchingJsonPath("$.text")));
    }
}
