package com.provider.nps.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.provider.nps.crypto.NpsXmlEncryptor;
import com.provider.nps.crypto.NpsXmlSigner;

import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * NPS HTTP Client.
 * 
 * Handles all HTTP communication with NIBSS NPS endpoints.
 * 
 * NPS Endpoints (per integration doc):
 * - /nps/acmt/023 - Account identification (Name Enquiry request)
 * - /nps/acmt/024 - Account identification response
 * - /nps/pacs/008 - Credit transfer (FI to FI)
 * - /nps/pacs/002 - Payment status report
 * - /nps/pain/001 - Payment initiation (Customer to FI)
 * - /nps/pain/002 - Payment initiation status
 * - /nps/pacs/028 - Payment status request (Requery)
 * 
 * All requests are:
 * 1. Signed with W3C XMLDSIG (RSA-SHA256)
 * 2. Encrypted with AES-256-GCM + RSA-OAEP key wrapping
 * 3. Sent with OAuth2 Bearer token
 */
@ApplicationScoped
public class NpsClient {

    @ConfigProperty(name = "nps.base-url")
    String baseUrl;

    @ConfigProperty(name = "nps.timeout-ms", defaultValue = "60000")
    int timeoutMs;

    @ConfigProperty(name = "nps.skip-crypto", defaultValue = "false")
    boolean skipCrypto;

    @Inject
    NpsTokenManager tokenManager;

    @Inject
    NpsXmlSigner xmlSigner;

    @Inject
    NpsXmlEncryptor xmlEncryptor;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        Log.infof("NpsClient initialized with base URL: %s", baseUrl);
    }

    /**
     * Sends a name enquiry request (acmt.023).
     * 
     * @param xml The unsigned XML message
     * @return The NPS response
     */
    public NpsResponse sendNameEnquiry(String xml) throws Exception {
        return sendRequest("/nps/acmt/023", xml);
    }

    /**
     * Sends a credit transfer request (pacs.008).
     * 
     * @param xml The unsigned XML message
     * @return The NPS response
     */
    public NpsResponse sendCreditTransfer(String xml) throws Exception {
        return sendRequest("/nps/pacs/008", xml);
    }

    /**
     * Sends a payment initiation request (pain.001).
     * 
     * @param xml The unsigned XML message
     * @return The NPS response
     */
    public NpsResponse sendPaymentInitiation(String xml) throws Exception {
        return sendRequest("/nps/pain/001", xml);
    }

    /**
     * Sends a payment status request / requery (pacs.028).
     * 
     * @param xml The unsigned XML message
     * @return The NPS response
     */
    public NpsResponse sendPaymentStatusRequest(String xml) throws Exception {
        return sendRequest("/nps/pacs/028", xml);
    }

    /**
     * Core method to send request to NPS with signing and encryption.
     */
    private NpsResponse sendRequest(String endpoint, String xml) throws Exception {
        String url = baseUrl + endpoint;
        Log.debugf("Sending NPS request to: %s", url);

        String requestXml;
        if (skipCrypto) {
            // For testing - skip signing and encryption
            requestXml = xml;
            Log.debug("Crypto skipped for testing");
        } else {
            // Step 1: Sign the XML
            String signedXml = xmlSigner.sign(xml);
            Log.tracef("Signed XML:\n%s", signedXml);

            // Step 2: Encrypt the signed XML
            requestXml = xmlEncryptor.encrypt(signedXml);
            Log.tracef("Encrypted XML:\n%s", requestXml);
        }

        // Step 3: Get OAuth token
        String token = tokenManager.getAccessToken();

        // Step 4: Build and send HTTP request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(requestXml))
                .build();

        long startTime = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - startTime;

        Log.infof("NPS %s response: status=%d, elapsed=%dms", endpoint, response.statusCode(), elapsed);

        // Handle 401 - token might be expired
        if (response.statusCode() == 401) {
            Log.warn("Received 401 from NPS, refreshing token and retrying");
            tokenManager.forceRefresh();
            return sendRequest(endpoint, xml); // Retry once with new token
        }

        return processResponse(response, endpoint, xml, requestXml);
    }

    private NpsResponse processResponse(HttpResponse<String> response, String endpoint, 
            String originalXml, String sentXml) throws Exception {
        
        String responseBody = response.body();
        int statusCode = response.statusCode();

        NpsResponse npsResponse = new NpsResponse();
        npsResponse.setHttpStatus(statusCode);
        npsResponse.setRawRequest(sentXml);
        npsResponse.setRawResponse(responseBody);

        if (statusCode >= 200 && statusCode < 300 && responseBody != null && !responseBody.isBlank()) {
            try {
                String decryptedXml;
                boolean signatureValid = true;

                if (skipCrypto) {
                    // For testing - response is plain XML
                    decryptedXml = responseBody;
                } else {
                    // Decrypt the response
                    decryptedXml = xmlEncryptor.decrypt(responseBody);
                    Log.tracef("Decrypted response:\n%s", decryptedXml);

                    // Verify signature
                    signatureValid = xmlSigner.verify(decryptedXml);
                    if (!signatureValid) {
                        Log.warnf("NPS response signature verification failed for %s", endpoint);
                    }
                }

                npsResponse.setSignatureValid(signatureValid);
                npsResponse.setDecryptedResponse(decryptedXml);
                npsResponse.setSuccess(true);

                // Parse response code from XML
                parseResponseCode(npsResponse, decryptedXml);

            } catch (Exception e) {
                Log.error("Failed to process NPS response", e);
                npsResponse.setSuccess(false);
                npsResponse.setErrorMessage("Response processing failed: " + e.getMessage());
            }
        } else {
            npsResponse.setSuccess(false);
            npsResponse.setErrorMessage("HTTP " + statusCode + ": " + responseBody);
        }

        return npsResponse;
    }

    private void parseResponseCode(NpsResponse response, String xml) {
        try {
            // Extract response code from various possible locations
            // pacs.002: /Document/FIToFIPmtStsRpt/TxInfAndSts/TxSts
            // acmt.024: /Document/IdVrfctnRpt/Rpt/Rslt/Rsn
            // pain.002: /Document/CstmrPmtStsRpt/OrgnlPmtInfAndSts/TxInfAndSts/TxSts

            if (xml.contains("<TxSts>")) {
                int start = xml.indexOf("<TxSts>") + 7;
                int end = xml.indexOf("</TxSts>");
                if (end > start) {
                    response.setResponseCode(xml.substring(start, end));
                }
            } else if (xml.contains("<Rsn>")) {
                // For name enquiry response
                int start = xml.indexOf("<Rsn>") + 5;
                int end = xml.indexOf("</Rsn>");
                if (end > start) {
                    String rsn = xml.substring(start, end);
                    // Extract code from nested element if present
                    if (rsn.contains("<Cd>")) {
                        int codeStart = rsn.indexOf("<Cd>") + 4;
                        int codeEnd = rsn.indexOf("</Cd>");
                        response.setResponseCode(rsn.substring(codeStart, codeEnd));
                    } else {
                        response.setResponseCode(rsn);
                    }
                }
            }

            // Extract message ID for correlation
            if (xml.contains("<MsgId>")) {
                int start = xml.indexOf("<MsgId>") + 7;
                int end = xml.indexOf("</MsgId>");
                if (end > start) {
                    response.setMessageId(xml.substring(start, end));
                }
            }

        } catch (Exception e) {
            Log.warn("Failed to parse response code from XML", e);
        }
    }

    /**
     * Sends a raw request without signing/encryption (for testing).
     */
    public NpsResponse sendRaw(String endpoint, String xml) throws Exception {
        String url = baseUrl + endpoint;
        String token = tokenManager.getAccessToken();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(xml))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        NpsResponse npsResponse = new NpsResponse();
        npsResponse.setHttpStatus(response.statusCode());
        npsResponse.setRawRequest(xml);
        npsResponse.setRawResponse(response.body());
        npsResponse.setDecryptedResponse(response.body());
        npsResponse.setSuccess(response.statusCode() >= 200 && response.statusCode() < 300);

        return npsResponse;
    }

    /**
     * Response wrapper for NPS API calls.
     */
    public static class NpsResponse {

        private boolean success;
        private int httpStatus;
        private String responseCode;
        private String messageId;
        private String rawRequest;
        private String rawResponse;
        private String decryptedResponse;
        private boolean signatureValid;
        private String errorMessage;

        // Getters and Setters
        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public void setHttpStatus(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public String getResponseCode() {
            return responseCode;
        }

        public void setResponseCode(String responseCode) {
            this.responseCode = responseCode;
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }

        public String getRawRequest() {
            return rawRequest;
        }

        public void setRawRequest(String rawRequest) {
            this.rawRequest = rawRequest;
        }

        public String getRawResponse() {
            return rawResponse;
        }

        public void setRawResponse(String rawResponse) {
            this.rawResponse = rawResponse;
        }

        public String getDecryptedResponse() {
            return decryptedResponse;
        }

        public void setDecryptedResponse(String decryptedResponse) {
            this.decryptedResponse = decryptedResponse;
        }

        public boolean isSignatureValid() {
            return signatureValid;
        }

        public void setSignatureValid(boolean signatureValid) {
            this.signatureValid = signatureValid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        /**
         * Checks if transaction was successful based on NPS response codes.
         * ACTC, ACCP, ACSP = Success
         * RJCT = Rejected
         * PDNG = Pending
         */
        public boolean isTransactionSuccessful() {
            if (responseCode == null) {
                return false;
            }
            return "ACTC".equals(responseCode) || "ACCP".equals(responseCode) || "ACSP".equals(responseCode);
        }

        public boolean isTransactionPending() {
            return "PDNG".equals(responseCode);
        }

        public boolean isTransactionRejected() {
            return "RJCT".equals(responseCode);
        }

        @Override
        public String toString() {
            return "NpsResponse{" +
                    "success=" + success +
                    ", httpStatus=" + httpStatus +
                    ", responseCode='" + responseCode + '\'' +
                    ", messageId='" + messageId + '\'' +
                    ", signatureValid=" + signatureValid +
                    ", errorMessage='" + errorMessage + '\'' +
                    '}';
        }
    }
}
