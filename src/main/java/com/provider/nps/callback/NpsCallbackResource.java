package com.provider.nps.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.provider.nps.crypto.NpsXmlEncryptor;
import com.provider.nps.crypto.NpsXmlSigner;
import com.provider.nps.message.NpsResponseParser;
import com.provider.nps.message.NpsResponseParser.NameEnquiryResponse;
import com.provider.nps.message.NpsResponseParser.PaymentStatusResponse;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * REST endpoints for inbound NPS callbacks.
 * 
 * NPS sends us messages when:
 * - Inbound credit transfer (pacs.008) - we receive funds
 * - Payment status notification (pacs.002)
 * - Name enquiry request (acmt.023) - another FI queries our account
 * 
 * All messages are signed and encrypted by NIBSS.
 */
@Path("/nps/callback")
@Consumes(MediaType.APPLICATION_XML)
@Produces(MediaType.APPLICATION_XML)
public class NpsCallbackResource {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ConfigProperty(name = "nps.skip-crypto", defaultValue = "false")
    boolean skipCrypto;

    @Inject
    NpsXmlEncryptor xmlEncryptor;

    @Inject
    NpsXmlSigner xmlSigner;

    @Inject
    NpsResponseParser responseParser;

    @Inject
    NpsCallbackHandler callbackHandler;

    /**
     * Callback for inbound credit transfer (pacs.008).
     * NPS sends this when another bank initiates a transfer TO us.
     */
    @POST
    @Path("/pacs008")
    public Response handleInboundCreditTransfer(String encryptedXml) {
        Log.info("Received inbound pacs.008 callback");

        try {
            // Decrypt and verify (skip in test mode)
            String decryptedXml;
            if (skipCrypto) {
                decryptedXml = encryptedXml;
                Log.debug("Crypto skipped for testing (callback)");
            } else {
                decryptedXml = xmlEncryptor.decrypt(encryptedXml);
                boolean signatureValid = xmlSigner.verify(decryptedXml);
                if (!signatureValid) {
                    Log.warn("Invalid signature on inbound pacs.008");
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(buildErrorResponse("RJCT", "Invalid signature"))
                            .build();
                }
            }

            // Parse the credit transfer
            InboundCreditTransfer transfer = parseInboundCreditTransfer(decryptedXml);

            // Process the inbound transfer (credit beneficiary account)
            CallbackResult result = callbackHandler.handleInboundCreditTransfer(transfer);

            // Build pacs.002 response
            String responseXml = buildPacs002Response(transfer, result);

            // Sign and encrypt response (skip in test mode)
            String finalResponse;
            if (skipCrypto) {
                finalResponse = responseXml;
            } else {
                String signedResponse = xmlSigner.sign(responseXml);
                finalResponse = xmlEncryptor.encrypt(signedResponse);
            }

            Log.infof("Processed inbound pacs.008: msgId=%s, status=%s", 
                    transfer.getMessageId(), result.getStatus());

            return Response.ok(finalResponse).build();

        } catch (Exception e) {
            Log.error("Failed to process inbound pacs.008", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(buildErrorResponse("RJCT", "Processing error"))
                    .build();
        }
    }

    /**
     * Callback for payment status notification (pacs.002).
     * NPS sends this for async status updates on our outbound transfers.
     */
    @POST
    @Path("/pacs002")
    public Response handlePaymentStatus(String encryptedXml) {
        Log.info("Received pacs.002 status callback");

        try {
            // Decrypt and verify (skip in test mode)
            String decryptedXml;
            if (skipCrypto) {
                decryptedXml = encryptedXml;
                Log.debug("Crypto skipped for testing (callback)");
            } else {
                decryptedXml = xmlEncryptor.decrypt(encryptedXml);
                boolean signatureValid = xmlSigner.verify(decryptedXml);
                if (!signatureValid) {
                    Log.warn("Invalid signature on pacs.002 callback");
                    return Response.status(Response.Status.BAD_REQUEST).build();
                }
            }

            // Parse status report
            PaymentStatusResponse status = responseParser.parsePaymentStatusResponse(decryptedXml);

            // Update transaction status in our system
            callbackHandler.handlePaymentStatusUpdate(status);

            Log.infof("Processed pacs.002: originalMsgId=%s, status=%s", 
                    status.getOriginalMessageId(), status.getTransactionStatus());

            return Response.ok().build();

        } catch (Exception e) {
            Log.error("Failed to process pacs.002 callback", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Callback for inbound name enquiry (acmt.023).
     * Another FI is querying one of our accounts.
     */
    @POST
    @Path("/acmt023")
    public Response handleInboundNameEnquiry(String encryptedXml) {
        Log.info("Received inbound acmt.023 callback");

        try {
            // Decrypt and verify (skip in test mode)
            String decryptedXml;
            if (skipCrypto) {
                decryptedXml = encryptedXml;
                Log.debug("Crypto skipped for testing (callback)");
            } else {
                decryptedXml = xmlEncryptor.decrypt(encryptedXml);
                boolean signatureValid = xmlSigner.verify(decryptedXml);
                if (!signatureValid) {
                    Log.warn("Invalid signature on inbound acmt.023");
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(buildErrorResponse("RJCT", "Invalid signature"))
                            .build();
                }
            }

            // Parse the name enquiry request
            InboundNameEnquiry enquiry = parseInboundNameEnquiry(decryptedXml);

            // Look up account in our system
            CallbackResult result = callbackHandler.handleInboundNameEnquiry(enquiry);

            // Build acmt.024 response
            String responseXml = buildAcmt024Response(enquiry, result);

            // Sign and encrypt response (skip in test mode)
            String finalResponse;
            if (skipCrypto) {
                finalResponse = responseXml;
            } else {
                String signedResponse = xmlSigner.sign(responseXml);
                finalResponse = xmlEncryptor.encrypt(signedResponse);
            }

            Log.infof("Processed inbound acmt.023: account=%s, found=%s", 
                    enquiry.getAccountNumber(), result.isSuccess());

            return Response.ok(finalResponse).build();

        } catch (Exception e) {
            Log.error("Failed to process inbound acmt.023", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(buildErrorResponse("RJCT", "Processing error"))
                    .build();
        }
    }

    /**
     * Callback for name enquiry response (acmt.024).
     * Async response to our outbound name enquiry.
     */
    @POST
    @Path("/acmt024")
    public Response handleNameEnquiryResponse(String encryptedXml) {
        Log.info("Received acmt.024 callback");

        try {
            // Decrypt and verify (skip in test mode)
            String decryptedXml;
            if (skipCrypto) {
                decryptedXml = encryptedXml;
                Log.debug("Crypto skipped for testing (callback)");
            } else {
                decryptedXml = xmlEncryptor.decrypt(encryptedXml);
                boolean signatureValid = xmlSigner.verify(decryptedXml);
                if (!signatureValid) {
                    Log.warn("Invalid signature on acmt.024 callback");
                    return Response.status(Response.Status.BAD_REQUEST).build();
                }
            }

            NameEnquiryResponse response = responseParser.parseNameEnquiryResponse(decryptedXml);
            callbackHandler.handleNameEnquiryResponse(response);

            Log.infof("Processed acmt.024: msgId=%s, accountName=%s", 
                    response.getMessageId(), response.getAccountName());

            return Response.ok().build();

        } catch (Exception e) {
            Log.error("Failed to process acmt.024 callback", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Callback for payment initiation response (pain.002).
     */
    @POST
    @Path("/pain002")
    public Response handlePaymentInitiationResponse(String encryptedXml) {
        Log.info("Received pain.002 callback");

        try {
            // Decrypt and verify (skip in test mode)
            String decryptedXml;
            if (skipCrypto) {
                decryptedXml = encryptedXml;
                Log.debug("Crypto skipped for testing (callback)");
            } else {
                decryptedXml = xmlEncryptor.decrypt(encryptedXml);
                boolean signatureValid = xmlSigner.verify(decryptedXml);
                if (!signatureValid) {
                    Log.warn("Invalid signature on pain.002 callback");
                    return Response.status(Response.Status.BAD_REQUEST).build();
                }
            }

            var response = responseParser.parsePaymentInitiationStatus(decryptedXml);
            callbackHandler.handlePaymentInitiationResponse(response);

            Log.infof("Processed pain.002: msgId=%s, status=%s", 
                    response.getMessageId(), response.getTransactionStatus());

            return Response.ok().build();

        } catch (Exception e) {
            Log.error("Failed to process pain.002 callback", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    // Helper methods for parsing inbound messages

    private InboundCreditTransfer parseInboundCreditTransfer(String xml) {
        InboundCreditTransfer transfer = new InboundCreditTransfer();
        
        transfer.setMessageId(extractValue(xml, "MsgId"));
        transfer.setEndToEndId(extractValue(xml, "EndToEndId"));
        transfer.setTransactionId(extractValue(xml, "TxId"));
        transfer.setAmount(extractAmount(xml));
        transfer.setDebtorName(extractDebtorName(xml));
        transfer.setDebtorAccount(extractDebtorAccount(xml));
        transfer.setDebtorBankCode(extractDebtorBankCode(xml));
        transfer.setCreditorName(extractCreditorName(xml));
        transfer.setCreditorAccount(extractCreditorAccount(xml));
        transfer.setNarration(extractValue(xml, "Ustrd"));
        
        return transfer;
    }

    private InboundNameEnquiry parseInboundNameEnquiry(String xml) {
        InboundNameEnquiry enquiry = new InboundNameEnquiry();
        
        enquiry.setMessageId(extractValue(xml, "MsgId"));
        enquiry.setAccountNumber(extractAccountNumber(xml));
        enquiry.setSourceBankCode(extractSourceBankCode(xml));
        enquiry.setChannelCode(extractValue(xml, "ChnlCd"));
        
        return enquiry;
    }

    private String extractValue(String xml, String tag) {
        String startTag = "<" + tag + ">";
        String endTag = "</" + tag + ">";
        int start = xml.indexOf(startTag);
        if (start < 0) return null;
        start += startTag.length();
        int end = xml.indexOf(endTag, start);
        if (end < 0) return null;
        return xml.substring(start, end).trim();
    }

    private String extractAmount(String xml) {
        // Look for IntrBkSttlmAmt or InstdAmt
        String amount = extractValue(xml, "IntrBkSttlmAmt");
        if (amount == null) {
            amount = extractValue(xml, "InstdAmt");
        }
        // Remove any nested content
        if (amount != null && amount.contains(">")) {
            amount = amount.substring(amount.lastIndexOf(">") + 1);
        }
        return amount;
    }

    private String extractDebtorName(String xml) {
        // Find Dbtr/Nm
        int dbtrStart = xml.indexOf("<Dbtr>");
        if (dbtrStart < 0) return null;
        int dbtrEnd = xml.indexOf("</Dbtr>", dbtrStart);
        String dbtrSection = xml.substring(dbtrStart, dbtrEnd);
        return extractValue(dbtrSection, "Nm");
    }

    private String extractDebtorAccount(String xml) {
        int dbtrAcctStart = xml.indexOf("<DbtrAcct>");
        if (dbtrAcctStart < 0) return null;
        int dbtrAcctEnd = xml.indexOf("</DbtrAcct>", dbtrAcctStart);
        String section = xml.substring(dbtrAcctStart, dbtrAcctEnd);
        return extractValue(section, "Id");
    }

    private String extractDebtorBankCode(String xml) {
        int dbtrAgtStart = xml.indexOf("<DbtrAgt>");
        if (dbtrAgtStart < 0) return null;
        int dbtrAgtEnd = xml.indexOf("</DbtrAgt>", dbtrAgtStart);
        String section = xml.substring(dbtrAgtStart, dbtrAgtEnd);
        String mmbId = extractValue(section, "MmbId");
        return mmbId != null ? mmbId : extractValue(section, "BICFI");
    }

    private String extractCreditorName(String xml) {
        int cdtrStart = xml.indexOf("<Cdtr>");
        if (cdtrStart < 0) return null;
        int cdtrEnd = xml.indexOf("</Cdtr>", cdtrStart);
        String section = xml.substring(cdtrStart, cdtrEnd);
        return extractValue(section, "Nm");
    }

    private String extractCreditorAccount(String xml) {
        int cdtrAcctStart = xml.indexOf("<CdtrAcct>");
        if (cdtrAcctStart < 0) return null;
        int cdtrAcctEnd = xml.indexOf("</CdtrAcct>", cdtrAcctStart);
        String section = xml.substring(cdtrAcctStart, cdtrAcctEnd);
        return extractValue(section, "Id");
    }

    private String extractAccountNumber(String xml) {
        // For name enquiry, look in Vrfctn/PtyAndAcctId/Acct
        int acctStart = xml.indexOf("<Acct>");
        if (acctStart < 0) return null;
        int acctEnd = xml.indexOf("</Acct>", acctStart);
        String section = xml.substring(acctStart, acctEnd);
        return extractValue(section, "Id");
    }

    private String extractSourceBankCode(String xml) {
        // Look for Assgnr bank code
        int assgnrStart = xml.indexOf("<Assgnr>");
        if (assgnrStart < 0) return null;
        int assgnrEnd = xml.indexOf("</Assgnr>", assgnrStart);
        String section = xml.substring(assgnrStart, assgnrEnd);
        String mmbId = extractValue(section, "MmbId");
        return mmbId != null ? mmbId : extractValue(section, "BICFI");
    }

    private String buildErrorResponse(String status, String reason) {
        return String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Error>
                    <Status>%s</Status>
                    <Reason>%s</Reason>
                </Error>""", status, reason);
    }

    private String buildPacs002Response(InboundCreditTransfer transfer, CallbackResult result) {
        // Build a pacs.002 status response for the inbound transfer
        String timestamp = java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Lagos"))
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        return String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <AppHdr xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                        <BizMsgIdr>%s</BizMsgIdr>
                        <MsgDefIdr>pacs.002.001.10</MsgDefIdr>
                        <CreDt>%s</CreDt>
                    </AppHdr>
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
                        <FIToFIPmtStsRpt>
                            <GrpHdr>
                                <MsgId>%s</MsgId>
                                <CreDtTm>%s</CreDtTm>
                            </GrpHdr>
                            <OrgnlGrpInfAndSts>
                                <OrgnlMsgId>%s</OrgnlMsgId>
                                <OrgnlMsgNmId>pacs.008.001.08</OrgnlMsgNmId>
                            </OrgnlGrpInfAndSts>
                            <TxInfAndSts>
                                <OrgnlEndToEndId>%s</OrgnlEndToEndId>
                                <TxSts>%s</TxSts>
                                %s
                            </TxInfAndSts>
                        </FIToFIPmtStsRpt>
                    </Document>
                </BizMsg>""",
                java.util.UUID.randomUUID().toString().replace("-", ""),
                timestamp,
                java.util.UUID.randomUUID().toString().replace("-", ""),
                timestamp,
                transfer.getMessageId(),
                transfer.getEndToEndId(),
                result.getStatus(),
                result.isSuccess() ? "" : String.format("""
                        <StsRsnInf>
                            <Rsn><Cd>%s</Cd></Rsn>
                            <AddtlInf>%s</AddtlInf>
                        </StsRsnInf>""", result.getReasonCode(), result.getMessage()));
    }

    private String buildAcmt024Response(InboundNameEnquiry enquiry, CallbackResult result) {
        String timestamp = java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Lagos"))
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        if (result.isSuccess()) {
            return String.format("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                        <AppHdr xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                            <BizMsgIdr>%s</BizMsgIdr>
                            <MsgDefIdr>acmt.024.001.03</MsgDefIdr>
                            <CreDt>%s</CreDt>
                        </AppHdr>
                        <Document xmlns="urn:iso:std:iso:20022:tech:xsd:acmt.024.001.03">
                            <IdVrfctnRpt>
                                <Assgnmt>
                                    <MsgId>%s</MsgId>
                                    <CreDtTm>%s</CreDtTm>
                                </Assgnmt>
                                <Rpt>
                                    <OrgnlId>%s</OrgnlId>
                                    <Vrfctn>true</Vrfctn>
                                    <UpdtdPtyAndAcctId>
                                        <Pty>
                                            <Nm>%s</Nm>
                                        </Pty>
                                        <Acct>
                                            <Id>
                                                <Othr>
                                                    <Id>%s</Id>
                                                </Othr>
                                            </Id>
                                        </Acct>
                                    </UpdtdPtyAndAcctId>
                                </Rpt>
                            </IdVrfctnRpt>
                        </Document>
                    </BizMsg>""",
                    java.util.UUID.randomUUID().toString().replace("-", ""),
                    timestamp,
                    java.util.UUID.randomUUID().toString().replace("-", ""),
                    timestamp,
                    enquiry.getMessageId(),
                    result.getAccountName(),
                    enquiry.getAccountNumber());
        } else {
            return String.format("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                        <AppHdr xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                            <BizMsgIdr>%s</BizMsgIdr>
                            <MsgDefIdr>acmt.024.001.03</MsgDefIdr>
                            <CreDt>%s</CreDt>
                        </AppHdr>
                        <Document xmlns="urn:iso:std:iso:20022:tech:xsd:acmt.024.001.03">
                            <IdVrfctnRpt>
                                <Assgnmt>
                                    <MsgId>%s</MsgId>
                                    <CreDtTm>%s</CreDtTm>
                                </Assgnmt>
                                <Rpt>
                                    <OrgnlId>%s</OrgnlId>
                                    <Vrfctn>false</Vrfctn>
                                    <Rsn>
                                        <Cd>%s</Cd>
                                    </Rsn>
                                </Rpt>
                            </IdVrfctnRpt>
                        </Document>
                    </BizMsg>""",
                    java.util.UUID.randomUUID().toString().replace("-", ""),
                    timestamp,
                    java.util.UUID.randomUUID().toString().replace("-", ""),
                    timestamp,
                    enquiry.getMessageId(),
                    result.getReasonCode());
        }
    }

    // Data classes for inbound messages

    public static class InboundCreditTransfer {
        private String messageId;
        private String endToEndId;
        private String transactionId;
        private String amount;
        private String debtorName;
        private String debtorAccount;
        private String debtorBankCode;
        private String creditorName;
        private String creditorAccount;
        private String narration;

        // Getters and setters
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getEndToEndId() { return endToEndId; }
        public void setEndToEndId(String endToEndId) { this.endToEndId = endToEndId; }
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }
        public String getDebtorName() { return debtorName; }
        public void setDebtorName(String debtorName) { this.debtorName = debtorName; }
        public String getDebtorAccount() { return debtorAccount; }
        public void setDebtorAccount(String debtorAccount) { this.debtorAccount = debtorAccount; }
        public String getDebtorBankCode() { return debtorBankCode; }
        public void setDebtorBankCode(String debtorBankCode) { this.debtorBankCode = debtorBankCode; }
        public String getCreditorName() { return creditorName; }
        public void setCreditorName(String creditorName) { this.creditorName = creditorName; }
        public String getCreditorAccount() { return creditorAccount; }
        public void setCreditorAccount(String creditorAccount) { this.creditorAccount = creditorAccount; }
        public String getNarration() { return narration; }
        public void setNarration(String narration) { this.narration = narration; }
    }

    public static class InboundNameEnquiry {
        private String messageId;
        private String accountNumber;
        private String sourceBankCode;
        private String channelCode;

        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
        public String getSourceBankCode() { return sourceBankCode; }
        public void setSourceBankCode(String sourceBankCode) { this.sourceBankCode = sourceBankCode; }
        public String getChannelCode() { return channelCode; }
        public void setChannelCode(String channelCode) { this.channelCode = channelCode; }
    }

    public static class CallbackResult {
        private boolean success;
        private String status; // ACTC, RJCT, etc.
        private String message;
        private String reasonCode;
        private String accountName;

        public static CallbackResult success(String accountName) {
            CallbackResult r = new CallbackResult();
            r.success = true;
            r.status = "ACTC";
            r.accountName = accountName;
            return r;
        }

        public static CallbackResult accepted() {
            CallbackResult r = new CallbackResult();
            r.success = true;
            r.status = "ACTC";
            return r;
        }

        public static CallbackResult rejected(String reasonCode, String message) {
            CallbackResult r = new CallbackResult();
            r.success = false;
            r.status = "RJCT";
            r.reasonCode = reasonCode;
            r.message = message;
            return r;
        }

        public boolean isSuccess() { return success; }
        public String getStatus() { return status; }
        public String getMessage() { return message; }
        public String getReasonCode() { return reasonCode; }
        public String getAccountName() { return accountName; }
    }
}
