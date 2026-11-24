package com.acme.support.knowledge;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("r2r")
public record R2rProperties(
        @NotBlank String baseUrl,
        @NotBlank String serviceEmail,
        @NotBlank String servicePassword,
        @NotNull UUID collectionId,
        Duration connectTimeout,
        Duration readTimeout) {

    @Override
    public String toString() {
        return "R2rProperties[baseUrl=" + baseUrl + ", collectionId=" + collectionId + ", secrets=redacted]";
    }
}
