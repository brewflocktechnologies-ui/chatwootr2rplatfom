package com.acme.support.admin;

import com.acme.support.chatwoot.ChatwootProperties;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo-page configuration. Only the public website token and base URL; never any secret. */
@RestController
public class PublicController {

    private final ChatwootProperties chatwoot;

    public PublicController(ChatwootProperties chatwoot) {
        this.chatwoot = chatwoot;
    }

    @GetMapping("/api/public/widget-config")
    public Map<String, String> widgetConfig() {
        return Map.of("websiteToken", chatwoot.websiteToken(), "chatwootBaseUrl", chatwoot.publicBaseUrl());
    }
}
