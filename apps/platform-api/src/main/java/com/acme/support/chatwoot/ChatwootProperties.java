package com.acme.support.chatwoot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties("chatwoot")
public record ChatwootProperties(
        @NotBlank String baseUrl,
        @Positive long accountId,
        @Positive long inboxId,
        @Positive long teamId,
        @Positive long agentBotId,
        @NotBlank String apiToken,
        @NotBlank String botAccessToken,
        @NotBlank String botSecret,
        @NotBlank String websiteToken,
        @NotBlank String publicBaseUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    @Override
    public String toString() {
        return "ChatwootProperties[baseUrl=" + baseUrl + ", accountId=" + accountId + ", inboxId=" + inboxId
                + ", teamId=" + teamId + ", secrets=redacted]";
    }
}
