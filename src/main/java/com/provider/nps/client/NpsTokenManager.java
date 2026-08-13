package com.provider.nps.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * NPS OAuth2 Token Manager.
 * 
 * Handles client_credentials flow to obtain access tokens from NIBSS.
 * Token endpoint: POST https://apitest.nibss-plc.com.ng:1443/reset
 * 
 * Features:
 * - Automatic token refresh before expiry
 * - Thread-safe token access
 * - Retry logic for transient failures
 */
@ApplicationScoped
public class NpsTokenManager {

    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    @ConfigProperty(name = "nps.token-url")
    String tokenUrl;

    @ConfigProperty(name = "nps.client-id")
    String clientId;

    @ConfigProperty(name = "nps.client-secret")
    String clientSecret;

    @ConfigProperty(name = "nps.token-refresh-margin-seconds", defaultValue = "300")
    int tokenRefreshMarginSeconds;

    @ConfigProperty(name = "nps.timeout-ms", defaultValue = "60000")
    int timeoutMs;

    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpClient httpClient;
    private String accessToken;
    private Instant tokenExpiry;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        // Fetch initial token
        try {
            refreshToken();
            Log.info("NpsTokenManager initialized with valid token");
        } catch (Exception e) {
            Log.warn("Failed to fetch initial NPS token, will retry on first request", e);
        }
    }

    /**
     * Gets a valid access token, refreshing if necessary.
     * 
     * @return The current valid access token
     * @throws NpsTokenException if token cannot be obtained
     */
    public String getAccessToken() {
        lock.lock();
        try {
            if (isTokenValid()) {
                return accessToken;
            }

            refreshToken();
            return accessToken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if we have a valid token that won't expire soon.
     */
    private boolean isTokenValid() {
        if (accessToken == null || tokenExpiry == null) {
            return false;
        }
        // Token is valid if it won't expire within the margin
        return Instant.now().plusSeconds(tokenRefreshMarginSeconds).isBefore(tokenExpiry);
    }

    /**
     * Refreshes the access token from NIBSS.
     */
    private void refreshToken() {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                fetchNewToken();
                Log.infof("NPS token refreshed successfully, expires at %s", tokenExpiry);
                return;
            } catch (Exception e) {
                lastException = e;
                Log.warnf("Token fetch attempt %d failed: %s", attempt, e.getMessage());

                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY.toMillis() * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new NpsTokenException("Token refresh interrupted", ie);
                    }
                }
            }
        }

        throw new NpsTokenException("Failed to refresh NPS token after " + MAX_RETRIES + " attempts", lastException);
    }

    private void fetchNewToken() throws Exception {
        // Build Basic auth header
        String credentials = clientId + ":" + clientSecret;
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

        // Build request body (client_credentials grant)
        String requestBody = "grant_type=client_credentials";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", basicAuth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        Log.debugf("Fetching NPS token from %s", tokenUrl);

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            Log.errorf("Token request failed with status %d: %s", response.statusCode(), response.body());
            throw new NpsTokenException("Token request failed with status " + response.statusCode());
        }

        parseTokenResponse(response.body());
    }

    private void parseTokenResponse(String responseBody) throws Exception {
        JsonNode json = objectMapper.readTree(responseBody);

        if (!json.has("access_token")) {
            throw new NpsTokenException("Token response missing access_token: " + responseBody);
        }

        accessToken = json.get("access_token").asText();

        // Calculate expiry (default to 3600 seconds if not provided)
        int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt() : 3600;
        tokenExpiry = Instant.now().plusSeconds(expiresIn);

        Log.debugf("Token obtained, expires in %d seconds", expiresIn);
    }

    /**
     * Scheduled task to proactively refresh token before expiry.
     * Runs every 5 minutes to check if token needs refresh.
     */
    @Scheduled(every = "5m")
    void scheduledTokenRefresh() {
        if (!isTokenValid()) {
            Log.info("Scheduled token refresh triggered");
            lock.lock();
            try {
                if (!isTokenValid()) {
                    refreshToken();
                }
            } catch (Exception e) {
                Log.error("Scheduled token refresh failed", e);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Forces a token refresh. Use after receiving 401 from NPS.
     */
    public void forceRefresh() {
        lock.lock();
        try {
            accessToken = null;
            tokenExpiry = null;
            refreshToken();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns time until token expiry in seconds, or -1 if no valid token.
     */
    public long getSecondsUntilExpiry() {
        if (tokenExpiry == null) {
            return -1;
        }
        return Duration.between(Instant.now(), tokenExpiry).getSeconds();
    }

    /**
     * Exception thrown when token operations fail.
     */
    public static class NpsTokenException extends RuntimeException {

        public NpsTokenException(String message) {
            super(message);
        }

        public NpsTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
