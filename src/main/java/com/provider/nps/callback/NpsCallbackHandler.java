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

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;

/**
 * Handler for NPS callback events.
 * 
 * This is a placeholder implementation that publishes events to Kafka
 * for processing by other services (e.g., transaction service, wallet service).
 * 
 * In production, this would integrate with:
 * - Account lookup service (for name enquiry)
 * - Wallet/ledger service (for inbound credits)
 * - Transaction service (for status updates)
 */
@ApplicationScoped
public class NpsCallbackHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    @Channel("nps-inbound-transfers")
    Emitter<String> inboundTransferEmitter;

    @Inject
    @Channel("nps-status-updates")
    Emitter<String> statusUpdateEmitter;

    /**
     * Handles an inbound credit transfer (pacs.008).
     * Another bank is sending us funds for one of our customers.
     * 
     * @return CallbackResult with ACTC (accepted) or RJCT (rejected)
     */
    public CallbackResult handleInboundCreditTransfer(InboundCreditTransfer transfer) {
        Log.infof("Processing inbound credit transfer: msgId=%s, amount=%s, creditor=%s",
                transfer.getMessageId(), transfer.getAmount(), transfer.getCreditorAccount());

        try {
            // TODO: In production, look up the creditor account in our system
            // For now, we accept all transfers and publish to Kafka for processing

            // Validate basic fields
            if (transfer.getCreditorAccount() == null || transfer.getCreditorAccount().isBlank()) {
                return CallbackResult.rejected("AC01", "Invalid creditor account");
            }

            if (transfer.getAmount() == null || transfer.getAmount().isBlank()) {
                return CallbackResult.rejected("AM01", "Invalid amount");
            }

            // Publish to Kafka for async processing
            publishInboundTransfer(transfer);

            // Return acceptance - actual crediting happens asynchronously
            return CallbackResult.accepted();

        } catch (Exception e) {
            Log.error("Failed to process inbound credit transfer", e);
            return CallbackResult.rejected("FF01", "System error");
        }
    }

    /**
     * Handles inbound name enquiry (acmt.023).
     * Another bank is verifying one of our accounts.
     * 
     * @return CallbackResult with account name or rejection
     */
    public CallbackResult handleInboundNameEnquiry(InboundNameEnquiry enquiry) {
        Log.infof("Processing inbound name enquiry: account=%s, source=%s",
                enquiry.getAccountNumber(), enquiry.getSourceBankCode());

        try {
            // TODO: In production, look up account in our system
            // This would call the account service or wallet service

            // Validate account number format
            if (enquiry.getAccountNumber() == null || enquiry.getAccountNumber().length() < 10) {
                return CallbackResult.rejected("AC01", "Invalid account number");
            }

            // Placeholder: In production, query account database
            // For now, return a mock response
            // In real implementation:
            // AccountInfo account = accountService.findByNumber(enquiry.getAccountNumber());
            // if (account == null) {
            //     return CallbackResult.rejected("AC01", "Account not found");
            // }
            // return CallbackResult.success(account.getName());

            // Mock response for development
            Log.warn("Name enquiry using mock response - implement account lookup in production");
            return CallbackResult.rejected("AC01", "Account not found");

        } catch (Exception e) {
            Log.error("Failed to process inbound name enquiry", e);
            return CallbackResult.rejected("FF01", "System error");
        }
    }

    /**
     * Handles payment status update (pacs.002 callback).
     * Updates our transaction status based on NPS notification.
     */
    public void handlePaymentStatusUpdate(PaymentStatusResponse status) {
        Log.infof("Processing payment status update: originalMsgId=%s, status=%s",
                status.getOriginalMessageId(), status.getTransactionStatus());

        try {
            // Publish to Kafka for transaction service to process
            publishStatusUpdate("pacs002", status.getOriginalMessageId(), 
                    status.getTransactionStatus(), status.getReasonCode());

        } catch (Exception e) {
            Log.error("Failed to process payment status update", e);
        }
    }

    /**
     * Handles name enquiry response (acmt.024 callback).
     * Async response to our outbound name enquiry.
     */
    public void handleNameEnquiryResponse(NameEnquiryResponse response) {
        Log.infof("Processing name enquiry response: msgId=%s, name=%s",
                response.getMessageId(), response.getAccountName());

        try {
            // Publish to Kafka - the original requester should be waiting for this
            publishStatusUpdate("acmt024", response.getMessageId(),
                    response.isSuccess() ? "SUCCESS" : "FAILED",
                    response.getReasonCode());

        } catch (Exception e) {
            Log.error("Failed to process name enquiry response", e);
        }
    }

    /**
     * Handles payment initiation status (pain.002 callback).
     */
    public void handlePaymentInitiationResponse(PaymentInitiationStatusResponse response) {
        Log.infof("Processing payment initiation status: msgId=%s, status=%s",
                response.getMessageId(), response.getTransactionStatus());

        try {
            publishStatusUpdate("pain002", response.getOriginalMessageId(),
                    response.getTransactionStatus(), response.getReasonCode());

        } catch (Exception e) {
            Log.error("Failed to process payment initiation status", e);
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
            var metadata = OutgoingKafkaRecordMetadata.<String>builder()
                    .withKey(transfer.getMessageId())
                    .build();

            inboundTransferEmitter.send(Message.of(json).addMetadata(metadata));

            Log.debugf("Published inbound transfer event: %s", transfer.getMessageId());

        } catch (Exception e) {
            Log.error("Failed to publish inbound transfer event", e);
        }
    }

    private void publishStatusUpdate(String messageType, String originalMsgId, 
            String status, String reasonCode) {
        try {
            StatusUpdateEvent event = new StatusUpdateEvent();
            event.eventType = "NPS_STATUS_UPDATE";
            event.messageType = messageType;
            event.originalMessageId = originalMsgId;
            event.status = status;
            event.reasonCode = reasonCode;
            event.timestamp = java.time.Instant.now().toString();

            String json = objectMapper.writeValueAsString(event);

            var metadata = OutgoingKafkaRecordMetadata.<String>builder()
                    .withKey(originalMsgId)
                    .build();

            statusUpdateEmitter.send(Message.of(json).addMetadata(metadata));

            Log.debugf("Published status update event: %s", originalMsgId);

        } catch (Exception e) {
            Log.error("Failed to publish status update event", e);
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
        public String eventType;
        public String messageType;
        public String originalMessageId;
        public String status;
        public String reasonCode;
        public String timestamp;
    }
}
