package com.provider.nps.message;

import java.io.StringReader;
import java.math.BigDecimal;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Parser for NPS ISO 20022 response messages.
 * 
 * Parses:
 * - acmt.024 - Name enquiry response
 * - pacs.002 - Payment status report
 * - pain.002 - Payment initiation status
 */
@ApplicationScoped
public class NpsResponseParser {

    private final DocumentBuilderFactory documentBuilderFactory;

    public NpsResponseParser() {
        documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setNamespaceAware(true);
    }

    /**
     * Parses an acmt.024 name enquiry response.
     */
    public NameEnquiryResponse parseNameEnquiryResponse(String xml) {
        NameEnquiryResponse response = new NameEnquiryResponse();

        try {
            Document doc = parseXml(xml);

            // Extract from IdVrfctnRpt/Rpt
            response.setMessageId(extractText(doc, "MsgId"));
            
            // Result info
            response.setVerificationStatus(extractText(doc, "Rslt"));
            
            // Get the Rsn/Cd if present (for success/failure reason)
            NodeList rsnList = doc.getElementsByTagNameNS("*", "Rsn");
            if (rsnList.getLength() > 0) {
                Element rsn = (Element) rsnList.item(0);
                response.setReasonCode(extractChildText(rsn, "Cd"));
            }

            // Account details from Rpt/UpdtdPtyAndAcctId
            response.setAccountNumber(extractText(doc, "Id", "Othr"));
            response.setAccountName(extractAccountName(doc));
            response.setBvn(extractBvn(doc));
            response.setBankCode(extractBankCode(doc));

            // Session ID from SplmtryData
            response.setSessionId(extractText(doc, "SessId"));

            // Determine success based on response
            response.setSuccess(isSuccessfulVerification(response));

        } catch (Exception e) {
            Log.error("Failed to parse acmt.024 response", e);
            response.setSuccess(false);
            response.setErrorMessage("Parse error: " + e.getMessage());
        }

        return response;
    }

    /**
     * Parses a pacs.002 payment status report.
     */
    public PaymentStatusResponse parsePaymentStatusResponse(String xml) {
        PaymentStatusResponse response = new PaymentStatusResponse();

        try {
            Document doc = parseXml(xml);

            // Group header info
            response.setMessageId(extractText(doc, "MsgId"));
            response.setOriginalMessageId(extractText(doc, "OrgnlMsgId"));

            // Transaction status from TxInfAndSts
            response.setTransactionStatus(extractText(doc, "TxSts"));
            response.setEndToEndId(extractText(doc, "OrgnlEndToEndId"));
            response.setTransactionId(extractText(doc, "OrgnlTxId"));

            // Reason code if rejected
            NodeList stsRsnInfList = doc.getElementsByTagNameNS("*", "StsRsnInf");
            if (stsRsnInfList.getLength() > 0) {
                Element stsRsnInf = (Element) stsRsnInfList.item(0);
                response.setReasonCode(extractChildText(stsRsnInf, "Cd"));
                response.setReasonDescription(extractChildText(stsRsnInf, "AddtlInf"));
            }

            // Settlement date if available
            response.setSettlementDate(extractText(doc, "IntrBkSttlmDt"));

            // Session ID from SplmtryData
            response.setSessionId(extractText(doc, "SessId"));

            // Determine success
            response.setSuccess(isSuccessfulPayment(response.getTransactionStatus()));

        } catch (Exception e) {
            Log.error("Failed to parse pacs.002 response", e);
            response.setSuccess(false);
            response.setErrorMessage("Parse error: " + e.getMessage());
        }

        return response;
    }

    /**
     * Parses a pain.002 payment initiation status.
     */
    public PaymentInitiationStatusResponse parsePaymentInitiationStatus(String xml) {
        PaymentInitiationStatusResponse response = new PaymentInitiationStatusResponse();

        try {
            Document doc = parseXml(xml);

            response.setMessageId(extractText(doc, "MsgId"));
            response.setOriginalMessageId(extractText(doc, "OrgnlMsgId"));
            response.setTransactionStatus(extractText(doc, "TxSts"));
            response.setEndToEndId(extractText(doc, "OrgnlEndToEndId"));

            // Reason info
            NodeList stsRsnInfList = doc.getElementsByTagNameNS("*", "StsRsnInf");
            if (stsRsnInfList.getLength() > 0) {
                Element stsRsnInf = (Element) stsRsnInfList.item(0);
                response.setReasonCode(extractChildText(stsRsnInf, "Cd"));
                response.setReasonDescription(extractChildText(stsRsnInf, "AddtlInf"));
            }

            response.setSuccess(isSuccessfulPayment(response.getTransactionStatus()));

        } catch (Exception e) {
            Log.error("Failed to parse pain.002 response", e);
            response.setSuccess(false);
            response.setErrorMessage("Parse error: " + e.getMessage());
        }

        return response;
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private String extractText(Document doc, String tagName) {
        NodeList list = doc.getElementsByTagNameNS("*", tagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent().trim();
        }
        return null;
    }

    private String extractText(Document doc, String tagName, String parentTag) {
        NodeList parentList = doc.getElementsByTagNameNS("*", parentTag);
        if (parentList.getLength() > 0) {
            Element parent = (Element) parentList.item(0);
            NodeList list = parent.getElementsByTagNameNS("*", tagName);
            if (list.getLength() > 0) {
                return list.item(0).getTextContent().trim();
            }
        }
        return null;
    }

    private String extractChildText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagNameNS("*", tagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent().trim();
        }
        return null;
    }

    private String extractAccountName(Document doc) {
        // Try Nm in UpdtdPtyAndAcctId/Pty
        NodeList nmList = doc.getElementsByTagNameNS("*", "Nm");
        for (int i = 0; i < nmList.getLength(); i++) {
            Element nm = (Element) nmList.item(i);
            Element parent = (Element) nm.getParentNode();
            if (parent != null && parent.getLocalName() != null && 
                    (parent.getLocalName().equals("Pty") || parent.getLocalName().equals("Cdtr"))) {
                return nm.getTextContent().trim();
            }
        }
        return null;
    }

    private String extractBvn(Document doc) {
        // Look for BVN in PrvtId/Othr where SchmeNm/Prtry = "BVN"
        NodeList othrList = doc.getElementsByTagNameNS("*", "Othr");
        for (int i = 0; i < othrList.getLength(); i++) {
            Element othr = (Element) othrList.item(i);
            String prtry = extractChildText(othr, "Prtry");
            if ("BVN".equals(prtry)) {
                return extractChildText(othr, "Id");
            }
        }
        return null;
    }

    private String extractBankCode(Document doc) {
        return extractText(doc, "MmbId");
    }

    private boolean isSuccessfulVerification(NameEnquiryResponse response) {
        // Success if we got account name and no error reason
        if (response.getAccountName() != null && !response.getAccountName().isBlank()) {
            String reason = response.getReasonCode();
            // Check for success codes or absence of failure codes
            return reason == null || "ACTC".equals(reason) || "0000".equals(reason);
        }
        return false;
    }

    private boolean isSuccessfulPayment(String status) {
        if (status == null) return false;
        return "ACTC".equals(status) || "ACCP".equals(status) || 
                "ACSP".equals(status) || "ACSC".equals(status);
    }

    // Response classes
    public static class NameEnquiryResponse {
        private boolean success;
        private String messageId;
        private String verificationStatus;
        private String reasonCode;
        private String accountNumber;
        private String accountName;
        private String bvn;
        private String bankCode;
        private String sessionId;
        private String errorMessage;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getVerificationStatus() { return verificationStatus; }
        public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }
        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
        public String getAccountName() { return accountName; }
        public void setAccountName(String accountName) { this.accountName = accountName; }
        public String getBvn() { return bvn; }
        public void setBvn(String bvn) { this.bvn = bvn; }
        public String getBankCode() { return bankCode; }
        public void setBankCode(String bankCode) { this.bankCode = bankCode; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }

    public static class PaymentStatusResponse {
        private boolean success;
        private String messageId;
        private String originalMessageId;
        private String transactionStatus;
        private String endToEndId;
        private String transactionId;
        private String reasonCode;
        private String reasonDescription;
        private String settlementDate;
        private String sessionId;
        private String errorMessage;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getOriginalMessageId() { return originalMessageId; }
        public void setOriginalMessageId(String originalMessageId) { this.originalMessageId = originalMessageId; }
        public String getTransactionStatus() { return transactionStatus; }
        public void setTransactionStatus(String transactionStatus) { this.transactionStatus = transactionStatus; }
        public String getEndToEndId() { return endToEndId; }
        public void setEndToEndId(String endToEndId) { this.endToEndId = endToEndId; }
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
        public String getReasonDescription() { return reasonDescription; }
        public void setReasonDescription(String reasonDescription) { this.reasonDescription = reasonDescription; }
        public String getSettlementDate() { return settlementDate; }
        public void setSettlementDate(String settlementDate) { this.settlementDate = settlementDate; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

        public boolean isPending() {
            return "PDNG".equals(transactionStatus);
        }

        public boolean isRejected() {
            return "RJCT".equals(transactionStatus);
        }
    }

    public static class PaymentInitiationStatusResponse {
        private boolean success;
        private String messageId;
        private String originalMessageId;
        private String transactionStatus;
        private String endToEndId;
        private String reasonCode;
        private String reasonDescription;
        private String errorMessage;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getOriginalMessageId() { return originalMessageId; }
        public void setOriginalMessageId(String originalMessageId) { this.originalMessageId = originalMessageId; }
        public String getTransactionStatus() { return transactionStatus; }
        public void setTransactionStatus(String transactionStatus) { this.transactionStatus = transactionStatus; }
        public String getEndToEndId() { return endToEndId; }
        public void setEndToEndId(String endToEndId) { this.endToEndId = endToEndId; }
        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
        public String getReasonDescription() { return reasonDescription; }
        public void setReasonDescription(String reasonDescription) { this.reasonDescription = reasonDescription; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }
}
