package com.acme.support.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Admin API key from PLATFORM_ADMIN_API_KEY. Startup fails when it is shorter than 32 characters. */
@ConfigurationProperties("platform")
public record AdminProperties(String adminApiKey) {

    public AdminProperties {
        if (adminApiKey == null || adminApiKey.length() < 32) {
            throw new IllegalStateException("PLATFORM_ADMIN_API_KEY must be set and at least 32 characters");
        }
    }

    @Override
    public String toString() {
        return "AdminProperties[adminApiKey=redacted]";
    }
}
