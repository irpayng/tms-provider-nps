package com.provider.nps.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.provider.nps.callback.NpsCallbackResource.CallbackResult;
import com.provider.nps.callback.NpsCallbackResource.InboundCreditTransfer;
import com.provider.nps.callback.NpsCallbackResource.InboundNameEnquiry;
import com.provider.nps.message.NpsResponseParser.NameEnquiryResponse;
import com.provider.nps.message.NpsResponseParser.PaymentInitiationStatusResponse;
import com.provider.nps.message.NpsResponseParser.PaymentStatusResponse;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;

/**
 * Handler for NPS callback events.
 * 
 * This is a placeholder implementation that publishes events to Kafka for
 * processing by other services (e.g., transaction service, wallet service).
 * 
 * In production, this would integrate with: - Account lookup service (for name
 * enquiry) - Wallet/ledger service (for inbound credits) - Transaction service
 * (for status updates)
 */
@ApplicationScoped
public class NpsCallbackHandler {

    @ConfigProperty(name = "nps.callback.publish-timeout-ms", defaultValue = "5000")
    long publishTimeoutMs = 5000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    @Channel("nps-inbound-transfers")
    Emitter<String> inboundTransferEmitter;

    @Inject
    @Channel("nps-status-updates")
    Emitter<String> statusUpdateEmitter;

    /**
     * Handles an inbound credit transfer (pacs.008). Another bank is sending us
     * funds for one of our customers.
     * 
     * @return CallbackResult with ACTC (accepted) or RJCT (rejected)
     */
    public CallbackResult handleInboundCreditTransfer(InboundCreditTransfer transfer) {
        Log.infof("Processing inbound credit transfer: msgId=%s, amount=%s, creditor=%s", transfer.getMessageId(),
                transfer.getAmount(), transfer.getCreditorAccount());

        try {
            // Account eligibility and ledger authorization are not integrated yet.
            // Broker acknowledgement below represents receipt, not completed payment.

            // Validate basic fields
            if (transfer.getCreditorAccount() == null || transfer.getCreditorAccount().isBlank()) {
                return CallbackResult.rejected("AC01", "Invalid creditor account");
            }

            if (transfer.getAmount() == null || transfer.getAmount().isBlank()) {
                return CallbackResult.rejected("AM01", "Invalid amount");
            }

            if (new BigDecimal(transfer.getAmount()).signum() <= 0) {
                return CallbackResult.rejected("AM01", "Amount must be positive");
            }
            if (transfer.getMessageId() == null || transfer.getMessageId().isBlank()) {
                return CallbackResult.rejected("FF01", "Missing message ID");
            }

            // Wait for broker acknowledgement before acknowledging the callback.
            publishInboundTransfer(transfer);

            // Return acceptance - actual crediting happens asynchronously
            return CallbackResult.accepted();

        } catch (NumberFormatException e) {
            return CallbackResult.rejected("AM01", "Invalid amount");
        }
    }

    /**
     * Handles inbound name enquiry (acmt.023). Another bank is verifying one of our
     * accounts.
     * 
     * @return CallbackResult with account name or rejection
     */
    public CallbackResult handleInboundNameEnquiry(InboundNameEnquiry enquiry) {
        Log.infof("Processing inbound name enquiry: account=%s, source=%s", enquiry.getAccountNumber(),
                enquiry.getSourceBankCode());

        try {
            // Validate account number format
            if (enquiry.getAccountNumber() == null || enquiry.getAccountNumber().length() < 10) {
                return CallbackResult.rejected("AC01", "Invalid account number");
            }

            // Account lookup is not connected; never fabricate a verified account.
            Log.warn("Inbound name enquiry cannot verify accounts until account lookup is integrated");
            return CallbackResult.rejected("AC01", "Account not found");

        } catch (Exception e) {
            Log.error("Failed to process inbound name enquiry", e);
            return CallbackResult.rejected("FF01", "System error");
        }
    }

    /**
     * Handles payment status update (pacs.002 callback). Updates our transaction
     * status based on NPS notification.
     */
    public void handlePaymentStatusUpdate(PaymentStatusResponse status) {
        Log.infof("Processing payment status update: originalMsgId=%s, status=%s", status.getOriginalMessageId(),
                status.getTransactionStatus());

        try {
            // Publish to Kafka for transaction service to process
            publishStatusUpdate("pacs002", status.getOriginalMessageId(), status.getTransactionStatus(),
                    status.getReasonCode(), status);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to process payment status update", e);
        }
    }

    /**
     * Handles name enquiry response (acmt.024 callback). Async response to our
     * outbound name enquiry.
     */
    public void handleNameEnquiryResponse(NameEnquiryResponse response) {
        Log.infof("Processing name enquiry response: msgId=%s, name=%s", response.getMessageId(),
                response.getAccountName());

        try {
            // Publish to Kafka - the original requester should be waiting for this
            publishStatusUpdate("acmt024", response.getMessageId(), response.isSuccess() ? "SUCCESS" : "FAILED",
                    response.getReasonCode());

        } catch (Exception e) {
            throw new IllegalStateException("Failed to process name enquiry response", e);
        }
    }

    /**
     * Handles payment initiation status (pain.002 callback).
     */
    public void handlePaymentInitiationResponse(PaymentInitiationStatusResponse response) {
        Log.infof("Processing payment initiation status: msgId=%s, status=%s", response.getMessageId(),
                response.getTransactionStatus());

        try {
            publishStatusUpdate("pain002", response.getOriginalMessageId(), response.getTransactionStatus(),
                    response.getReasonCode());

        } catch (Exception e) {
            throw new IllegalStateException("Failed to process payment initiation status", e);
        }
    }

    private void publishInboundTransfer(InboundCreditTransfer transfer) {
        try {
            InboundTransferEvent event = new InboundTransferEvent();
            event.eventType = "NPS_INBOUND_CREDIT";
            event.messageId = transfer.getMessageId();
            event.endToEndId = transfer.getEndToEndId();
            event.amount = transfer.getAmount();
            event.debtorName = transfer.getDebtorName();
            event.debtorAccount = transfer.getDebtorAccount();
            event.debtorBankCode = transfer.getDebtorBankCode();
            event.creditorAccount = transfer.getCreditorAccount();
            event.creditorName = transfer.getCreditorName();
            event.narration = transfer.getNarration();
            event.timestamp = java.time.Instant.now().toString();

            String json = objectMapper.writeValueAsString(event);

            // Use message ID as Kafka key for partitioning
            var metadata = OutgoingKafkaRecordMetadata.<String>builder().withKey(transfer.getMessageId()).build();

            publishAndAwait(inboundTransferEmitter, Message.of(json).addMetadata(metadata));

            Log.debugf("Published inbound transfer event: %s", transfer.getMessageId());

        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish inbound transfer event", e);
        }
    }

    private void publishStatusUpdate(String messageType, String originalMsgId, String status, String reasonCode) {
        publishStatusUpdate(messageType, originalMsgId, status, reasonCode, null);
    }

    private void publishStatusUpdate(String messageType, String originalMsgId, String status, String reasonCode,
            PaymentStatusResponse payment) {
        try {
            StatusUpdateEvent event = new StatusUpdateEvent();
            event.eventType = "NPS_STATUS_UPDATE";
            event.messageType = messageType;
            event.originalMessageId = originalMsgId;
            event.status = status;
            event.reasonCode = reasonCode;
            if (payment != null) {
                event.statusId = payment.getStatusId();
                event.responseMessageId = payment.getMessageId();
                event.originalInstructionId = payment.getOriginalInstructionId();
                event.originalTransactionId = payment.getTransactionId();
                event.originalEndToEndId = payment.getEndToEndId();
                event.reasonDescription = payment.getReasonDescription();
            }
            event.timestamp = java.time.Instant.now().toString();

            String json = objectMapper.writeValueAsString(event);

            var metadata = OutgoingKafkaRecordMetadata.<String>builder().withKey(originalMsgId).build();

            publishAndAwait(statusUpdateEmitter, Message.of(json).addMetadata(metadata));

            Log.debugf("Published status update event: %s", originalMsgId);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish status update event", e);
        }
    }

    private void publishAndAwait(Emitter<String> emitter, Message<String> message) {
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        emitter.send(message.withAck(() -> {
            acknowledgement.complete(null);
            return CompletableFuture.completedFuture(null);
        }).withNack(failure -> {
            acknowledgement.completeExceptionally(failure);
            return CompletableFuture.completedFuture(null);
        }));
        try {
            acknowledgement.get(publishTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Callback publication interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Callback publication was not acknowledged", e);
        }
    }

    // Event DTOs for Kafka

    public static class InboundTransferEvent {
        public String eventType;
        public String messageId;
        public String endToEndId;
        public String amount;
        public String debtorName;
        public String debtorAccount;
        public String debtorBankCode;
        public String creditorAccount;
        public String creditorName;
        public String narration;
        public String timestamp;
    }

    public static class StatusUpdateEvent {
        public String statusId;
        public String responseMessageId;
        public String originalInstructionId;
        public String originalTransactionId;
        public String originalEndToEndId;
        public String reasonDescription;
        public String eventType;
        public String messageType;
        public String originalMessageId;
        public String status;
        public String reasonCode;
        public String timestamp;
    }
}
