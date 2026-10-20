package com.provider.nps.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.provider.nps.crypto.NpsIdGenerator;
import com.provider.nps.crypto.NpsXmlEncryptor;
import com.provider.nps.crypto.NpsXmlSigner;
import com.provider.nps.internal.InboundConflictException;
import com.provider.nps.internal.InboundNpsForwarder;
import com.provider.nps.message.NpsInboundParser;
import com.provider.nps.message.NpsResponseParser;
import com.provider.nps.message.NpsResponseParser.NameEnquiryResponse;
import com.provider.nps.message.NpsResponseParser.PaymentStatusResponse;
import io.grpc.StatusRuntimeException;
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
 * NPS sends us messages when: - Inbound credit transfer (pacs.008) - we receive
 * funds - Payment status notification (pacs.002) - Name enquiry request
 * (acmt.023) - another FI queries our account
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

    @Inject
    NpsInboundParser inboundParser;

    @Inject
    NpsIdGenerator idGenerator;

    @Inject
    InboundNpsForwarder internalForwarder;

    @ConfigProperty(name = "nps.inbound.mode", defaultValue = "grpc")
    String inboundMode;

    /**
     * Callback for inbound credit transfer (pacs.008). NPS sends this when another
     * bank initiates a transfer TO us.
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
                    return Response.status(Response.Status.BAD_REQUEST).build();
                }
            }

            if (useInternalGrpc())
                return securedReply(internalForwarder.credit(decryptedXml));

            // Parse the credit transfer
            InboundCreditTransfer transfer = inboundParser.parseCreditTransfer(decryptedXml);

            // Process the inbound transfer (credit beneficiary account)
            CallbackResult result = callbackHandler.handleInboundCreditTransfer(transfer);

            // Build pacs.002 response
            String responseXml = buildPacs002Response(transfer, result).replaceAll(">\\s+<", "><");

            // Sign and encrypt response (skip in test mode)
            String finalResponse;
            if (skipCrypto) {
                finalResponse = responseXml;
            } else {
                String signedResponse = xmlSigner.sign(responseXml);
                finalResponse = xmlEncryptor.encrypt(signedResponse);
            }

            Log.infof("Processed inbound pacs.008: msgId=%s, status=%s", transfer.getMessageId(), result.getStatus());

            return Response.ok(finalResponse).build();

        } catch (StatusRuntimeException e) {
            Log.warn("Internal NPS service did not return a resolved result", e);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        } catch (InboundConflictException e) {
            return Response.status(Response.Status.CONFLICT).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        } catch (Exception e) {
            Log.error("Failed to process inbound pacs.008", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Callback for payment status notification (pacs.002). NPS sends this for async
     * status updates on our outbound transfers.
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

            if (useInternalGrpc()) {
                internalForwarder.paymentStatus(decryptedXml);
                return Response.ok().build();
            }

            // Parse status report
            PaymentStatusResponse status = responseParser.parsePaymentStatusResponse(decryptedXml);
            if (status.getErrorMessage() != null)
                return Response.status(Response.Status.BAD_REQUEST).build();

            // Update transaction status in our system
            callbackHandler.handlePaymentStatusUpdate(status);

            Log.infof("Processed pacs.002: originalMsgId=%s, status=%s", status.getOriginalMessageId(),
                    status.getTransactionStatus());

            return Response.ok().build();

        } catch (StatusRuntimeException e) {
            Log.warn("Internal NPS service did not return a resolved result", e);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        } catch (InboundConflictException e) {
            return Response.status(Response.Status.CONFLICT).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        } catch (Exception e) {
            Log.error("Failed to process pacs.002 callback", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Callback for inbound name enquiry (acmt.023). Another FI is querying one of
     * our accounts.
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
                    return Response.status(Response.Status.BAD_REQUEST).build();
                }
            }

            if (useInternalGrpc())
                return securedReply(internalForwarder.nameEnquiry(decryptedXml));

            // Parse the name enquiry request
            InboundNameEnquiry enquiry = inboundParser.parseNameEnquiry(decryptedXml);

            // Look up account in our system
            CallbackResult result = callbackHandler.handleInboundNameEnquiry(enquiry);

            // Build acmt.024 response
            String responseXml = buildAcmt024Response(enquiry, result).replaceAll(">\\s+<", "><");

            // Sign and encrypt response (skip in test mode)
            String finalResponse;
            if (skipCrypto) {
                finalResponse = responseXml;
            } else {
                String signedResponse = xmlSigner.sign(responseXml);
                finalResponse = xmlEncryptor.encrypt(signedResponse);
            }

            Log.infof("Processed inbound acmt.023: account=%s, found=%s", enquiry.getAccountNumber(),
                    result.isSuccess());

            return Response.ok(finalResponse).build();

        } catch (StatusRuntimeException e) {
            Log.warn("Internal NPS service did not return a resolved result", e);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        } catch (InboundConflictException e) {
            return Response.status(Response.Status.CONFLICT).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        } catch (Exception e) {
            Log.error("Failed to process inbound acmt.023", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Callback for name enquiry response (acmt.024). Async response to our outbound
     * name enquiry.
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
            if (response.getErrorMessage() != null)
                return Response.status(Response.Status.BAD_REQUEST).build();
            callbackHandler.handleNameEnquiryResponse(response);

            Log.infof("Processed acmt.024: msgId=%s, accountName=%s", response.getMessageId(),
                    response.getAccountName());

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
            if (response.getErrorMessage() != null)
                return Response.status(Response.Status.BAD_REQUEST).build();
            callbackHandler.handlePaymentInitiationResponse(response);

            Log.infof("Processed pain.002: msgId=%s, status=%s", response.getMessageId(),
                    response.getTransactionStatus());

            return Response.ok().build();

        } catch (Exception e) {
            Log.error("Failed to process pain.002 callback", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    private boolean useInternalGrpc() {
        if ("grpc".equals(inboundMode))
            return true;
        if ("legacy-test".equals(inboundMode) && skipCrypto)
            return false;
        throw io.grpc.Status.UNAVAILABLE.withDescription("Unsupported inbound mode").asRuntimeException();
    }

    private Response securedReply(String xml) throws Exception {
        String response = skipCrypto ? xml : xmlEncryptor.encrypt(xmlSigner.sign(xml));
        return Response.ok(response).build();
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
                </BizMsg>""", idGenerator.generateMessageId(), timestamp, idGenerator.generateMessageId(), timestamp,
                transfer.getMessageId(), transfer.getEndToEndId(), result.getStatus(),
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
                    </BizMsg>""", idGenerator.generateMessageId(), timestamp, idGenerator.generateMessageId(),
                    timestamp, enquiry.getMessageId(), result.getAccountName(), enquiry.getAccountNumber());
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
                    </BizMsg>""", idGenerator.generateMessageId(), timestamp, idGenerator.generateMessageId(),
                    timestamp, enquiry.getMessageId(), result.getReasonCode());
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
        public String getMessageId() {
            return messageId;
        }
        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }
        public String getEndToEndId() {
            return endToEndId;
        }
        public void setEndToEndId(String endToEndId) {
            this.endToEndId = endToEndId;
        }
        public String getTransactionId() {
            return transactionId;
        }
        public void setTransactionId(String transactionId) {
            this.transactionId = transactionId;
        }
        public String getAmount() {
            return amount;
        }
        public void setAmount(String amount) {
            this.amount = amount;
        }
        public String getDebtorName() {
            return debtorName;
        }
        public void setDebtorName(String debtorName) {
            this.debtorName = debtorName;
        }
        public String getDebtorAccount() {
            return debtorAccount;
        }
        public void setDebtorAccount(String debtorAccount) {
            this.debtorAccount = debtorAccount;
        }
        public String getDebtorBankCode() {
            return debtorBankCode;
        }
        public void setDebtorBankCode(String debtorBankCode) {
            this.debtorBankCode = debtorBankCode;
        }
        public String getCreditorName() {
            return creditorName;
        }
        public void setCreditorName(String creditorName) {
            this.creditorName = creditorName;
        }
        public String getCreditorAccount() {
            return creditorAccount;
        }
        public void setCreditorAccount(String creditorAccount) {
            this.creditorAccount = creditorAccount;
        }
        public String getNarration() {
            return narration;
        }
        public void setNarration(String narration) {
            this.narration = narration;
        }
    }

    public static class InboundNameEnquiry {
        private String messageId;
        private String accountNumber;
        private String sourceBankCode;
        private String channelCode;

        public String getMessageId() {
            return messageId;
        }
        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }
        public String getAccountNumber() {
            return accountNumber;
        }
        public void setAccountNumber(String accountNumber) {
            this.accountNumber = accountNumber;
        }
        public String getSourceBankCode() {
            return sourceBankCode;
        }
        public void setSourceBankCode(String sourceBankCode) {
            this.sourceBankCode = sourceBankCode;
        }
        public String getChannelCode() {
            return channelCode;
        }
        public void setChannelCode(String channelCode) {
            this.channelCode = channelCode;
        }
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

        public boolean isSuccess() {
            return success;
        }
        public String getStatus() {
            return status;
        }
        public String getMessage() {
            return message;
        }
        public String getReasonCode() {
            return reasonCode;
        }
        public String getAccountName() {
            return accountName;
        }
    }
}
