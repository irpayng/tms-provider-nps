package com.provider.nps.application;

import static org.junit.jupiter.api.Assertions.*;

import com.provider.nps.client.NpsClient;
import com.provider.nps.client.NpsClient.NpsResponse;
import com.provider.nps.grpc.ProviderExecuteRequest;
import com.provider.nps.grpc.ProviderExecuteResponse;
import com.provider.nps.grpc.ProviderGrpcService;
import com.provider.nps.message.NpsResponseParser;
import com.provider.nps.message.Pacs008Builder;
import com.provider.nps.message.Pacs028Builder;
import io.grpc.stub.StreamObserver;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProviderGrpcSafetyTest {
    private static final String TRANSFER_XML = """
            <Document><FIToFICstmrCdtTrf><GrpHdr><MsgId>original-id</MsgId><CreDtTm>2026-10-17T10:00:00Z</CreDtTm></GrpHdr>
                <CdtTrfTxInf><PmtId><TxId>tx-id</TxId><EndToEndId>e2e-id</EndToEndId></PmtId>
                    <IntrBkSttlmDt>2026-10-17</IntrBkSttlmDt>
                    <InstgAgt><FinInstnId><ClrSysMmbId><MmbId>999057</MmbId></ClrSysMmbId></FinInstnId></InstgAgt>
                </CdtTrfTxInf>
            </FIToFICstmrCdtTrf></Document>
            """;

    private NpsOperations service(String status, boolean trustedResponse) {
        var response = new NpsResponse();
        response.setSuccess(trustedResponse);
        response.setHttpStatus(200);
        response.setRawRequest(TRANSFER_XML);
        response.setDecryptedResponse("""
                <BizMsg><Document><FIToFIPmtStsRpt>
                    <GrpHdr><MsgId>response-id</MsgId></GrpHdr>
                    <OrgnlGrpInfAndSts><OrgnlMsgId>original-id</OrgnlMsgId></OrgnlGrpInfAndSts>
                    <TxInfAndSts><TxSts>%s</TxSts></TxInfAndSts>
                </FIToFIPmtStsRpt></Document></BizMsg>
                """.formatted(status));
        var service = new NpsOperations();
        try {
            service.journal = new com.provider.nps.internal.AdapterJournal(
                    java.nio.file.Files.createTempDirectory("nps-operation-test"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        service.npsClient = new NpsClient() {
            @Override
            public NpsResponse sendCreditTransfer(String reference, String xml) {
                return response;
            }
            @Override
            public NpsResponse sendPaymentStatusRequest(String reference, String xml) {
                return response;
            }
        };
        service.pacs008Builder = new Pacs008Builder() {
            @Override
            public String build(CreditTransferRequest request) {
                return TRANSFER_XML;
            }
        };
        service.pacs028Builder = new Pacs028Builder() {
            @Override
            public String build(PaymentStatusRequest request) {
                assertEquals("original-id", request.getOriginalMsgId());
                return "<Request/>";
            }
        };
        service.responseParser = new NpsResponseParser();
        return service;
    }

    private ProviderExecuteRequest transfer(double amount) {
        return ProviderExecuteRequest.newBuilder().setAction("bank-transfer")
                .putMetadata("debtor_account_designation", "2").putMetadata("debtor_account_tier", "3")
                .putMetadata("debtor_id_type", "BVN").putMetadata("debtor_id_value", "22123456789")
                .putMetadata("creditor_account_designation", "2").putMetadata("creditor_account_tier", "3")
                .putMetadata("creditor_id_type", "BVN").putMetadata("creditor_id_value", "22987654321")
                .putMetadata("name_enquiry_msg_id", "ENQUIRY123").putMetadata("channel_code", "1")
                .putMetadata("transaction_location", "01080652440N020900337921E").setReference("internal-ref")
                .setAmount(amount).putAllMetadata(Map.of("account_number", "0123456789", "account_name", "Receiver",
                        "bank_code", "000013", "debtor_name", "Sender", "debtor_account", "9876543210"))
                .build();
    }

    private ProviderExecuteResponse execute(NpsOperations service, ProviderExecuteRequest request) {
        var result = new AtomicReference<ProviderExecuteResponse>();
        new ProviderGrpcService(service).execute(request, new StreamObserver<>() {
            @Override
            public void onNext(ProviderExecuteResponse value) {
                result.set(value);
            }
            @Override
            public void onError(Throwable failure) {
                fail(failure);
            }
            @Override
            public void onCompleted() {
            }
        });
        return result.get();
    }

    @Test
    void repeatedTransfersReplayAndConflictingReferencesNeverSend() {
        var service = service("ACSP", true);
        var originalClient = service.npsClient;
        var sends = new java.util.concurrent.atomic.AtomicInteger();
        service.npsClient = new NpsClient() {
            @Override
            public NpsResponse sendCreditTransfer(String reference, String xml) throws Exception {
                sends.incrementAndGet();
                return originalClient.sendCreditTransfer(reference, xml);
            }
        };
        assertEquals("success", execute(service, transfer(100)).getStatus());
        assertEquals("success", execute(service, transfer(100)).getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", execute(service, transfer(200)).getResponseCode());
        assertEquals(1, sends.get());
    }
    @Test
    void timeoutIsReplayedWithoutResubmitting() {
        var service = service("", false);
        var sends = new java.util.concurrent.atomic.AtomicInteger();
        service.npsClient = new NpsClient() {
            @Override
            public NpsResponse sendCreditTransfer(String reference, String xml) throws Exception {
                sends.incrementAndGet();
                throw new HttpTimeoutException("No response");
            }
        };
        assertEquals("pending", execute(service, transfer(100)).getStatus());
        var replay = execute(service, transfer(100));
        assertEquals("pending", replay.getStatus());
        assertEquals("original-id", replay.getProviderReference());
        assertEquals(1, sends.get());
    }

    @Test
    void acknowledgementsAndUnknownStatusesRemainPending() {
        for (String status : new String[]{"ACTC", "ACCP", "PDNG", "UNKNOWN"}) {
            var response = execute(service(status, true), transfer(100));
            assertEquals("pending", response.getStatus(), status);
            assertEquals("original-id", response.getProviderReference());
        }
    }

    @Test
    void completedTransferReturnsOriginalRequestId() {
        var response = execute(service("ACSP", true), transfer(100));
        assertEquals("success", response.getStatus());
        assertEquals("original-id", response.getProviderReference());
    }

    @Test
    void untrustedResponseDoesNotTriggerFinancialRejection() {
        var response = execute(service("RJCT", false), transfer(100));
        assertEquals("pending", response.getStatus());
        assertEquals("original-id", response.getProviderReference());
    }

    @Test
    void timeoutPreservesOriginalRequestId() {
        var service = service("", false);
        service.npsClient = new NpsClient() {
            @Override
            public NpsResponse sendCreditTransfer(String reference, String xml) throws Exception {
                throw new HttpTimeoutException("No response");
            }
        };
        var response = execute(service, transfer(100));
        assertEquals("pending", response.getStatus());
        assertEquals("original-id", response.getProviderReference());
        assertTrue(response.getDataJson().contains("\"original_tx_id\":\"tx-id\""));
        assertTrue(response.getDataJson().contains("\"original_creation_time\":\"2026-10-17T10:00:00Z\""));
        assertTrue(response.getDataJson().contains("\"original_instructing_agent\":\"999057\""));
    }

    @Test
    void requeryNeverConfirmsFinality() {
        var request = ProviderExecuteRequest.newBuilder().setAction("requery")
                .putMetadata("original_tx_id", "ORIGINAL_TX_123")
                .putMetadata("original_creation_time", "2026-10-17T10:00:00Z")
                .putMetadata("original_settlement_date", "2026-10-17")
                .putMetadata("original_instructing_agent", "999057").putMetadata("original_instructed_agent", "000013")
                .setReference("internal-ref").putMetadata("original_msg_id", "original-id").build();
        for (String status : new String[]{"ACSP", "ACSC", "RJCT"}) {
            var response = execute(service(status, true), request);
            assertEquals("pending", response.getStatus());
            assertEquals(status, response.getResponseCode());
            assertTrue(response.getDataJson().contains("\"finalityConfirmed\":false"));
        }
    }

    @Test
    void requeryDoesNotInventOriginalIdentifiersFromReference() {
        var request = ProviderExecuteRequest.newBuilder().setAction("requery")
                .putMetadata("original_tx_id", "ORIGINAL_TX_123")
                .putMetadata("original_creation_time", "2026-10-17T10:00:00Z")
                .putMetadata("original_settlement_date", "2026-10-17")
                .putMetadata("original_instructing_agent", "999057").putMetadata("original_instructed_agent", "000013")
                .setReference("internal-ref").build();
        assertEquals("failed", execute(service("ACSP", true), request).getStatus());
    }

    @Test
    void nonFiniteAmountsAreRejectedBeforeSending() {
        for (double amount : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals("failed", execute(service("ACSP", true), transfer(amount)).getStatus());
        }
    }
}
