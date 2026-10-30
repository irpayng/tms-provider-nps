package com.provider.nps.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

/**
 * Publishes each NIBSS exchange to the shared client-logs topic in the shape
 * the logger service persists (the same fields provider-client emits).
 *
 * Bodies are the wire payloads, which NIBSS requires to be signed and
 * encrypted, so account and identity data stay encrypted at rest.
 */
@ApplicationScoped
public class ClientLogPublisher {

    static final String PROVIDER = "nps";
    static final String SERVICE = "provider-nps";
    private static final int MAX_BODY = 65000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    @Channel("client-logs")
    Emitter<String> emitter;

    public void publish(String reference, String url, String requestBody, int responseStatus, String responseBody,
            long durationMs) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("provider", PROVIDER);
            event.put("service", SERVICE);
            event.put("reference", reference != null ? reference : "");
            event.put("method", "POST");
            event.put("url", url);
            event.put("request_body", truncate(requestBody));
            event.put("response_status", responseStatus);
            event.put("response_body", truncate(responseBody));
            event.put("duration_ms", durationMs);
            event.put("mocked", false);
            event.put("timestamp", Instant.now().toString());
            emitter.send(objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            Log.warnf("Failed to publish client log for %s: %s", reference, e.getMessage());
        }
    }

    private static String truncate(String body) {
        if (body == null || body.length() <= MAX_BODY)
            return body;
        return body.substring(0, MAX_BODY) + "...[truncated]";
    }
}
