package com.provider.nps.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Standard response DTO for provider operations.
 * Matches the pattern used by other providers (NIP, NIBSS).
 */
public class ProviderResponse {

    public String status;
    public String message;

    @JsonProperty("provider_reference")
    public String providerReference;

    @JsonProperty("request_id")
    public String requestId;

    @JsonProperty("response_code")
    public String responseCode;

    @JsonProperty("data_json")
    public String dataJson;

    @JsonProperty("request_xml")
    public String requestXml;

    @JsonProperty("response_xml")
    public String responseXml;

    public ProviderResponse() {
    }

    public ProviderResponse(String status, String message, String providerReference, String requestId) {
        this.status = status;
        this.message = message;
        this.providerReference = providerReference;
        this.requestId = requestId;
    }

    public static ProviderResponse success(String message, String providerReference, String requestId) {
        return new ProviderResponse("success", message, providerReference, requestId);
    }

    public static ProviderResponse successWithData(String message, String requestId, String dataJson) {
        ProviderResponse r = new ProviderResponse("success", message, null, requestId);
        r.dataJson = dataJson;
        return r;
    }

    public static ProviderResponse successWithXml(String message, String providerReference, String requestId, 
            String requestXml, String responseXml) {
        ProviderResponse r = new ProviderResponse("success", message, providerReference, requestId);
        r.requestXml = requestXml;
        r.responseXml = responseXml;
        return r;
    }

    public static ProviderResponse failed(String message, String requestId) {
        return new ProviderResponse("failed", message, null, requestId);
    }

    public static ProviderResponse failedWithCode(String message, String responseCode, String requestId) {
        ProviderResponse r = new ProviderResponse("failed", message, null, requestId);
        r.responseCode = responseCode;
        return r;
    }

    public static ProviderResponse pending(String message, String requestId) {
        return new ProviderResponse("pending", message, null, requestId);
    }

    public static ProviderResponse pendingWithReference(String message, String providerReference, String requestId) {
        return new ProviderResponse("pending", message, providerReference, requestId);
    }
}
