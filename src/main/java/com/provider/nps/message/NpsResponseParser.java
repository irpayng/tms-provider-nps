package com.provider.nps.message;

import com.provider.nps.xml.NpsXml;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parser for NPS ISO 20022 response messages.
 * 
 * Parses: - acmt.024 - Name enquiry response - pacs.002 - Payment status report
 * - pain.002 - Payment initiation status
 */
@ApplicationScoped
public class NpsResponseParser {

    /**
     * Parses an acmt.024 name enquiry response.
     */
    public NameEnquiryResponse parseNameEnquiryResponse(String xml) {
        NameEnquiryResponse response = new NameEnquiryResponse();

        try {
            Document doc = NpsXml.parse(xml);

            String report = "BizMsg/Document/IdVrfctnRpt/";
            String account = report + "Rpt/UpdtdPtyAndAcctId/";
            response.setMessageId(NpsXml.text(doc, report + "Assgnmt/MsgId"));
            response.setVerificationStatus(NpsXml.text(doc, report + "Rpt/Vrfctn"));
            response.setReasonCode(NpsXml.text(doc, report + "Rpt/Rsn/Cd"));
            response.setAccountNumber(NpsXml.text(doc, account + "Acct/Id/Othr/Id"));
            response.setAccountName(NpsXml.text(doc, account + "Pty/Nm"));
            response.setBvn(extractBvn(doc));
            response.setBankCode(NpsXml.text(doc, account + "Agt/FinInstnId/ClrSysMmbId/MmbId"));
            response.setSessionId(NpsXml.text(doc, report + "SplmtryData/Envlp/SessId"));

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
            Document doc = NpsXml.parse(xml);

            String report = "BizMsg/Document/FIToFIPmtStsRpt/";
            String transaction = report + "TxInfAndSts/";
            response.setMessageId(NpsXml.text(doc, report + "GrpHdr/MsgId"));
            response.setOriginalMessageId(NpsXml.text(doc, report + "OrgnlGrpInfAndSts/OrgnlMsgId"));
            response.setTransactionStatus(NpsXml.text(doc, transaction + "TxSts"));
            response.setEndToEndId(NpsXml.text(doc, transaction + "OrgnlEndToEndId"));
            response.setTransactionId(NpsXml.text(doc, transaction + "OrgnlTxId"));
            response.setReasonCode(NpsXml.text(doc, transaction + "StsRsnInf/Rsn/Cd"));
            response.setReasonDescription(NpsXml.text(doc, transaction + "StsRsnInf/AddtlInf"));
            response.setSettlementDate(NpsXml.text(doc, transaction + "OrgnlTxRef/IntrBkSttlmDt"));
            response.setSessionId(NpsXml.text(doc, report + "SplmtryData/Envlp/SessId"));

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
            Document doc = NpsXml.parse(xml);

            String report = "BizMsg/Document/CstmrPmtStsRpt/";
            String transaction = report + "OrgnlPmtInfAndSts/TxInfAndSts/";
            response.setMessageId(NpsXml.text(doc, report + "GrpHdr/MsgId"));
            response.setOriginalMessageId(NpsXml.text(doc, report + "OrgnlGrpInfAndSts/OrgnlMsgId"));
            response.setTransactionStatus(NpsXml.text(doc, transaction + "TxSts"));
            response.setEndToEndId(NpsXml.text(doc, transaction + "OrgnlEndToEndId"));
            response.setReasonCode(NpsXml.text(doc, transaction + "StsRsnInf/Rsn/Cd"));
            response.setReasonDescription(NpsXml.text(doc, transaction + "StsRsnInf/AddtlInf"));

            response.setSuccess(isSuccessfulPayment(response.getTransactionStatus()));

        } catch (Exception e) {
            Log.error("Failed to parse pain.002 response", e);
            response.setSuccess(false);
            response.setErrorMessage("Parse error: " + e.getMessage());
        }

        return response;
    }

    private String extractBvn(Document doc) {
        // Look for BVN in PrvtId/Othr where SchmeNm/Prtry = "BVN"
        NodeList othrList = doc.getElementsByTagNameNS("*", "Othr");
        for (int i = 0; i < othrList.getLength(); i++) {
            Element othr = (Element) othrList.item(i);
            String prtry = NpsXml.text(othr, "SchmeNm/Prtry");
            if ("BVN".equals(prtry)) {
                return NpsXml.text(othr, "Id");
            }
        }
        return null;
    }

    private boolean isSuccessfulVerification(NameEnquiryResponse response) {
        // A name alone is not proof that the account was verified.
        if (!"true".equals(response.getVerificationStatus()) && !"1".equals(response.getVerificationStatus())) {
            return false;
        }
        // Success requires verified account details and no error reason
        if (response.getAccountName() != null && !response.getAccountName().isBlank()) {
            String reason = response.getReasonCode();
            // Check for success codes or absence of failure codes
            return reason == null || "ACTC".equals(reason) || "0000".equals(reason);
        }
        return false;
    }

    private boolean isSuccessfulPayment(String status) {
        if (status == null)
            return false;
        return NpsPaymentStatus.fromCode(status).isSuccessful();
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

        public boolean isSuccess() {
            return success;
        }
        public void setSuccess(boolean success) {
            this.success = success;
        }
        public String getMessageId() {
            return messageId;
        }
        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }
        public String getVerificationStatus() {
            return verificationStatus;
        }
        public void setVerificationStatus(String verificationStatus) {
            this.verificationStatus = verificationStatus;
        }
        public String getReasonCode() {
            return reasonCode;
        }
        public void setReasonCode(String reasonCode) {
            this.reasonCode = reasonCode;
        }
        public String getAccountNumber() {
            return accountNumber;
        }
        public void setAccountNumber(String accountNumber) {
            this.accountNumber = accountNumber;
        }
        public String getAccountName() {
            return accountName;
        }
        public void setAccountName(String accountName) {
            this.accountName = accountName;
        }
        public String getBvn() {
            return bvn;
        }
        public void setBvn(String bvn) {
            this.bvn = bvn;
        }
        public String getBankCode() {
            return bankCode;
        }
        public void setBankCode(String bankCode) {
            this.bankCode = bankCode;
        }
        public String getSessionId() {
            return sessionId;
        }
        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }
        public String getErrorMessage() {
            return errorMessage;
        }
        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
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

        public boolean isSuccess() {
            return success;
        }
        public void setSuccess(boolean success) {
            this.success = success;
        }
        public String getMessageId() {
            return messageId;
        }
        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }
        public String getOriginalMessageId() {
            return originalMessageId;
        }
        public void setOriginalMessageId(String originalMessageId) {
            this.originalMessageId = originalMessageId;
        }
        public String getTransactionStatus() {
            return transactionStatus;
        }
        public void setTransactionStatus(String transactionStatus) {
            this.transactionStatus = transactionStatus;
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
        public String getReasonCode() {
            return reasonCode;
        }
        public void setReasonCode(String reasonCode) {
            this.reasonCode = reasonCode;
        }
        public String getReasonDescription() {
            return reasonDescription;
        }
        public void setReasonDescription(String reasonDescription) {
            this.reasonDescription = reasonDescription;
        }
        public String getSettlementDate() {
            return settlementDate;
        }
        public void setSettlementDate(String settlementDate) {
            this.settlementDate = settlementDate;
        }
        public String getSessionId() {
            return sessionId;
        }
        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }
        public String getErrorMessage() {
            return errorMessage;
        }
        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public boolean isPending() {
            return NpsPaymentStatus.fromCode(transactionStatus).isPending();
        }

        public boolean isRejected() {
            return NpsPaymentStatus.fromCode(transactionStatus).isRejected();
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

        public boolean isSuccess() {
            return success;
        }
        public void setSuccess(boolean success) {
            this.success = success;
        }
        public String getMessageId() {
            return messageId;
        }
        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }
        public String getOriginalMessageId() {
            return originalMessageId;
        }
        public void setOriginalMessageId(String originalMessageId) {
            this.originalMessageId = originalMessageId;
        }
        public String getTransactionStatus() {
            return transactionStatus;
        }
        public void setTransactionStatus(String transactionStatus) {
            this.transactionStatus = transactionStatus;
        }
        public String getEndToEndId() {
            return endToEndId;
        }
        public void setEndToEndId(String endToEndId) {
            this.endToEndId = endToEndId;
        }
        public String getReasonCode() {
            return reasonCode;
        }
        public void setReasonCode(String reasonCode) {
            this.reasonCode = reasonCode;
        }
        public String getReasonDescription() {
            return reasonDescription;
        }
        public void setReasonDescription(String reasonDescription) {
            this.reasonDescription = reasonDescription;
        }
        public String getErrorMessage() {
            return errorMessage;
        }
        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }
}
