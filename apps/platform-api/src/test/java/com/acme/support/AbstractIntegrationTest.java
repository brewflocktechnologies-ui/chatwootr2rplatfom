package com.acme.support;

import java.util.UUID;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL (pgvector/pgvector:pg16, the platform DB image family) via Testcontainers.
 * Upstream clients are configured to unreachable local ports; integration tests mock them explicitly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "worker.poll-interval-ms=3600000")
@AutoConfigureMockMvc
@Testcontainers
public abstract class AbstractIntegrationTest {

    public static final String BOT_SECRET = "test-bot-secret-0123456789abcdef";
    public static final String ADMIN_KEY = "test-admin-api-key-0123456789abcdefghij";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry r) {
        r.add("PLATFORM_DB_URL", POSTGRES::getJdbcUrl);
        r.add("PLATFORM_DB_USER", POSTGRES::getUsername);
        r.add("PLATFORM_DB_PASSWORD", POSTGRES::getPassword);
        r.add("TENANT_KEY", () -> "acme-demo");
        r.add("CHATWOOT_BASE_URL", () -> "http://127.0.0.1:9");
        r.add("CHATWOOT_ACCOUNT_ID", () -> "4");
        r.add("CHATWOOT_INBOX_ID", () -> "1");
        r.add("CHATWOOT_TEAM_ID", () -> "1");
        r.add("CHATWOOT_AGENT_BOT_ID", () -> "1");
        r.add("CHATWOOT_API_TOKEN", () -> "test-admin-token-not-real");
        r.add("CHATWOOT_BOT_ACCESS_TOKEN", () -> "test-bot-token-not-real");
        r.add("CHATWOOT_BOT_SECRET", () -> BOT_SECRET);
        r.add("CHATWOOT_WEBSITE_TOKEN", () -> "test-website-token-not-real");
        r.add("R2R_BASE_URL", () -> "http://127.0.0.1:9");
        r.add("R2R_SERVICE_EMAIL", () -> "service@acme-demo.example.com");
        r.add("R2R_SERVICE_PASSWORD", () -> "test-only-password");
        r.add("R2R_COLLECTION_ID", () -> "6a1f5c2e-0000-4000-8000-000000000001");
        r.add("OLLAMA_BASE_URL", () -> "http://127.0.0.1:9");
        r.add("PLATFORM_ADMIN_API_KEY", () -> ADMIN_KEY);
    }

    @Autowired
    protected JdbcClient jdbc;

    protected void resetPlatformState() {
        jdbc.sql("delete from agent_execution").update();
        jdbc.sql("delete from handoff_record").update();
        jdbc.sql("delete from admin_audit").update();
        jdbc.sql("delete from webhook_inbox").update();
        jdbc.sql("delete from conversation_control").update();
    }

    protected String newDelivery() {
        return UUID.randomUUID().toString();
    }
}
