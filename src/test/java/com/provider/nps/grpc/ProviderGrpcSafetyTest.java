package com.provider.nps.grpc;

import static org.junit.jupiter.api.Assertions.*;

import com.provider.nps.client.NpsClient;
import com.provider.nps.client.NpsClient.NpsResponse;
import com.provider.nps.logging.NpsTransactionLogger;
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
            <BizMsg><Document><FIToFICstmrCdtTrf><GrpHdr><MsgId>original-id</MsgId></GrpHdr>
            </FIToFICstmrCdtTrf></Document></BizMsg>
            """;

    private ProviderGrpcService service(String status, boolean trustedResponse) {
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
        var service = new ProviderGrpcService();
        service.npsClient = new NpsClient() {
            @Override
            public NpsResponse sendCreditTransfer(String xml) {
                return response;
            }
            @Override
            public NpsResponse sendPaymentStatusRequest(String xml) {
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
            public String build(String originalMsgId, String originalEndToEndId) {
                assertEquals("original-id", originalMsgId);
                assertEquals("", originalEndToEndId);
                return "<Request/>";
            }
        };
        service.responseParser = new NpsResponseParser();
        service.transactionLogger = new NpsTransactionLogger() {
            @Override
            public void logTransaction(NpsTransactionLog log) {
            }
            @Override
            public void logClientEvent(String reference, String action, String status, String message) {
            }
        };
        return service;
    }

    private ProviderExecuteRequest transfer(double amount) {
        return ProviderExecuteRequest.newBuilder().setAction("bank-transfer").setReference("internal-ref")
                .setAmount(amount).putAllMetadata(Map.of("account_number", "0123456789", "account_name", "Receiver",
                        "bank_code", "000013", "debtor_name", "Sender", "debtor_account", "9876543210"))
                .build();
    }

    private ProviderExecuteResponse execute(ProviderGrpcService service, ProviderExecuteRequest request) {
        var result = new AtomicReference<ProviderExecuteResponse>();
        service.execute(request, new StreamObserver<>() {
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
            public NpsResponse sendCreditTransfer(String xml) throws Exception {
                throw new HttpTimeoutException("No response");
            }
        };
        var response = execute(service, transfer(100));
        assertEquals("pending", response.getStatus());
        assertEquals("original-id", response.getProviderReference());
    }

    @Test
    void requeryNeverConfirmsFinality() {
        var request = ProviderExecuteRequest.newBuilder().setAction("requery").setReference("internal-ref")
                .putMetadata("original_msg_id", "original-id").build();
        for (String status : new String[]{"ACSP", "ACSC", "RJCT"}) {
            var response = execute(service(status, true), request);
            assertEquals("pending", response.getStatus());
            assertEquals(status, response.getResponseCode());
            assertTrue(response.getDataJson().contains("\"finalityConfirmed\":false"));
        }
    }

    @Test
    void requeryDoesNotInventOriginalIdentifiersFromReference() {
        var request = ProviderExecuteRequest.newBuilder().setAction("requery").setReference("internal-ref").build();
        assertEquals("failed", execute(service("ACSP", true), request).getStatus());
    }

    @Test
    void nonFiniteAmountsAreRejectedBeforeSending() {
        for (double amount : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals("failed", execute(service("ACSP", true), transfer(amount)).getStatus());
        }
    }
}
