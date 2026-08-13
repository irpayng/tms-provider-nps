package com.provider.nps;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.provider.nps.grpc.ProviderExecuteRequest;
import com.provider.nps.grpc.ProviderExecuteResponse;
import com.provider.nps.grpc.ProviderServiceGrpc;

import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for all NPS operations.
 * Uses WireMock to simulate NPS responses.
 */
@QuarkusTest
@QuarkusTestResource(NpsMockServer.class)
class NpsIntegrationTest {

    @GrpcClient
    ProviderServiceGrpc.ProviderServiceBlockingStub providerService;

    @Nested
    @DisplayName("Name Enquiry Tests (acmt.023)")
    class NameEnquiryTests {

        @Test
        @DisplayName("Should successfully validate account")
        void testSuccessfulNameEnquiry() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("name-enquiry")
                    .setReference("NE-TEST-001")
                    .putAllMetadata(Map.of(
                            "account_number", "0123456789",
                            "bank_code", "000013",
                            "channel_code", "1"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("success", response.getStatus());
            assertNotNull(response.getDataJson());
            assertTrue(response.getDataJson().contains("JOHN DOE"));
            assertTrue(response.getDataJson().contains("0123456789"));
            assertNotNull(response.getProviderReference());
        }

        @Test
        @DisplayName("Should fail when account_number is missing")
        void testMissingAccountNumber() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("name-enquiry")
                    .setReference("NE-TEST-002")
                    .putAllMetadata(Map.of(
                            "bank_code", "000013"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("failed", response.getStatus());
            assertTrue(response.getMessage().contains("Missing required metadata"));
        }

        @Test
        @DisplayName("Should fail when bank_code is missing")
        void testMissingBankCode() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("name-enquiry")
                    .setReference("NE-TEST-003")
                    .putAllMetadata(Map.of(
                            "account_number", "0123456789"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("failed", response.getStatus());
            assertTrue(response.getMessage().contains("Missing required metadata"));
        }

        @Test
        @DisplayName("Should use default channel_code when not provided")
        void testDefaultChannelCode() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("name-enquiry")
                    .setReference("NE-TEST-004")
                    .putAllMetadata(Map.of(
                            "account_number", "0123456789",
                            "bank_code", "000013"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            // Should succeed with default channel_code
            assertEquals("success", response.getStatus());
        }
    }

    @Nested
    @DisplayName("Bank Transfer Tests (pacs.008)")
    class BankTransferTests {

        @Test
        @DisplayName("Should successfully transfer funds")
        void testSuccessfulTransfer() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("bank-transfer")
                    .setReference("BT-TEST-001")
                    .setAmount(5000.00)
                    .putAllMetadata(Map.of(
                            "account_number", "0123456789",
                            "account_name", "JOHN DOE SMITH",
                            "bank_code", "000013",
                            "debtor_name", "JANE DOE",
                            "debtor_account", "9876543210",
                            "debtor_bvn", "22987654321",
                            "narration", "Test transfer"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("success", response.getStatus());
            assertTrue(response.getMessage().contains("successful"));
            assertNotNull(response.getProviderReference());
        }

        @Test
        @DisplayName("Should fail when amount is zero")
        void testZeroAmount() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("bank-transfer")
                    .setReference("BT-TEST-002")
                    .setAmount(0)
                    .putAllMetadata(Map.of(
                            "account_number", "0123456789",
                            "account_name", "JOHN DOE",
                            "bank_code", "000013",
                            "debtor_name", "JANE DOE",
                            "debtor_account", "9876543210"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("failed", response.getStatus());
            assertTrue(response.getMessage().contains("Invalid amount"));
        }

        @Test
        @DisplayName("Should fail when creditor details are missing")
        void testMissingCreditorDetails() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("bank-transfer")
                    .setReference("BT-TEST-003")
                    .setAmount(1000.00)
                    .putAllMetadata(Map.of(
                            "debtor_name", "JANE DOE",
                            "debtor_account", "9876543210"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("failed", response.getStatus());
            assertTrue(response.getMessage().contains("Missing required metadata"));
        }

        @Test
        @DisplayName("Should fail when debtor details are missing")
        void testMissingDebtorDetails() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("bank-transfer")
                    .setReference("BT-TEST-004")
                    .setAmount(1000.00)
                    .putAllMetadata(Map.of(
                            "account_number", "0123456789",
                            "account_name", "JOHN DOE",
                            "bank_code", "000013"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("failed", response.getStatus());
            assertTrue(response.getMessage().contains("Missing required metadata"));
        }

        @Test
        @DisplayName("Should handle large amounts")
        void testLargeAmount() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("bank-transfer")
                    .setReference("BT-TEST-005")
                    .setAmount(999999999.99)
                    .putAllMetadata(Map.of(
                            "account_number", "0123456789",
                            "account_name", "JOHN DOE SMITH",
                            "bank_code", "000013",
                            "debtor_name", "CORPORATE ENTITY LTD",
                            "debtor_account", "9876543210"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("success", response.getStatus());
        }

        @Test
        @DisplayName("Should include session_id from name enquiry")
        void testWithSessionId() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("bank-transfer")
                    .setReference("BT-TEST-006")
                    .setAmount(2500.00)
                    .putAllMetadata(Map.of(
                            "account_number", "0123456789",
                            "account_name", "JOHN DOE SMITH",
                            "bank_code", "000013",
                            "debtor_name", "JANE DOE",
                            "debtor_account", "9876543210",
                            "session_id", "SESSION123456",
                            "kyc_level", "3"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("success", response.getStatus());
        }
    }

    @Nested
    @DisplayName("Requery Tests (pacs.028)")
    class RequeryTests {

        @Test
        @DisplayName("Should successfully requery with original_msg_id")
        void testRequeryWithMsgId() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("requery")
                    .setReference("RQ-TEST-001")
                    .putAllMetadata(Map.of(
                            "original_msg_id", "ORIGINAL_MSG_123",
                            "original_end_to_end_id", "ORIG_E2E_123"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("success", response.getStatus());
            assertNotNull(response.getProviderReference());
        }

        @Test
        @DisplayName("Should use reference as end_to_end_id when not provided")
        void testRequeryWithReferenceAsFallback() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("requery")
                    .setReference("ORIG_E2E_123")
                    .putAllMetadata(Map.of(
                            "original_msg_id", "ORIGINAL_MSG_123"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("success", response.getStatus());
        }

        @Test
        @DisplayName("Should handle requery with only end_to_end_id")
        void testRequeryWithEndToEndIdOnly() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("requery")
                    .setReference("RQ-TEST-003")
                    .putAllMetadata(Map.of(
                            "original_end_to_end_id", "ORIG_E2E_123"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("success", response.getStatus());
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should return error for unknown action")
        void testUnknownAction() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("invalid-action")
                    .setReference("ERR-TEST-001")
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            assertEquals("failed", response.getStatus());
            assertTrue(response.getMessage().contains("Unknown action"));
        }

        @Test
        @DisplayName("Should handle empty reference")
        void testEmptyReference() {
            ProviderExecuteRequest request = ProviderExecuteRequest.newBuilder()
                    .setAction("name-enquiry")
                    .setReference("")
                    .putAllMetadata(Map.of(
                            "account_number", "0123456789",
                            "bank_code", "000013"))
                    .build();

            ProviderExecuteResponse response = providerService.execute(request);

            // Should still process (reference is used internally)
            assertNotNull(response);
        }
    }

    @Nested
    @DisplayName("End-to-End Flow Tests")
    class EndToEndTests {

        @Test
        @DisplayName("Should complete full transfer flow: name enquiry -> transfer -> requery")
        void testFullTransferFlow() {
            // Step 1: Name Enquiry
            ProviderExecuteRequest nameEnquiryRequest = ProviderExecuteRequest.newBuilder()
                    .setAction("name-enquiry")
                    .setReference("E2E-FLOW-001")
                    .putAllMetadata(Map.of(
                            "account_number", "0123456789",
                            "bank_code", "000013"))
                    .build();

            ProviderExecuteResponse nameEnquiryResponse = providerService.execute(nameEnquiryRequest);
            assertEquals("success", nameEnquiryResponse.getStatus());
            String sessionId = nameEnquiryResponse.getProviderReference();

            // Step 2: Bank Transfer
            ProviderExecuteRequest transferRequest = ProviderExecuteRequest.newBuilder()
                    .setAction("bank-transfer")
                    .setReference("E2E-FLOW-001-TRF")
                    .setAmount(10000.00)
                    .putAllMetadata(Map.of(
                            "account_number", "0123456789",
                            "account_name", "JOHN DOE SMITH",
                            "bank_code", "000013",
                            "debtor_name", "COMPANY ABC LTD",
                            "debtor_account", "1111111111",
                            "session_id", sessionId != null ? sessionId : ""))
                    .build();

            ProviderExecuteResponse transferResponse = providerService.execute(transferRequest);
            assertEquals("success", transferResponse.getStatus());
            String providerRef = transferResponse.getProviderReference();

            // Step 3: Requery
            ProviderExecuteRequest requeryRequest = ProviderExecuteRequest.newBuilder()
                    .setAction("requery")
                    .setReference("E2E-FLOW-001-RQ")
                    .putAllMetadata(Map.of(
                            "original_msg_id", providerRef != null ? providerRef : "ORIG123",
                            "original_end_to_end_id", "E2E-FLOW-001-TRF"))
                    .build();

            ProviderExecuteResponse requeryResponse = providerService.execute(requeryRequest);
            assertEquals("success", requeryResponse.getStatus());
        }
    }
}
