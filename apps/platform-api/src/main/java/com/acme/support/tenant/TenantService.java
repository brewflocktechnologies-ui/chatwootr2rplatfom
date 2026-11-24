package com.acme.support.tenant;

import com.acme.support.chatwoot.ChatwootProperties;
import com.acme.support.knowledge.R2rProperties;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Single fixed demo tenant (acme-demo). On startup it records non-secret integration identifiers so the
 * database reflects the configuration that is actually in use. Tokens never reach the database.
 */
@Service
public class TenantService implements ApplicationRunner {

    private final String tenantKey;
    private final ChatwootProperties chatwoot;
    private final R2rProperties r2r;
    private final JdbcClient jdbc;

    public TenantService(@Value("${tenant.key}") String tenantKey, ChatwootProperties chatwoot, R2rProperties r2r,
                         JdbcClient jdbc) {
        this.tenantKey = tenantKey;
        this.chatwoot = chatwoot;
        this.r2r = r2r;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"acme-demo".equals(tenantKey)) {
            throw new IllegalStateException("TENANT_KEY must be acme-demo for this MVP");
        }
        jdbc.sql("""
                insert into tenant_integrations (tenant_key, chatwoot_account_id, chatwoot_inbox_id, chatwoot_team_id, r2r_collection_id)
                values (:t, :a, :i, :m, :c)
                on conflict (tenant_key) do update set chatwoot_account_id = excluded.chatwoot_account_id,
                    chatwoot_inbox_id = excluded.chatwoot_inbox_id, chatwoot_team_id = excluded.chatwoot_team_id,
                    r2r_collection_id = excluded.r2r_collection_id, updated_at = now()
                """).param("t", tenantKey).param("a", chatwoot.accountId()).param("i", chatwoot.inboxId())
                .param("m", chatwoot.teamId()).param("c", r2r.collectionId()).update();
    }

    public String tenantKey() {
        return tenantKey;
    }

    /** Non-secret view for the admin API. Shape frozen in api-contract.md. */
    public Map<String, Object> summary() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("tenantKey", tenantKey);
        m.put("displayName", jdbc.sql("select display_name from tenant where tenant_key = :t")
                .param("t", tenantKey).query(String.class).single());
        m.put("chatwootAccountId", chatwoot.accountId());
        m.put("inboxId", chatwoot.inboxId());
        m.put("teamId", chatwoot.teamId());
        m.put("agentBotId", chatwoot.agentBotId());
        m.put("r2rCollectionId", r2r.collectionId().toString());
        return m;
    }
}
