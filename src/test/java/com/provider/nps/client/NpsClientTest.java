package com.provider.nps.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.provider.nps.crypto.NpsXmlEncryptor;
import com.provider.nps.crypto.NpsXmlSigner;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NpsClientTest {
    private HttpServer server;
    private final AtomicInteger refreshes = new AtomicInteger();
    private final List<String> bodies = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private NpsClient client(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nps/pacs", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        NpsClient client = new NpsClient();
        client.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client.timeoutMs = 2000;
        client.skipCrypto = true;
        client.tokenManager = new NpsTokenManager() {
            @Override
            public String getAccessToken(java.time.Duration budget) {
                return "test-token";
            }

            @Override
            public void forceRefresh(java.time.Duration budget) {
                refreshes.incrementAndGet();
            }
        };
        client.init();
        return client;
    }

    @Test
    void persistentUnauthorizedResponseRetriesOnlyOnceWithIdenticalPayload() throws Exception {
        var client = client(401, "Unauthorized");
        var response = client.sendCreditTransfer("ref-1", "<Request>same-id</Request>");
        assertFalse(response.isSuccess());
        assertEquals(401, response.getHttpStatus());
        assertEquals(1, refreshes.get());
        assertEquals(List.of("<Request>same-id</Request>", "<Request>same-id</Request>"), bodies);
    }

    @Test
    void invalidSignatureDoesNotExposeAParsedPaymentStatus() throws Exception {
        var client = client(200, "<Document><TxSts>ACSP</TxSts></Document>");
        client.skipCrypto = false;
        client.xmlSigner = new NpsXmlSigner() {
            @Override
            public String sign(String xml) {
                return xml;
            }
            @Override
            public boolean verify(String xml) {
                return false;
            }
        };
        client.xmlEncryptor = new NpsXmlEncryptor() {
            @Override
            public String encrypt(String xml) {
                return xml;
            }
            @Override
            public String decrypt(String xml) {
                return xml;
            }
        };
        var response = client.sendCreditTransfer("ref-1", "<Request/>");
        assertFalse(response.isSuccess());
        assertFalse(response.isSignatureValid());
        assertNull(response.getDecryptedResponse());
        assertNull(response.getResponseCode());
        assertEquals("Invalid NPS response signature", response.getErrorMessage());
    }

    @Test
    void parsesPrefixedResponseFields() throws Exception {
        var client = client(200,
                "<p:Document xmlns:p='urn:test'><p:MsgId>report-id</p:MsgId><p:TxSts>ACSC</p:TxSts></p:Document>");
        var response = client.sendCreditTransfer("ref-1", "<Request/>");
        assertTrue(response.isTransactionSuccessful());
        assertEquals("report-id", response.getMessageId());
    }

    @Test
    void tokenRefreshCannotStartAnotherRequestAfterDeadline() throws Exception {
        var client = client(401, "Unauthorized");
        client.timeoutMs = 1000;
        client.tokenManager = new NpsTokenManager() {
            @Override
            public String getAccessToken(java.time.Duration budget) {
                return "token";
            }
            @Override
            public void forceRefresh(java.time.Duration budget) {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
        assertThrows(HttpTimeoutException.class, () -> client.sendCreditTransfer("ref-1", "<Request/>"));
        assertEquals(1, bodies.size());
    }
    @Test
    void operationPathCanBeConfiguredWithoutChangingSharedDefault() throws Exception {
        var client = client(200, "<Document><GrpSts>ACSC</GrpSts></Document>");
        assertTrue(client.sendCreditTransfer("ref-1", "<Request/>").isTransactionSuccessful());
        client.creditTransferPath = "/nps/pacs/008";
        assertTrue(client.sendCreditTransfer("ref-1", "<Request/>").isTransactionSuccessful());
        assertEquals(List.of("/nps/pacs", "/nps/pacs/008"), paths);
    }
    @Test
    void contradictoryAuthorizationDoesNotConfirmAnOutcome() {
        for (String values : new String[]{"ACSC NAUT", "ACSC NOAN", "ACSC UNKNOWN", "RJCT AUTH"}) {
            var pair = values.split(" ");
            var response = new NpsClient.NpsResponse();
            response.setSuccess(true);
            response.setResponseCode(pair[0]);
            response.setStatusId(pair[1]);
            assertFalse(response.isTransactionSuccessful());
            assertFalse(response.isTransactionRejected());
        }
    }

    @Test
    void everyExchangeIsLoggedInTheLoggerServiceShape() throws Exception {
        var client = client(200, "<Document><TxSts>ACSC</TxSts></Document>");
        var events = capturedClientLogs(client);
        client.sendCreditTransfer("ref-1", "<Request/>");

        assertEquals(1, events.size());
        JsonNode event = new ObjectMapper().readTree(events.get(0));
        assertEquals("nps", event.get("provider").asText());
        assertEquals("provider-nps", event.get("service").asText());
        assertEquals("ref-1", event.get("reference").asText());
        assertEquals("POST", event.get("method").asText());
        assertEquals(client.baseUrl + "/nps/pacs", event.get("url").asText());
        assertEquals("<Request/>", event.get("request_body").asText());
        assertEquals(200, event.get("response_status").asInt());
        assertEquals("<Document><TxSts>ACSC</TxSts></Document>", event.get("response_body").asText());
        assertTrue(event.has("duration_ms"));
        assertTrue(event.has("timestamp"));
    }

    @Test
    void transportFailureIsLoggedAsStatusZero() throws Exception {
        var client = client(200, "<Document/>");
        server.stop(0);
        server = null;
        var events = capturedClientLogs(client);
        assertThrows(java.io.IOException.class, () -> client.sendCreditTransfer("ref-1", "<Request/>"));

        assertEquals(1, events.size());
        assertEquals(0, new ObjectMapper().readTree(events.get(0)).get("response_status").asInt());
    }

    private static List<String> capturedClientLogs(NpsClient client) {
        List<String> events = new ArrayList<>();
        client.clientLogs = new ClientLogPublisher();
        client.clientLogs.emitter = new Emitter<>() {
            @Override
            public CompletionStage<Void> send(String payload) {
                events.add(payload);
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public <M extends Message<? extends String>> void send(M message) {
                events.add(message.getPayload());
            }
            @Override
            public void complete() {
            }
            @Override
            public void error(Exception e) {
            }
            @Override
            public boolean isCancelled() {
                return false;
            }
            @Override
            public boolean hasRequests() {
                return true;
            }
        };
        return events;
    }
}
