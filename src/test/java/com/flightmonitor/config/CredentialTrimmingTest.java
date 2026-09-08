package com.flightmonitor.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Credentials are pasted by hand, and stray whitespace is the most common way a correct token
 * still fails. It is also the most confusing, because a properties file strips it and a real
 * environment variable does not — so the same {@code .env} works locally and fails in Docker.
 */
class CredentialTrimmingTest {

    @Test
    @DisplayName("a chat id pasted with a leading space still reaches Telegram intact")
    void trimsTelegramCredentials() {
        TelegramConfig config = new TelegramConfig(
                true, "  123456:AAE-token  ", " -1001234567890\n", null, 0);

        assertThat(config.botToken()).isEqualTo("123456:AAE-token");
        assertThat(config.chatId()).isEqualTo("-1001234567890");
        assertThat(config.configured()).isTrue();
        assertThat(config.sendMessageUrl())
                .isEqualTo("https://api.telegram.org/bot123456:AAE-token/sendMessage");
    }

    @Test
    @DisplayName("whitespace-only credentials count as absent, not as configured")
    void blankCredentialsAreNotConfigured() {
        assertThat(new TelegramConfig(true, "   ", "-100123", null, 0).configured()).isFalse();
        assertThat(new TelegramConfig(true, "123:abc", "  ", null, 0).configured()).isFalse();
    }

    @Test
    @DisplayName("a provider key pasted with trailing whitespace is cleaned before use")
    void trimsProviderKey() {
        ProviderConfig config = new ProviderConfig(
                true, " https://serpapi.com ", "abc123  ", 0, 0, 0, 0, 0, 0, 0);

        assertThat(config.apiKey()).isEqualTo("abc123");
        assertThat(config.baseUrl()).isEqualTo("https://serpapi.com");
        assertThat(config.hasApiKey()).isTrue();
        assertThat(config.usable(true)).isTrue();
    }

    @Test
    @DisplayName("a whitespace-only provider key leaves the provider unusable")
    void blankProviderKeyIsNotUsable() {
        ProviderConfig config = new ProviderConfig(true, null, "   ", 0, 0, 0, 0, 0, 0, 0);

        assertThat(config.hasApiKey()).isFalse();
        assertThat(config.usable(true)).isFalse();
        assertThat(config.usable(false))
                .as("a provider that needs no key stays usable")
                .isTrue();
    }
}
