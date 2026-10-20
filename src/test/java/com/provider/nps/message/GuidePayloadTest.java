package com.provider.nps.message;

import static org.junit.jupiter.api.Assertions.*;

import com.provider.nps.crypto.NpsIdGenerator;
import com.provider.nps.xml.NpsXml;
import java.math.BigDecimal;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class GuidePayloadTest {
    private NpsMessageBuilder base() {
        var base = new NpsMessageBuilder();
        base.sourceId = "999057";
        base.idGenerator = new NpsIdGenerator() {
            public String generateMessageId() {
                return "99905720261017100000123456789012345";
            }
        };
        return base;
    }
    private Pacs008Builder.CreditTransferRequest transfer() {
        var r = new Pacs008Builder.CreditTransferRequest();
        r.setAmount(new BigDecimal("100.50"));
        r.setDebtorName("Sender & Sons <Ltd>");
        r.setDebtorAccount("0123456789");
        r.setCreditorName("Receiver");
        r.setCreditorAccount("9876543210");
        r.setCreditorBankCode("000013");
        r.setEndToEndId("TRANSFER-ID");
        var m = new HashMap<String, String>();
        for (String party : new String[]{"debtor", "creditor"}) {
            m.put(party + "_account_designation", "2");
            m.put(party + "_account_tier", "3");
            m.put(party + "_id_type", "BVN");
            m.put(party + "_id_value", "22123456789");
        }
        m.put("name_enquiry_msg_id", "ENQUIRY-ID");
        m.put("channel_code", "1");
        m.put("transaction_location", "01080652440N020900337921E");
        r.setVerificationDetails(m);
        return r;
    }
    @Test
    void transferEscapesNamesAndKeepsEnquirySeparateFromTransferId() {
        var builder = new Pacs008Builder();
        builder.baseBuilder = base();
        var doc = NpsXml.parse(builder.build(transfer()));
        String path = "Document/FIToFICstmrCdtTrf/";
        assertEquals("Sender & Sons <Ltd>", NpsXml.text(doc, path + "CdtTrfTxInf/Dbtr/Nm"));
        assertEquals("TRANSFER-ID", NpsXml.text(doc, path + "CdtTrfTxInf/PmtId/EndToEndId"));
        assertEquals("ENQUIRY-ID",
                NpsXml.text(doc, path + "SplmtryData/Envlp/CustomData/TransactionInfo/NameEnquiryMsgId"));
        assertEquals("999057", NpsXml.text(doc, path + "GrpHdr/InstgAgt/FinInstnId/ClrSysMmbId/MmbId"));
        assertEquals("0123456789", NpsXml.text(doc, path + "CdtTrfTxInf/DbtrAcct/Id/IBAN"));
    }
    @Test
    void invalidIdentityCombinationsAndMissingDataAreRejected() {
        var builder = new Pacs008Builder();
        builder.baseBuilder = base();
        for (String type : new String[]{"NIN", "RC", "UNKNOWN"}) {
            var r = transfer();
            var m = new HashMap<>(r.getVerificationDetails());
            m.put("debtor_id_type", type);
            r.setVerificationDetails(m);
            assertThrows(IllegalArgumentException.class, () -> builder.build(r));
        }
        var missing = transfer();
        missing.setVerificationDetails(java.util.Map.of());
        assertThrows(IllegalArgumentException.class, () -> builder.build(missing));
        var r = transfer();
        r.setAmount(new BigDecimal("0.001"));
        var fractional = r;
        assertThrows(IllegalArgumentException.class, () -> builder.build(fractional));
    }
    @Test
    void queryIncludesOriginalDetailsAndRejectsIncompleteRequests() {
        var builder = new Pacs028Builder();
        builder.baseBuilder = base();
        var r = new Pacs028Builder.PaymentStatusRequest();
        assertThrows(IllegalArgumentException.class, () -> builder.build(r));
        r.setOriginalMsgId("ORIGINAL");
        r.setOriginalTxId("TX-ID");
        r.setOriginalCreationDateTime("2026-10-17T10:00:00Z");
        r.setOriginalSettlementDate("2026-10-17");
        r.setInstructingAgent("999057");
        r.setInstructedAgent("000013");
        var doc = NpsXml.parse(builder.build(r));
        String path = "Document/FIToFIPmtStsReq/";
        assertEquals("TX-ID", NpsXml.text(doc, path + "TxInf/OrgnlTxId"));
        assertEquals("2026-10-17T10:00:00Z", NpsXml.text(doc, path + "OrgnlGrpInf/OrgnlCreDtTm"));
        assertEquals("2026-10-17", NpsXml.text(doc, path + "TxInf/OrgnlTxRef/IntrBkSttlmDt"));
        assertEquals("000013", NpsXml.text(doc, path + "TxInf/InstdAgt/FinInstnId/ClrSysMmbId/MmbId"));
        r.setOriginalCreationDateTime("yesterday");
        assertThrows(IllegalArgumentException.class, () -> builder.build(r));
    }
}
