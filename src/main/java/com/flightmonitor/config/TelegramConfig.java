package com.flightmonitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Telegram credentials. Both values come from the environment; neither has a default, so a missing
 * token disables notifications loudly rather than shipping a placeholder.
 */
@ConfigurationProperties(prefix = "telegram")
public record TelegramConfig(
        boolean enabled,
        String botToken,
        String chatId,
        String apiBaseUrl,
        int timeoutSeconds) {

    public TelegramConfig {
        // Credentials arrive by copy-paste, and a stray space or newline is easy to bring along.
        // Worse, it is inconsistent: a properties file strips it, a real environment variable does
        // not, so the same .env would work locally and fail in Docker with "chat not found".
        // Trimming here removes the whole class of problem.
        botToken = trim(botToken);
        chatId = trim(chatId);
        apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? "https://api.telegram.org"
                : trim(apiBaseUrl);
        timeoutSeconds = timeoutSeconds <= 0 ? 15 : timeoutSeconds;
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    public boolean configured() {
        return enabled
                && botToken != null && !botToken.isBlank()
                && chatId != null && !chatId.isBlank();
    }

    public String sendMessageUrl() {
        return apiBaseUrl + "/bot" + botToken + "/sendMessage";
    }
}
