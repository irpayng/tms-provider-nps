package com.provider.nps.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.provider.nps.application.NpsOperations;
import com.provider.nps.dto.ProviderRequest;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * HTTP JSON facade for the same operations used by the internal gRPC interface.
 */
@Tag(name = "NPS JSON operations", description = "HTTP testing facade. Dedicated routes select the action automatically; use a stable reference when retrying transfers.")
@APIResponse(responseCode = "200", description = "Business result: inspect status (success, pending, or failed), message, responseCode and data.", content = @Content(schema = @Schema(implementation = NpsOperationsResource.OperationResponse.class)))
@APIResponse(responseCode = "400", description = "Missing or malformed JSON request")
@APIResponse(responseCode = "404", description = "HTTP facade is disabled")
@Path("/api/nps")
@Blocking
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class NpsOperationsResource {
    @ConfigProperty(name = "nps.http.enabled", defaultValue = "false")
    boolean enabled;
    @Inject
    NpsOperations operations;
    @Inject
    ObjectMapper objectMapper;

    @POST
    @Path("/execute")
    @Operation(summary = "Execute an NPS operation", description = "Set action to name-enquiry, bank-transfer, or requery. Transfer timeouts stay pending; use requery/reconciliation rather than a new reference.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ProviderRequest.class), examples = @ExampleObject(name = "Request", value = NpsApiExamples.TRANSFER)))
    public OperationResponse execute(ProviderRequest request)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        if (!enabled)
            throw new NotFoundException();
        if (request == null)
            throw new BadRequestException("JSON request body is required");
        var response = operations.execute(request);
        return new OperationResponse(response.status, response.message, response.requestId, response.providerReference,
                response.responseCode, response.dataJson == null ? null : objectMapper.readTree(response.dataJson));
    }
    @POST
    @Path("/name-enquiry")
    @Operation(summary = "Verify a beneficiary account", description = "Provide the expected name, ten-digit account number, and six-digit institution code.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ProviderRequest.class), examples = @ExampleObject(name = "Request", value = NpsApiExamples.NAME_ENQUIRY)))
    public OperationResponse nameEnquiry(ProviderRequest request)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        return execute(requireBody(request).withAction("name-enquiry"));
    }
    @POST
    @Path("/transfers")
    @Operation(summary = "Submit a bank transfer", description = "Replace example identity and verification data with actual values. Preserve the reference across retries; changed data with the same reference returns IDEMPOTENCY_CONFLICT.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ProviderRequest.class), examples = @ExampleObject(name = "Request", value = NpsApiExamples.TRANSFER)))
    public OperationResponse transfer(ProviderRequest request)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        return execute(requireBody(request).withAction("bank-transfer"));
    }
    @POST
    @Path("/requery")
    @Operation(summary = "Query a previous transfer", description = "Use original identifiers returned by the transfer. A requery is informational and does not establish payment finality.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ProviderRequest.class), examples = @ExampleObject(name = "Request", value = NpsApiExamples.REQUERY)))
    public OperationResponse requery(ProviderRequest request)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        return execute(requireBody(request).withAction("requery"));
    }
    private ProviderRequest requireBody(ProviderRequest request) {
        if (!enabled)
            throw new NotFoundException();
        if (request == null)
            throw new BadRequestException("JSON request body is required");
        return request;
    }
    public record OperationResponse(String status, String message, String reference, String providerReference,
            String responseCode, JsonNode data) {
    }
}
