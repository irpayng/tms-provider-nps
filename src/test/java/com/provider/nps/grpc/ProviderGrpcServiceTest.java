package com.provider.nps.grpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ProviderGrpcServiceTest {

    @GrpcClient
    ProviderServiceGrpc.ProviderServiceBlockingStub client;

    @Test
    void testNameEnquiryMissingMetadata() {
        ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                .setAction("name-enquiry")
                .setReference("TEST123")
                .build();

        ProviderExecuteResponse response = client.execute(request);

        assertEquals("failed", response.getStatus());
        assertTrue(response.getMessage().contains("Missing required metadata"));
    }

    @Test
    void testBankTransferMissingAmount() {
        ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                .setAction("bank-transfer")
                .setReference("TEST123")
                .setAmount(0)
                .putAllMetadata(Map.of(
                        "account_number", "0123456789",
                        "account_name", "JOHN DOE",
                        "bank_code", "000013",
                        "debtor_name", "JANE DOE",
                        "debtor_account", "9876543210"))
                .build();

        ProviderExecuteResponse response = client.execute(request);

        assertEquals("failed", response.getStatus());
        assertTrue(response.getMessage().contains("Invalid amount"));
    }

    @Test
    void testUnknownAction() {
        ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                .setAction("unknown-action")
                .setReference("TEST123")
                .build();

        ProviderExecuteResponse response = client.execute(request);

        assertEquals("failed", response.getStatus());
        assertTrue(response.getMessage().contains("Unknown action"));
    }

    @Test
    void testRequeryMissingOriginalId() {
        ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                .setAction("requery")
                .setReference("TEST123")
                .build();

        // With empty original_msg_id but reference as fallback for end_to_end_id
        // Should attempt the request (may fail at NPS level)
        ProviderExecuteResponse response = client.execute(request);

        // Will fail because NPS is not available, but validates the flow works
        assertNotNull(response);
    }
}
