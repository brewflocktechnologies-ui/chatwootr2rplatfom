package com.acme.support.admin;

import com.acme.support.shared.Http;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Diagnostics probe for Ollama at OLLAMA_BASE_URL/api/tags. UP on HTTP 200, else DOWN. */
@Component
public class OllamaProbe {

    private final RestClient http;

    public OllamaProbe(@Value("${ollama.base-url}") String baseUrl) {
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(Http.factory(Duration.ofSeconds(2), Duration.ofSeconds(3)))
                .build();
    }

    /** Returns {state, detail}. Detail never includes response bodies. */
    public Result probe() {
        try {
            var entity = http.get().uri("/api/tags").retrieve().toBodilessEntity();
            int status = entity.getStatusCode().value();
            return status == 200
                    ? new Result("UP", "HTTP 200")
                    : new Result("DOWN", "HTTP " + status);
        } catch (RestClientResponseException e) {
            return new Result("DOWN", "HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            return new Result("DOWN", "unreachable (" + e.getClass().getSimpleName() + ")");
        }
    }

    public record Result(String state, String detail) {
    }
}
