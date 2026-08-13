package com.provider.nps.logging;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.provider.nps.client.NpsClient.NpsResponse;

import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;

/**
 * Kafka-based transaction logger for NPS operations.
 * 
 * Logs all NPS transactions to Kafka for:
 * - Audit trail
 * - Debugging
 * - Analytics
 * - Compliance reporting
 */
@ApplicationScoped
public class NpsTransactionLogger {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    @Channel("nps-logs")
    Emitter<String> npsLogsEmitter;

    @Inject
    @Channel("client-logs")
    Emitter<String> clientLogsEmitter;

    /**
     * Logs an outbound NPS request and response.
     */
    public void logTransaction(NpsTransactionLog log) {
        try {
            String json = objectMapper.writeValueAsString(log);

            var metadata = OutgoingKafkaRecordMetadata.<String>builder()
                    .withKey(log.reference)
                    .build();

            npsLogsEmitter.send(Message.of(json).addMetadata(metadata));

            Log.debugf("Logged NPS transaction: ref=%s, action=%s, status=%s", 
                    log.reference, log.action, log.status);

        } catch (Exception e) {
            Log.error("Failed to log NPS transaction", e);
        }
    }

    /**
     * Logs a name enquiry operation.
     */
    public void logNameEnquiry(String reference, String accountNumber, String bankCode,
            NpsResponse response, long durationMs) {
        
        NpsTransactionLog log = new NpsTransactionLog();
        log.reference = reference;
        log.action = "name-enquiry";
        log.messageType = "acmt.023";
        log.accountNumber = accountNumber;
        log.bankCode = bankCode;
        log.status = response.isSuccess() ? "SUCCESS" : "FAILED";
        log.responseCode = response.getResponseCode();
        log.providerReference = response.getMessageId();
        log.durationMs = durationMs;
        log.timestamp = Instant.now().toString();
        log.requestXml = sanitizeXml(response.getRawRequest());
        log.responseXml = sanitizeXml(response.getRawResponse());

        logTransaction(log);
    }

    /**
     * Logs a bank transfer operation.
     */
    public void logBankTransfer(String reference, String creditorAccount, String creditorBankCode,
            String amount, NpsResponse response, long durationMs) {
        
        NpsTransactionLog log = new NpsTransactionLog();
        log.reference = reference;
        log.action = "bank-transfer";
        log.messageType = "pacs.008";
        log.accountNumber = creditorAccount;
        log.bankCode = creditorBankCode;
        log.amount = amount;
        log.status = mapTransactionStatus(response);
        log.responseCode = response.getResponseCode();
        log.providerReference = response.getMessageId();
        log.durationMs = durationMs;
        log.timestamp = Instant.now().toString();
        log.requestXml = sanitizeXml(response.getRawRequest());
        log.responseXml = sanitizeXml(response.getRawResponse());

        logTransaction(log);
    }

    /**
     * Logs a requery operation.
     */
    public void logRequery(String reference, String originalMsgId,
            NpsResponse response, long durationMs) {
        
        NpsTransactionLog log = new NpsTransactionLog();
        log.reference = reference;
        log.action = "requery";
        log.messageType = "pacs.028";
        log.originalMessageId = originalMsgId;
        log.status = mapTransactionStatus(response);
        log.responseCode = response.getResponseCode();
        log.providerReference = response.getMessageId();
        log.durationMs = durationMs;
        log.timestamp = Instant.now().toString();
        log.requestXml = sanitizeXml(response.getRawRequest());
        log.responseXml = sanitizeXml(response.getRawResponse());

        logTransaction(log);
    }

    /**
     * Logs a client-level event (for the client-logs topic used by other services).
     */
    public void logClientEvent(String reference, String action, String status, String message) {
        try {
            ClientLogEvent event = new ClientLogEvent();
            event.reference = reference;
            event.provider = "NPS";
            event.action = action;
            event.status = status;
            event.message = message;
            event.timestamp = Instant.now().toString();

            String json = objectMapper.writeValueAsString(event);

            var metadata = OutgoingKafkaRecordMetadata.<String>builder()
                    .withKey(reference)
                    .build();

            clientLogsEmitter.send(Message.of(json).addMetadata(metadata));

        } catch (Exception e) {
            Log.error("Failed to log client event", e);
        }
    }

    private String mapTransactionStatus(NpsResponse response) {
        if (response.isTransactionSuccessful()) {
            return "SUCCESS";
        } else if (response.isTransactionPending()) {
            return "PENDING";
        } else if (response.isTransactionRejected()) {
            return "FAILED";
        } else if (!response.isSuccess()) {
            return "ERROR";
        }
        return "UNKNOWN";
    }

    private String sanitizeXml(String xml) {
        if (xml == null) return null;
        // Truncate very long XML for logging (keep first 10KB)
        if (xml.length() > 10240) {
            return xml.substring(0, 10240) + "...[truncated]";
        }
        return xml;
    }

    /**
     * NPS transaction log entry.
     */
    public static class NpsTransactionLog {
        public String reference;
        public String action;
        public String messageType;
        public String accountNumber;
        public String bankCode;
        public String amount;
        public String status;
        public String responseCode;
        public String providerReference;
        public String originalMessageId;
        public long durationMs;
        public String timestamp;
        public String requestXml;
        public String responseXml;
        public String errorMessage;
    }

    /**
     * Client log event (compatible with other providers).
     */
    public static class ClientLogEvent {
        public String reference;
        public String provider;
        public String action;
        public String status;
        public String message;
        public String timestamp;
    }
}
