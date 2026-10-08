package com.provider.nps.client;

import static org.junit.jupiter.api.Assertions.*;

import com.provider.nps.crypto.NpsXmlEncryptor;
import com.provider.nps.crypto.NpsXmlSigner;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NpsClientTest {
    private HttpServer server;
    private final AtomicInteger refreshes = new AtomicInteger();
    private final List<String> bodies = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private NpsClient client(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nps/pacs/008", exchange -> {
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
        var response = client.sendCreditTransfer("<Request>same-id</Request>");
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
        var response = client.sendCreditTransfer("<Request/>");
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
        var response = client.sendCreditTransfer("<Request/>");
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
        assertThrows(HttpTimeoutException.class, () -> client.sendCreditTransfer("<Request/>"));
        assertEquals(1, bodies.size());
    }
}
