package com.provider.nps.message;

import static com.provider.nps.message.NpsFields.agent;
import static com.provider.nps.message.NpsFields.member;
import static com.provider.nps.message.NpsFields.required;
import static com.provider.nps.message.NpsFields.xml;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@ApplicationScoped
public class Pacs028Builder {
    @Inject
    NpsMessageBuilder baseBuilder;

    /** Incomplete legacy requests cannot satisfy the NIBSS field contract. */
    @Deprecated
    public String build(String originalMsgId, String originalEndToEndId) {
        throw new IllegalArgumentException("Complete original transaction details are required for requery");
    }

    public String build(PaymentStatusRequest request) {
        required(request.getOriginalMsgId(), "original_msg_id", 35);
        required(request.getOriginalTxId(), "original_tx_id", 35);
        required(request.getOriginalCreationDateTime(), "original_creation_time", 35);
        required(request.getOriginalSettlementDate(), "original_settlement_date", 10);
        try {
            OffsetDateTime.parse(request.getOriginalCreationDateTime());
            LocalDate.parse(request.getOriginalSettlementDate());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid original_creation_time or original_settlement_date", e);
        }
        member(request.getInstructingAgent(), "original_instructing_agent");
        member(request.getInstructedAgent(), "original_instructed_agent");
        String sourceMemberId = member(baseBuilder.getSourceId(), "NPS_SOURCE_ID");
        String messageId = baseBuilder.getIdGenerator().generateMessageId();
        return NpsMessageBuilder.XML_DECLARATION + "<ns2:Document xmlns:ns2=\"" + NpsMessageBuilder.PACS_028_NS
                + "\"><FIToFIPmtStsReq>" + "<GrpHdr><MsgId>" + xml(messageId) + "</MsgId><CreDtTm>"
                + xml(baseBuilder.getIdGenerator().currentTimestamp()) + "</CreDtTm><InstgAgt>" + agent(sourceMemberId)
                + "</InstgAgt></GrpHdr>" + "<OrgnlGrpInf><OrgnlMsgId>" + xml(request.getOriginalMsgId())
                + "</OrgnlMsgId>" + "<OrgnlMsgNmId>pacs.008.001.12</OrgnlMsgNmId><OrgnlCreDtTm>"
                + xml(request.getOriginalCreationDateTime()) + "</OrgnlCreDtTm></OrgnlGrpInf><TxInf><StsReqId>"
                + xml(messageId) + "</StsReqId>" + "<OrgnlTxId>" + xml(request.getOriginalTxId())
                + "</OrgnlTxId><InstgAgt>" + agent(request.getInstructingAgent()) + "</InstgAgt><InstdAgt>"
                + agent(request.getInstructedAgent()) + "</InstdAgt>" + "<OrgnlTxRef><IntrBkSttlmDt>"
                + xml(request.getOriginalSettlementDate())
                + "</IntrBkSttlmDt></OrgnlTxRef></TxInf></FIToFIPmtStsReq></ns2:Document>";
    }

    public static class PaymentStatusRequest {
        private String originalCreationDateTime;
        private String originalSettlementDate;
        private String instructingAgent;
        private String instructedAgent;
        public String getOriginalCreationDateTime() {
            return originalCreationDateTime;
        }
        public void setOriginalCreationDateTime(String value) {
            originalCreationDateTime = value;
        }
        public String getOriginalSettlementDate() {
            return originalSettlementDate;
        }
        public void setOriginalSettlementDate(String value) {
            originalSettlementDate = value;
        }
        public String getInstructingAgent() {
            return instructingAgent;
        }
        public void setInstructingAgent(String value) {
            instructingAgent = value;
        }
        public String getInstructedAgent() {
            return instructedAgent;
        }
        public void setInstructedAgent(String value) {
            instructedAgent = value;
        }
        private String originalMsgId;
        private String originalEndToEndId;
        private String originalTxId;

        public String getOriginalMsgId() {
            return originalMsgId;
        }

        public void setOriginalMsgId(String originalMsgId) {
            this.originalMsgId = originalMsgId;
        }

        public String getOriginalEndToEndId() {
            return originalEndToEndId;
        }

        public void setOriginalEndToEndId(String originalEndToEndId) {
            this.originalEndToEndId = originalEndToEndId;
        }

        public String getOriginalTxId() {
            return originalTxId;
        }

        public void setOriginalTxId(String originalTxId) {
            this.originalTxId = originalTxId;
        }
    }
}
