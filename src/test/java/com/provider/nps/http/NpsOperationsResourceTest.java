package com.provider.nps.http;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.provider.nps.NpsMockServer;
import com.provider.nps.grpc.ProviderExecuteRequest;
import com.provider.nps.grpc.ProviderServiceGrpc;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(NpsMockServer.class)
class NpsOperationsResourceTest {
    @GrpcClient
    ProviderServiceGrpc.ProviderServiceBlockingStub grpc;

    @Test
    void swaggerPublishesJsonRoutesWithEditableExamplesAndServesTheUi() {
        var spec = given().accept("application/json").get("/q/openapi").then().statusCode(200)
                .contentType(containsString("json")).extract().jsonPath();
        for (String route : new String[]{"execute", "name-enquiry", "transfers", "requery"}) {
            String operation = "paths.'/api/nps/" + route + "'.post";
            assertNotNull(spec.getString(operation + ".summary"));
            String example = spec
                    .getString(operation + ".requestBody.content.'application/json'.examples.Request.value.reference");
            assertNotNull(example, route + " must have an editable example");
            assertNotNull(spec.get(operation + ".responses.'200'.content.'application/json'.schema"));
        }
        given().get("/q/swagger-ui/").then().statusCode(200).contentType(containsString("html"))
                .body(containsString("swagger-ui"));
    }

    @Test
    void httpAndGrpcNameEnquiryReturnTheSameBusinessData() {
        var metadata = Map.of("account_number", "0123456789", "bank_code", "000013", "account_name", "JOHN DOE SMITH");
        var grpcResponse = grpc.execute(ProviderExecuteRequest.newBuilder().setAction("name-enquiry")
                .setReference("grpc-test").putAllMetadata(metadata).build());
        var json = given().contentType("application/json").body(Map.of("reference", "http-test", "metadata", metadata))
                .post("/api/nps/name-enquiry").then().statusCode(200).contentType(containsString("json"))
                .body("status", equalTo(grpcResponse.getStatus())).body("data.accountName", equalTo("JOHN DOE SMITH"))
                .body("data.accountNumber", equalTo("0123456789")).extract().jsonPath();
        assertEquals(json.getString("providerReference"), json.getString("data.messageId"));
        assertEquals(35, json.getString("providerReference").length());
    }
    @Test
    void requeryReturnsStructuredPendingResultWithoutConfirmingFinality() {
        given().contentType("application/json").body(Map.of("reference", "query-test", "metadata",
                Map.of("original_msg_id", "ORIGINAL_MSG_123", "original_tx_id", "TX123", "original_creation_time",
                        "2026-10-17T10:00:00Z", "original_settlement_date", "2026-10-17", "original_instructing_agent",
                        "999057", "original_instructed_agent", "000013")))
                .post("/api/nps/requery").then().statusCode(200).body("status", equalTo("pending"))
                .body("data.finalityConfirmed", equalTo(false)).body("responseCode", equalTo("ACSP"));
    }
    @Test
    void jsonDecimalIsNotRoundedThroughADouble() {
        given().contentType("application/json").body("""
                {"reference":"precision-test","amount":1.0000000000000001,"metadata":{
                "account_number":"0123456789","account_name":"Receiver","bank_code":"000013",
                "debtor_name":"Sender","debtor_account":"9876543210"}}
                """).post("/api/nps/transfers").then().statusCode(200).body("status", equalTo("failed")).body("message",
                containsString("two decimal places"));
    }
    @Test
    void nullAndMalformedJsonBodiesAreRejected() {
        for (String body : new String[]{"null", "{"}) {
            given().contentType("application/json").body(body).post("/api/nps/execute").then().statusCode(400);
        }
    }
    @Test
    void endpointDeterminesActionAndBothTransportsShareValidation() {
        given().contentType("application/json").body(Map.of("action", "bank-transfer", "reference", "validation"))
                .post("/api/nps/name-enquiry").then().statusCode(200).body("status", equalTo("failed"))
                .body("message", containsString("account_name"));
        given().contentType("application/json").body(Map.of("action", "unknown")).post("/api/nps/execute").then()
                .statusCode(200).body("status", equalTo("failed"));
    }
    @Test
    void disabledHttpFacadeDoesNotExecuteOperations() {
        var resource = new NpsOperationsResource();
        assertThrows(jakarta.ws.rs.NotFoundException.class, () -> resource
                .execute(new com.provider.nps.dto.ProviderRequest("name-enquiry", "test", null, Map.of())));
    }
}
