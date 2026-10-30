package com.provider.nps.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NpsTokenManagerTest {
    @Test
    void tokenRetryCannotOutliveCallerBudget() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            int request = requests.incrementAndGet();
            String body = request == 1 ? "{\"access_token\":\"valid-token\",\"expires_in\":3600}" : "Unavailable";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(request == 1 ? 200 : 503, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            var manager = new NpsTokenManager();
            manager.tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
            manager.clientId = Optional.of("client");
            manager.clientSecret = Optional.of("secret");
            manager.timeoutMs = 2000;
            manager.tokenRefreshMarginSeconds = 300;
            manager.init();
            assertEquals("valid-token", manager.getAccessToken());
            long started = System.nanoTime();
            assertThrows(NpsTokenManager.NpsTokenException.class, () -> manager.forceRefresh(Duration.ofMillis(500)));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500);
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingCredentialsNeverCallNibss() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            var manager = new NpsTokenManager();
            manager.tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
            manager.timeoutMs = 2000;
            manager.tokenRefreshMarginSeconds = 300;
            manager.init();
            manager.scheduledTokenRefresh();
            var failure = assertThrows(NpsTokenManager.NpsTokenException.class, manager::getAccessToken);
            assertEquals("NPS client credentials are not configured", failure.getMessage());
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
