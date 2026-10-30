package com.provider.nps.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.provider.nps.client.NpsClient;
import com.provider.nps.client.NpsClient.NpsResponse;
import com.provider.nps.dto.ProviderRequest;
import com.provider.nps.dto.ProviderResponse;
import com.provider.nps.internal.AdapterJournal;
import com.provider.nps.internal.InboundConflictException;
import com.provider.nps.message.Acmt023Builder;
import com.provider.nps.message.NpsResponseParser;
import com.provider.nps.message.NpsResponseParser.NameEnquiryResponse;
import com.provider.nps.message.NpsResponseParser.PaymentStatusResponse;
import com.provider.nps.message.Pacs008Builder;
import com.provider.nps.message.Pacs008Builder.CreditTransferRequest;
import com.provider.nps.message.Pacs028Builder;
import com.provider.nps.xml.NpsXml;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.Consumer;
/** Transport-independent NIBSS operations shared by HTTP and gRPC. */
@ApplicationScoped
public class NpsOperations {
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Inject
    AdapterJournal journal;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "nps.source-id")
    String sourceId = "";

    @Inject
    NpsClient npsClient;

    @Inject
    Acmt023Builder acmt023Builder;

    @Inject
    Pacs008Builder pacs008Builder;

    @Inject
    Pacs028Builder pacs028Builder;

    @Inject
    NpsResponseParser responseParser;

    public ProviderResponse execute(ProviderRequest request) {
        Log.infof("NPS operation: action=%s ref=%s", request.getAction(), request.getReference());
        return switch (request.getAction()) {
            case "name-enquiry" -> handleNameEnquiry(request);
            case "bank-transfer" -> protectedTransfer(request);
            case "requery" -> handleRequery(request);
            default -> ProviderResponse.failed("Unknown action: " + request.getAction(), request.getReference());
        };
    }

    /**
     * Name enquiry via acmt.023. Required metadata: account_number, account_name,
     * bank_code.
     */
    private ProviderResponse handleNameEnquiry(ProviderRequest request) {
        String ref = request.getReference();

        try {
            String accountNumber = request.getMetadataOrDefault("account_number", "");
            String bankCode = request.getMetadataOrDefault("bank_code", "");
            String channelCode = request.getMetadataOrDefault("channel_code", "1");
            String accountName = request.getMetadataOrDefault("account_name", "");

            if (accountNumber.isBlank() || bankCode.isBlank() || accountName.isBlank()) {
                return ProviderResponse.failed("Missing required metadata: account_number, bank_code, account_name",
                        ref);
            }

            // Build acmt.023 message
            String xml = acmt023Builder.build(accountNumber, bankCode, channelCode, accountName);
            String originalMessageId = NpsXml.text(NpsXml.parse(xml), "Document/IdVrfctnReq/Assgnmt/MsgId");

            // Send to NPS
            NpsResponse npsResponse = npsClient.sendNameEnquiry(ref, xml);

            if (!npsResponse.isSuccess()) {
                return ProviderResponse.failed(
                        npsResponse.getErrorMessage() != null ? npsResponse.getErrorMessage() : "NPS request failed",
                        ref);
            }

            // Parse acmt.024 response
            NameEnquiryResponse enquiryResponse = responseParser
                    .parseNameEnquiryResponse(npsResponse.getDecryptedResponse());

            if (enquiryResponse.isSuccess() && !accountNumber.equals(enquiryResponse.getAccountNumber())) {
                return ProviderResponse.failed("Name enquiry account mismatch", ref);
            }
            if (enquiryResponse.isSuccess()) {
                String dataJson = objectMapper.writeValueAsString(new NameEnquiryData(enquiryResponse.getAccountName(),
                        enquiryResponse.getAccountNumber(), enquiryResponse.getBvn(), enquiryResponse.getBankCode(),
                        enquiryResponse.getSessionId(), originalMessageId, enquiryResponse.getMessageId(),
                        enquiryResponse.getAccountDesignation(), enquiryResponse.getIdType(),
                        enquiryResponse.getIdValue(), enquiryResponse.getAccountTier(),
                        enquiryResponse.getRiskRating()));

                ProviderResponse resp = ProviderResponse.successWithData(enquiryResponse.getAccountName(), ref,
                        dataJson);
                resp.providerReference = originalMessageId;
                resp.requestXml = npsResponse.getRawRequest();
                resp.responseXml = npsResponse.getRawResponse();
                return resp;
            } else {
                ProviderResponse resp = ProviderResponse.failedWithCode(enquiryResponse.getErrorMessage() != null
                        ? enquiryResponse.getErrorMessage()
                        : "Account verification failed", enquiryResponse.getReasonCode(), ref);
                resp.requestXml = npsResponse.getRawRequest();
                resp.responseXml = npsResponse.getRawResponse();
                return resp;
            }

        } catch (Exception e) {
            Log.error("Name enquiry failed", e);
            return ProviderResponse.failed("Name enquiry error: " + e.getMessage(), ref);
        }
    }

    private ProviderResponse protectedTransfer(ProviderRequest request) {
        if (request.getReference().isBlank())
            return ProviderResponse.failed("Transfer reference is required", request.getReference());
        try {
            String scope = objectMapper
                    .writeValueAsString(java.util.List.of("outbound-transfer", sourceId, request.getReference()));
            String key = hash(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String body = objectMapper.writeValueAsString(java.util.Arrays.asList(request.getReference(),
                    request.getAmount() == null ? null : request.getAmount().stripTrailingZeros().toPlainString(),
                    new java.util.TreeMap<>(request.getMetadataMap())));
            String result = journal.executeOnce(key, hash(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    checkpoint -> serialize(handleBankTransfer(request, checkpoint)));
            return objectMapper.readValue(result, ProviderResponse.class);
        } catch (InboundConflictException conflict) {
            return ProviderResponse.failedWithCode("Transfer reference was reused with different business data",
                    "IDEMPOTENCY_CONFLICT", request.getReference());
        } catch (Exception exception) {
            Log.error("Transfer journal could not establish a resolved outcome", exception);
            return ProviderResponse.pending("Transfer outcome unresolved - reconciliation required",
                    request.getReference());
        }
    }

    private String serialize(ProviderResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize transfer result", e);
        }
    }
    private static String hash(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Bank transfer via pacs.008 with explicit debtor/creditor verification
     * metadata.
     */
    private ProviderResponse handleBankTransfer(ProviderRequest request, Consumer<String> checkpoint) {
        String ref = request.getReference();
        String originalMessageId = null;
        String transferDetails = null;

        try {
            // Validate required fields
            String creditorAccount = request.getMetadataOrDefault("account_number", "");
            String creditorName = request.getMetadataOrDefault("account_name", "");
            String creditorBankCode = request.getMetadataOrDefault("bank_code", "");
            String debtorName = request.getMetadataOrDefault("debtor_name", "");
            String debtorAccount = request.getMetadataOrDefault("debtor_account", "");

            if (creditorAccount.isBlank() || creditorName.isBlank() || creditorBankCode.isBlank()) {
                return ProviderResponse.failed("Missing required metadata: account_number, account_name, bank_code",
                        ref);
            }

            if (debtorName.isBlank() || debtorAccount.isBlank()) {
                return ProviderResponse.failed("Missing required metadata: debtor_name, debtor_account", ref);
            }

            if (request.getAmount() == null || request.getAmount().signum() <= 0) {
                return ProviderResponse.failed("Invalid amount", ref);
            }

            // Build credit transfer request
            CreditTransferRequest transferRequest = new CreditTransferRequest();
            transferRequest.setEndToEndId(request.getMetadataOrDefault("end_to_end_id", null));
            transferRequest.setVerificationDetails(request.getMetadataMap());
            transferRequest.setAmount(request.getAmount());
            transferRequest.setDebtorName(debtorName);
            transferRequest.setDebtorAccount(debtorAccount);
            transferRequest.setCreditorName(creditorName);
            transferRequest.setCreditorAccount(creditorAccount);
            transferRequest.setCreditorBankCode(creditorBankCode);
            transferRequest.setNarration(request.getMetadataOrDefault("narration", ref));

            // Build pacs.008 message
            String xml = pacs008Builder.build(transferRequest);
            var document = NpsXml.parse(xml);
            originalMessageId = NpsXml.text(document, "Document/FIToFICstmrCdtTrf/GrpHdr/MsgId");
            transferDetails = buildTransferDetails(document, originalMessageId, creditorBankCode);

            ProviderResponse uncertain = withData(ProviderResponse.pendingWithReference(
                    "Submission outcome is uncertain - requery or reconcile before retrying", originalMessageId, ref),
                    transferDetails);
            checkpoint.accept(serialize(uncertain));

            // Send to NPS
            NpsResponse npsResponse = npsClient.sendCreditTransfer(ref, xml);

            if (!npsResponse.isSuccess()) {
                // Transport or authenticity failure is not a verified payment rejection.
                return withData(
                        ProviderResponse.pendingWithReference("No verified payment status - reconciliation required",
                                originalMessageId, ref),
                        transferDetails);
            }

            // Parse pacs.002 response
            PaymentStatusResponse statusResponse = responseParser
                    .parsePaymentStatusResponse(npsResponse.getDecryptedResponse());

            ProviderResponse resp;
            if (statusResponse.isSuccess()) {
                resp = ProviderResponse.success(statusResponse.getReasonDescription() != null
                        ? statusResponse.getReasonDescription()
                        : "Transfer successful", originalMessageId, ref);
            } else if (statusResponse.isPending()) {
                resp = ProviderResponse.pendingWithReference(statusResponse.getReasonDescription() != null
                        ? statusResponse.getReasonDescription()
                        : "Transaction pending", originalMessageId, ref);
            } else if (statusResponse.isRejected()) {
                resp = ProviderResponse.failedWithCode(statusResponse.getReasonDescription() != null
                        ? statusResponse.getReasonDescription()
                        : "Transfer rejected", statusResponse.getReasonCode(), ref);
            } else {
                resp = ProviderResponse.pendingWithReference("Unrecognized payment status - reconciliation required",
                        originalMessageId, ref);
            }

            resp.dataJson = transferDetails;
            resp.requestXml = npsResponse.getRawRequest();
            resp.responseXml = npsResponse.getRawResponse();
            return resp;

        } catch (Exception e) {
            if (originalMessageId == null && e instanceof IllegalArgumentException) {
                return ProviderResponse.failed(e.getMessage(), ref);
            }
            Log.error("Bank transfer failed", e);
            return withData(
                    ProviderResponse.pendingWithReference("Transfer error - requery required", originalMessageId, ref),
                    transferDetails);
        }
    }

    /**
     * Transaction status requery via pacs.028. Required metadata: original_msg_id
     * and original transaction time, ID, settlement date, and both agents.
     */
    private ProviderResponse handleRequery(ProviderRequest request) {
        String ref = request.getReference();

        try {
            String originalMsgId = request.getMetadataOrDefault("original_msg_id", "");

            for (String field : new String[]{"original_msg_id", "original_tx_id", "original_creation_time",
                    "original_settlement_date", "original_instructing_agent", "original_instructed_agent"}) {
                if (request.getMetadataOrDefault(field, "").isBlank())
                    return ProviderResponse.failed("Missing required metadata: " + field, ref);
            }
            var query = new Pacs028Builder.PaymentStatusRequest();
            query.setOriginalMsgId(originalMsgId);
            query.setOriginalTxId(request.getMetadataOrDefault("original_tx_id", ""));
            query.setOriginalCreationDateTime(request.getMetadataOrDefault("original_creation_time", ""));
            query.setOriginalSettlementDate(request.getMetadataOrDefault("original_settlement_date", ""));
            query.setInstructingAgent(request.getMetadataOrDefault("original_instructing_agent", ""));
            query.setInstructedAgent(request.getMetadataOrDefault("original_instructed_agent", ""));
            String xml;
            try {
                xml = pacs028Builder.build(query);
            } catch (IllegalArgumentException e) {
                return ProviderResponse.failed(e.getMessage(), ref);
            }

            // Send to NPS
            NpsResponse npsResponse = npsClient.sendPaymentStatusRequest(ref, xml);

            if (!npsResponse.isSuccess()) {
                return ProviderResponse.pending("Requery failed: "
                        + (npsResponse.getErrorMessage() != null ? npsResponse.getErrorMessage() : "Unknown error"),
                        ref);
            }

            // Parse pacs.002 response
            PaymentStatusResponse statusResponse = responseParser
                    .parsePaymentStatusResponse(npsResponse.getDecryptedResponse());

            // A status query is informational; it cannot establish payment finality.
            ProviderResponse resp = ProviderResponse.pendingWithReference(
                    "Queried status: " + nullSafe(statusResponse.getTransactionStatus())
                            + " - finality requires NPS authorization or settlement reconciliation",
                    originalMsgId, ref);
            resp.responseCode = statusResponse.getTransactionStatus();
            resp.dataJson = objectMapper.writeValueAsString(
                    java.util.Map.of("queriedStatus", nullSafe(statusResponse.getTransactionStatus()),
                            "originalMessageId", nullSafe(statusResponse.getOriginalMessageId()), "statusId",
                            nullSafe(statusResponse.getStatusId()), "finalityConfirmed", false));

            resp.requestXml = npsResponse.getRawRequest();
            resp.responseXml = npsResponse.getRawResponse();
            return resp;

        } catch (Exception e) {
            Log.error("Requery failed", e);
            return ProviderResponse.pending("Requery error: " + e.getMessage(), ref);
        }
    }

    private String buildTransferDetails(org.w3c.dom.Document document, String originalMessageId,
            String creditorBankCode) throws com.fasterxml.jackson.core.JsonProcessingException {
        String transferPath = "Document/FIToFICstmrCdtTrf/";
        return objectMapper.writeValueAsString(java.util.Map.of("original_msg_id", nullSafe(originalMessageId),
                "original_tx_id", nullSafe(NpsXml.text(document, transferPath + "CdtTrfTxInf/PmtId/TxId")),
                "original_end_to_end_id",
                nullSafe(NpsXml.text(document, transferPath + "CdtTrfTxInf/PmtId/EndToEndId")),
                "original_creation_time", nullSafe(NpsXml.text(document, transferPath + "GrpHdr/CreDtTm")),
                "original_settlement_date", nullSafe(NpsXml.text(document, transferPath + "CdtTrfTxInf/IntrBkSttlmDt")),
                "original_instructing_agent",
                nullSafe(NpsXml.text(document, transferPath + "CdtTrfTxInf/InstgAgt/FinInstnId/ClrSysMmbId/MmbId")),
                "original_instructed_agent", creditorBankCode));
    }

    private static ProviderResponse withData(ProviderResponse response, String data) {
        response.dataJson = data;
        return response;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }

    /**
     * Data class for name enquiry response JSON.
     */
    public record NameEnquiryData(String accountName, String accountNumber, String bvn, String bankCode,
            String sessionId, String messageId, String responseMessageId, String creditorAccountDesignation,
            String creditorIdType, String creditorIdValue, String creditorAccountTier, String riskRating) {
    }
}
