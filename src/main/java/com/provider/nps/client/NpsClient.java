package com.provider.nps.client;

import com.provider.nps.crypto.NpsXmlEncryptor;
import com.provider.nps.crypto.NpsXmlSigner;
import com.provider.nps.message.NpsPaymentStatus;
import com.provider.nps.xml.NpsXml;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * NPS HTTP Client.
 * 
 * Handles all HTTP communication with NIBSS NPS endpoints.
 * 
 * NPS guide routes name enquiry to /nps/acmt and transfer/status requests to
 * /nps/pacs. Payment initiation remains a separate integration path.
 *
 * All requests are: 1. Signed with W3C XMLDSIG (RSA-SHA256) 2. Encrypted with
 * AES-256-GCM + RSA-OAEP key wrapping 3. Sent with OAuth2 Bearer token
 */
@ApplicationScoped
public class NpsClient {

    @ConfigProperty(name = "nps.base-url")
    String baseUrl;

    @ConfigProperty(name = "nps.name-enquiry-path", defaultValue = "/nps/acmt")
    String nameEnquiryPath = "/nps/acmt";
    @ConfigProperty(name = "nps.credit-transfer-path", defaultValue = "/nps/pacs")
    String creditTransferPath = "/nps/pacs";
    @ConfigProperty(name = "nps.requery-path", defaultValue = "/nps/pacs")
    String requeryPath = "/nps/pacs";
    @ConfigProperty(name = "nps.payment-initiation-path", defaultValue = "/nps/pain")
    String paymentInitiationPath = "/nps/pain";

    @ConfigProperty(name = "nps.timeout-ms", defaultValue = "20000")
    int timeoutMs;

    @ConfigProperty(name = "nps.skip-crypto", defaultValue = "false")
    boolean skipCrypto;

    @Inject
    NpsTokenManager tokenManager;

    @Inject
    NpsXmlSigner xmlSigner;

    @Inject
    NpsXmlEncryptor xmlEncryptor;

    @Inject
    ClientLogPublisher clientLogs;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        Log.infof("NpsClient initialized with base URL: %s", baseUrl);
    }

    /**
     * Sends a name enquiry request (acmt.023).
     *
     * @param reference
     *            Our transaction reference, for the client log
     * @param xml
     *            The unsigned XML message
     * @return The NPS response
     */
    public NpsResponse sendNameEnquiry(String reference, String xml) throws Exception {
        return sendRequest(reference, nameEnquiryPath, xml);
    }

    /**
     * Sends a credit transfer request (pacs.008).
     *
     * @param reference
     *            Our transaction reference, for the client log
     * @param xml
     *            The unsigned XML message
     * @return The NPS response
     */
    public NpsResponse sendCreditTransfer(String reference, String xml) throws Exception {
        return sendRequest(reference, creditTransferPath, xml);
    }

    /**
     * Sends a payment initiation request (pain.001).
     *
     * @param reference
     *            Our transaction reference, for the client log
     * @param xml
     *            The unsigned XML message
     * @return The NPS response
     */
    public NpsResponse sendPaymentInitiation(String reference, String xml) throws Exception {
        return sendRequest(reference, paymentInitiationPath, xml);
    }

    /**
     * Sends a payment status request / requery (pacs.028).
     *
     * @param reference
     *            Our transaction reference, for the client log
     * @param xml
     *            The unsigned XML message
     * @return The NPS response
     */
    public NpsResponse sendPaymentStatusRequest(String reference, String xml) throws Exception {
        return sendRequest(reference, requeryPath, xml);
    }

    /**
     * Core method to send request to NPS with signing and encryption.
     */
    private NpsResponse sendRequest(String reference, String endpoint, String xml) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
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

            // Step 2: Encrypt the signed XML
            requestXml = xmlEncryptor.encrypt(signedXml);

        }

        HttpResponse<String> response = sendHttpRequest(reference, url, requestXml, deadline);
        if (response.statusCode() == 401) {
            Log.warn("Received 401 from NPS, refreshing token and retrying once");
            tokenManager.forceRefresh(remainingTime(deadline));
            response = sendHttpRequest(reference, url, requestXml, deadline);
        }
        return processResponse(response, endpoint, requestXml);
    }

    private HttpResponse<String> sendHttpRequest(String reference, String url, String requestXml, long deadline)
            throws Exception {
        String token = tokenManager.getAccessToken(remainingTime(deadline));
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new java.net.http.HttpTimeoutException("NPS request deadline exceeded");
        }
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofNanos(remainingNanos))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/xml")
                .header("Accept", "application/xml").POST(HttpRequest.BodyPublishers.ofString(requestXml)).build();
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logExchange(reference, url, requestXml, response.statusCode(), response.body(), started);
            return response;
        } catch (Exception e) {
            // Status 0 marks a transport failure, as provider-client records it.
            logExchange(reference, url, requestXml, 0, e.getMessage(), started);
            throw e;
        }
    }

    private void logExchange(String reference, String url, String requestXml, int status, String responseBody,
            long started) {
        if (clientLogs != null) {
            clientLogs.publish(reference, url, requestXml, status, responseBody,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }

    private Duration remainingTime(long deadline) throws java.net.http.HttpTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new java.net.http.HttpTimeoutException("NPS request deadline exceeded");
        }
        return Duration.ofNanos(remaining);
    }

    private NpsResponse processResponse(HttpResponse<String> response, String endpoint, String sentXml)
            throws Exception {

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

                    // Verify signature
                    signatureValid = xmlSigner.verify(decryptedXml);
                    if (!signatureValid) {
                        Log.warnf("NPS response signature verification failed for %s", endpoint);
                        npsResponse.setErrorMessage("Invalid NPS response signature");
                        return npsResponse;
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
        var document = NpsXml.parse(xml);
        String status = NpsXml.first(document, "TxSts");
        if (status == null)
            status = NpsXml.first(document, "GrpSts");
        response.setResponseCode(status != null ? status : NpsXml.first(document, "Cd"));
        response.setMessageId(NpsXml.first(document, "MsgId"));
        response.setStatusId(NpsXml.first(document, "StsId"));
    }

    /**
     * Sends a raw request without signing/encryption (for testing).
     */
    public NpsResponse sendRaw(String endpoint, String xml) throws Exception {
        if (!skipCrypto) {
            throw new IllegalStateException("Raw requests are available only when test crypto bypass is enabled");
        }
        String url = baseUrl + endpoint;
        String token = tokenManager.getAccessToken();

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/xml")
                .header("Accept", "application/xml").POST(HttpRequest.BodyPublishers.ofString(xml)).build();

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
        private String statusId;
        public String getStatusId() {
            return statusId;
        }
        public void setStatusId(String value) {
            statusId = value;
        }
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
         * Checks if transaction was successful based on NPS response codes. ACSP, ACSC
         * = Successful payment status; ACTC and ACCP are acknowledgements RJCT =
         * Rejected PDNG = Pending
         */
        public boolean isTransactionSuccessful() {
            if (responseCode == null) {
                return false;
            }
            return success && NpsPaymentStatus.fromCode(responseCode).isSuccessful()
                    && (statusId == null || "AUTH".equals(statusId));
        }

        public boolean isTransactionPending() {
            return NpsPaymentStatus.fromCode(responseCode).isPending();
        }

        public boolean isTransactionRejected() {
            return success && NpsPaymentStatus.fromCode(responseCode).isRejected()
                    && (statusId == null || "NAUT".equals(statusId) || "NOAN".equals(statusId));
        }

        @Override
        public String toString() {
            return "NpsResponse{" + "success=" + success + ", httpStatus=" + httpStatus + ", responseCode='"
                    + responseCode + '\'' + ", messageId='" + messageId + '\'' + ", signatureValid=" + signatureValid
                    + ", errorMessage='" + errorMessage + '\'' + '}';
        }
    }
}
