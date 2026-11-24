package com.acme.support.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties("agent")
public record AgentProperties(
        @DecimalMin("0.0") @DecimalMax("1.0") double evidenceMinScore,
        @NotBlank String model,
        @NotBlank String embeddingModel,
        @Min(1) int retrievalLimit) {
}
