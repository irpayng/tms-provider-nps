package com.provider.nps.grpc;

import java.math.BigDecimal;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.provider.nps.client.NpsClient;
import com.provider.nps.client.NpsClient.NpsResponse;
import com.provider.nps.dto.ProviderResponse;
import com.provider.nps.logging.NpsTransactionLogger;
import com.provider.nps.message.Acmt023Builder;
import com.provider.nps.message.NpsResponseParser;
import com.provider.nps.message.NpsResponseParser.NameEnquiryResponse;
import com.provider.nps.message.NpsResponseParser.PaymentStatusResponse;
import com.provider.nps.message.Pacs008Builder;
import com.provider.nps.message.Pacs008Builder.CreditTransferRequest;
import com.provider.nps.message.Pacs028Builder;

import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;

/**
 * gRPC service implementation for NPS (NIBSS National Payment Stack).
 * 
 * Handles ISO 20022 message flows:
 * - name-enquiry: acmt.023 -> acmt.024
 * - bank-transfer: pacs.008 -> pacs.002
 * - requery: pacs.028 -> pacs.002
 */
@GrpcService
@Blocking
public class ProviderGrpcService extends ProviderServiceGrpc.ProviderServiceImplBase {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Inject
    NpsTransactionLogger transactionLogger;

    @Override
    public void execute(ProviderExecuteRequest request, StreamObserver<ProviderExecuteResponse> responseObserver) {
        Log.infof("gRPC Execute: action=%s ref=%s", request.getAction(), request.getReference());

        ProviderResponse result = switch (request.getAction()) {
            case "name-enquiry" -> handleNameEnquiry(request);
            case "bank-transfer" -> handleBankTransfer(request);
            case "requery" -> handleRequery(request);
            default -> ProviderResponse.failed("Unknown action: " + request.getAction(), request.getReference());
        };

        ProviderExecuteResponse.Builder resp = ProviderExecuteResponse.newBuilder()
                .setStatus(nullSafe(result.status))
                .setMessage(nullSafe(result.message));

        if (result.providerReference != null)
            resp.setProviderReference(result.providerReference);
        if (result.responseCode != null)
            resp.setResponseCode(result.responseCode);
        if (result.dataJson != null)
            resp.setDataJson(result.dataJson);
        if (result.requestXml != null)
            resp.setRequestIsoHex(result.requestXml);
        if (result.responseXml != null)
            resp.setResponseIsoHex(result.responseXml);

        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    /**
     * Name enquiry via acmt.023.
     * Required metadata: account_number, bank_code
     * Optional metadata: channel_code (default: 1)
     */
    private ProviderResponse handleNameEnquiry(ProviderExecuteRequest request) {
        String ref = request.getReference();
        long startTime = System.currentTimeMillis();

        try {
            String accountNumber = request.getMetadataOrDefault("account_number", "");
            String bankCode = request.getMetadataOrDefault("bank_code", "");
            String channelCode = request.getMetadataOrDefault("channel_code", "1");

            if (accountNumber.isBlank() || bankCode.isBlank()) {
                return ProviderResponse.failed("Missing required metadata: account_number, bank_code", ref);
            }

            // Build acmt.023 message
            String xml = acmt023Builder.build(accountNumber, bankCode, channelCode);

            // Send to NPS
            NpsResponse npsResponse = npsClient.sendNameEnquiry(xml);
            long durationMs = System.currentTimeMillis() - startTime;

            // Log to Kafka
            transactionLogger.logNameEnquiry(ref, accountNumber, bankCode, npsResponse, durationMs);

            if (!npsResponse.isSuccess()) {
                return ProviderResponse.failed(
                        npsResponse.getErrorMessage() != null ? npsResponse.getErrorMessage() : "NPS request failed",
                        ref);
            }

            // Parse acmt.024 response
            NameEnquiryResponse enquiryResponse = responseParser.parseNameEnquiryResponse(
                    npsResponse.getDecryptedResponse());

            if (enquiryResponse.isSuccess()) {
                String dataJson = objectMapper.writeValueAsString(new NameEnquiryData(
                        enquiryResponse.getAccountName(),
                        enquiryResponse.getAccountNumber(),
                        enquiryResponse.getBvn(),
                        enquiryResponse.getBankCode(),
                        enquiryResponse.getSessionId(),
                        npsResponse.getMessageId()));

                ProviderResponse resp = ProviderResponse.successWithData(
                        enquiryResponse.getAccountName(), ref, dataJson);
                resp.providerReference = npsResponse.getMessageId();
                resp.requestXml = npsResponse.getRawRequest();
                resp.responseXml = npsResponse.getRawResponse();
                return resp;
            } else {
                ProviderResponse resp = ProviderResponse.failedWithCode(
                        enquiryResponse.getErrorMessage() != null ? 
                                enquiryResponse.getErrorMessage() : "Account verification failed",
                        enquiryResponse.getReasonCode(),
                        ref);
                resp.requestXml = npsResponse.getRawRequest();
                resp.responseXml = npsResponse.getRawResponse();
                return resp;
            }

        } catch (Exception e) {
            Log.error("Name enquiry failed", e);
            transactionLogger.logClientEvent(ref, "name-enquiry", "ERROR", e.getMessage());
            return ProviderResponse.failed("Name enquiry error: " + e.getMessage(), ref);
        }
    }

    /**
     * Bank transfer via pacs.008.
     * Required metadata: account_number, account_name, bank_code, debtor_name, debtor_account
     * Optional: narration, debtor_bvn, channel_code, kyc_level, session_id (from name enquiry)
     */
    private ProviderResponse handleBankTransfer(ProviderExecuteRequest request) {
        String ref = request.getReference();
        long startTime = System.currentTimeMillis();

        try {
            // Validate required fields
            String creditorAccount = request.getMetadataOrDefault("account_number", "");
            String creditorName = request.getMetadataOrDefault("account_name", "");
            String creditorBankCode = request.getMetadataOrDefault("bank_code", "");
            String debtorName = request.getMetadataOrDefault("debtor_name", "");
            String debtorAccount = request.getMetadataOrDefault("debtor_account", "");

            if (creditorAccount.isBlank() || creditorName.isBlank() || creditorBankCode.isBlank()) {
                return ProviderResponse.failed(
                        "Missing required metadata: account_number, account_name, bank_code", ref);
            }

            if (debtorName.isBlank() || debtorAccount.isBlank()) {
                return ProviderResponse.failed(
                        "Missing required metadata: debtor_name, debtor_account", ref);
            }

            if (request.getAmount() <= 0) {
                return ProviderResponse.failed("Invalid amount", ref);
            }

            String amount = String.format("%.2f", request.getAmount());

            // Build credit transfer request
            CreditTransferRequest transferRequest = new CreditTransferRequest();
            transferRequest.setEndToEndId(request.getMetadataOrDefault("session_id", null));
            transferRequest.setAmount(BigDecimal.valueOf(request.getAmount()));
            transferRequest.setDebtorName(debtorName);
            transferRequest.setDebtorAccount(debtorAccount);
            transferRequest.setDebtorBvn(request.getMetadataOrDefault("debtor_bvn", null));
            transferRequest.setCreditorName(creditorName);
            transferRequest.setCreditorAccount(creditorAccount);
            transferRequest.setCreditorBankCode(creditorBankCode);
            transferRequest.setNarration(request.getMetadataOrDefault("narration", ref));
            transferRequest.setChannelCode(request.getMetadataOrDefault("channel_code", "1"));
            transferRequest.setKycLevel(request.getMetadataOrDefault("kyc_level", "3"));
            transferRequest.setTransactionLocation(request.getMetadataOrDefault("transaction_location", null));

            // Build pacs.008 message
            String xml = pacs008Builder.build(transferRequest);

            // Send to NPS
            NpsResponse npsResponse = npsClient.sendCreditTransfer(xml);
            long durationMs = System.currentTimeMillis() - startTime;

            // Log to Kafka
            transactionLogger.logBankTransfer(ref, creditorAccount, creditorBankCode, amount, npsResponse, durationMs);

            if (!npsResponse.isSuccess()) {
                // Connection/timeout errors should return pending
                if (npsResponse.getHttpStatus() == 0 || npsResponse.getHttpStatus() >= 500) {
                    return ProviderResponse.pendingWithReference(
                            "Provider connection error - requery required",
                            npsResponse.getMessageId(),
                            ref);
                }
                return ProviderResponse.failed(
                        npsResponse.getErrorMessage() != null ? npsResponse.getErrorMessage() : "Transfer failed",
                        ref);
            }

            // Parse pacs.002 response
            PaymentStatusResponse statusResponse = responseParser.parsePaymentStatusResponse(
                    npsResponse.getDecryptedResponse());

            ProviderResponse resp;
            if (statusResponse.isSuccess()) {
                resp = ProviderResponse.success(
                        statusResponse.getReasonDescription() != null ? 
                                statusResponse.getReasonDescription() : "Transfer successful",
                        statusResponse.getMessageId(),
                        ref);
            } else if (statusResponse.isPending()) {
                resp = ProviderResponse.pendingWithReference(
                        statusResponse.getReasonDescription() != null ? 
                                statusResponse.getReasonDescription() : "Transaction pending",
                        statusResponse.getMessageId(),
                        ref);
            } else {
                resp = ProviderResponse.failedWithCode(
                        statusResponse.getReasonDescription() != null ? 
                                statusResponse.getReasonDescription() : "Transfer rejected",
                        statusResponse.getReasonCode(),
                        ref);
            }

            resp.requestXml = npsResponse.getRawRequest();
            resp.responseXml = npsResponse.getRawResponse();
            return resp;

        } catch (Exception e) {
            Log.error("Bank transfer failed", e);
            transactionLogger.logClientEvent(ref, "bank-transfer", "ERROR", e.getMessage());
            return ProviderResponse.pending("Transfer error: " + e.getMessage(), ref);
        }
    }

    /**
     * Transaction status requery via pacs.028.
     * Required metadata: original_msg_id OR original_end_to_end_id
     */
    private ProviderResponse handleRequery(ProviderExecuteRequest request) {
        String ref = request.getReference();
        long startTime = System.currentTimeMillis();

        try {
            String originalMsgId = request.getMetadataOrDefault("original_msg_id", "");
            String originalEndToEndId = request.getMetadataOrDefault("original_end_to_end_id", ref);

            if (originalMsgId.isBlank() && originalEndToEndId.isBlank()) {
                return ProviderResponse.failed(
                        "Missing required metadata: original_msg_id or original_end_to_end_id", ref);
            }

            // Build pacs.028 message
            String xml = pacs028Builder.build(
                    originalMsgId.isBlank() ? originalEndToEndId : originalMsgId,
                    originalEndToEndId);

            // Send to NPS
            NpsResponse npsResponse = npsClient.sendPaymentStatusRequest(xml);
            long durationMs = System.currentTimeMillis() - startTime;

            // Log to Kafka
            transactionLogger.logRequery(ref, originalMsgId.isBlank() ? originalEndToEndId : originalMsgId, 
                    npsResponse, durationMs);

            if (!npsResponse.isSuccess()) {
                return ProviderResponse.pending(
                        "Requery failed: " + (npsResponse.getErrorMessage() != null ? 
                                npsResponse.getErrorMessage() : "Unknown error"),
                        ref);
            }

            // Parse pacs.002 response
            PaymentStatusResponse statusResponse = responseParser.parsePaymentStatusResponse(
                    npsResponse.getDecryptedResponse());

            ProviderResponse resp;
            if (statusResponse.isSuccess()) {
                resp = ProviderResponse.success(
                        "Transaction successful",
                        statusResponse.getMessageId(),
                        ref);
            } else if (statusResponse.isPending()) {
                resp = ProviderResponse.pendingWithReference(
                        statusResponse.getReasonDescription() != null ? 
                                statusResponse.getReasonDescription() : "Transaction still pending",
                        statusResponse.getMessageId(),
                        ref);
            } else {
                resp = ProviderResponse.failedWithCode(
                        statusResponse.getReasonDescription() != null ? 
                                statusResponse.getReasonDescription() : "Transaction failed",
                        statusResponse.getReasonCode(),
                        ref);
            }

            resp.requestXml = npsResponse.getRawRequest();
            resp.responseXml = npsResponse.getRawResponse();
            return resp;

        } catch (Exception e) {
            Log.error("Requery failed", e);
            transactionLogger.logClientEvent(ref, "requery", "ERROR", e.getMessage());
            return ProviderResponse.pending("Requery error: " + e.getMessage(), ref);
        }
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }

    /**
     * Data class for name enquiry response JSON.
     */
    public record NameEnquiryData(
            String accountName,
            String accountNumber,
            String bvn,
            String bankCode,
            String sessionId,
            String messageId) {
    }
}
