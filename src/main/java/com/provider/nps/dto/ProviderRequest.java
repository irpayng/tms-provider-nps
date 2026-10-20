package com.provider.nps.dto;

import java.math.BigDecimal;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Shared operation input; JSON decimals retain their precision before XML
 * validation.
 */
@Schema(description = "NPS operation request. Metadata requirements vary by operation; see each endpoint example.")
public record ProviderRequest(
        @Schema(description = "Required by /execute; dedicated routes select their action.", enumeration = {
                "name-enquiry", "bank-transfer", "requery"}) String action,
        @Schema(description = "Caller reference. Required for transfers; preserve it unchanged across retries.", example = "transfer-test-001") String reference,
        @Schema(description = "Transfer amount in NGN, positive with at most two decimal places.", example = "100.50") BigDecimal amount,
        @Schema(description = "Operation-specific NIBSS fields as string values.") Map<String, String> metadata){
    public ProviderRequest {
        action = action == null ? "" : action;
        reference = reference == null ? "" : reference;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
    public String getAction() {
        return action;
    }
    public String getReference() {
        return reference;
    }
    public BigDecimal getAmount() {
        return amount;
    }
    public Map<String, String> getMetadataMap() {
        return metadata;
    }
    public String getMetadataOrDefault(String name, String fallback) {
        return metadata.getOrDefault(name, fallback);
    }
    public ProviderRequest withAction(String action) {
        return new ProviderRequest(action, reference, amount, metadata);
    }
}
