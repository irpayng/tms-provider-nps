package com.provider.nps.message;

import static org.junit.jupiter.api.Assertions.*;

import com.provider.nps.xml.NpsXml;
import org.junit.jupiter.api.Test;

class GuideCompatibilityTest {
    @Test
    void directPrefixedDocumentReadsIbanAccounts() {
        String xml = """
                <p:Document xmlns:p="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.12">
                <FIToFICstmrCdtTrf><GrpHdr><MsgId>inbound-id</MsgId></GrpHdr><CdtTrfTxInf>
                <IntrBkSttlmAmt Ccy="NGN">100.50</IntrBkSttlmAmt><DbtrAcct><Id><IBAN>0123456789</IBAN></Id></DbtrAcct>
                <CdtrAcct><Id><IBAN>9876543210</IBAN></Id></CdtrAcct></CdtTrfTxInf></FIToFICstmrCdtTrf></p:Document>
                """;
        var transfer = new NpsInboundParser().parseCreditTransfer(xml);
        assertEquals("inbound-id", transfer.getMessageId());
        assertEquals("100.50", transfer.getAmount());
        assertEquals("0123456789", transfer.getDebtorAccount());
        assertEquals("9876543210", transfer.getCreditorAccount());
    }
    @Test
    void directNameEnquiryReadsAccountAndSourceMember() {
        var enquiry = new NpsInboundParser().parseNameEnquiry("""
                <Document><IdVrfctnReq><Assgnmt><MsgId>enquiry-id</MsgId><Assgnr><Agt><FinInstnId>
                <ClrSysMmbId><MmbId>999057</MmbId></ClrSysMmbId></FinInstnId></Agt></Assgnr></Assgnmt>
                <Vrfctn><PtyAndAcctId><Acct><Id><IBAN>0123456789</IBAN></Id></Acct></PtyAndAcctId></Vrfctn>
                </IdVrfctnReq></Document>
                """);
        assertEquals("0123456789", enquiry.getAccountNumber());
        assertEquals("999057", enquiry.getSourceBankCode());
    }
    @Test
    void directPaymentReportUsesGroupStatusOnlyWhenTransactionStatusIsAbsent() {
        String report = "<Document><FIToFIPmtStsRpt><OrgnlGrpInfAndSts><OrgnlMsgId>original-id</OrgnlMsgId>"
                + "<GrpSts>ACSC</GrpSts></OrgnlGrpInfAndSts>%s</FIToFIPmtStsRpt></Document>";
        var parser = new NpsResponseParser();
        assertTrue(parser.parsePaymentStatusResponse(report.formatted("")).isSuccess());
        var unknown = parser
                .parsePaymentStatusResponse(report.formatted("<TxInfAndSts><TxSts>UNKNOWN</TxSts></TxInfAndSts>"));
        assertFalse(unknown.isSuccess());
        assertFalse(unknown.isRejected());
        assertEquals("UNKNOWN", unknown.getTransactionStatus());
        var rejected = parser
                .parsePaymentStatusResponse(report.formatted("<TxInfAndSts><TxSts>RJCT</TxSts></TxInfAndSts>"));
        assertTrue(rejected.isRejected());
    }
    @Test
    void directVerificationDoesNotReadBvnFromAnUnrelatedParty() {
        String xml = """
                <Document><IdVrfctnRpt><Assgnmt><MsgId>response-id</MsgId><Othr><Id>WRONG-BVN</Id>
                <SchmeNm><Prtry>BVN</Prtry></SchmeNm></Othr></Assgnmt><Rpt><Vrfctn>true</Vrfctn>
                <UpdtdPtyAndAcctId><Pty><Nm>Receiver</Nm></Pty><Acct><Id><IBAN>0123456789</IBAN></Id></Acct>
                </UpdtdPtyAndAcctId></Rpt></IdVrfctnRpt></Document>
                """;
        var result = new NpsResponseParser().parseNameEnquiryResponse(xml);
        assertTrue(result.isSuccess());
        assertEquals("0123456789", result.getAccountNumber());
        assertNull(result.getBvn());
    }
    @Test
    void ambiguousOrUnexpectedEnvelopesAreRejected() {
        for (String xml : new String[]{"<Wrapper><Document><IdVrfctnReq/></Document></Wrapper>",
                "<BizMsg><Document><IdVrfctnReq/></Document><Document><IdVrfctnReq/></Document></BizMsg>",
                "<Document><IdVrfctnReq/><IdVrfctnReq/></Document>"}) {
            assertThrows(IllegalArgumentException.class, () -> NpsXml.businessPath(NpsXml.parse(xml), "IdVrfctnReq"));
        }
    }
    @Test
    void guideVerificationReturnsOriginalAccountAndCreditorVerificationDetails() {
        var result = new NpsResponseParser().parseNameEnquiryResponse(
                """
                        <Document><IdVrfctnRpt><Assgnmt><MsgId>response-id</MsgId><Assgnr><Agt><FinInstnId>
                        <ClrSysMmbId><MmbId>000013</MmbId></ClrSysMmbId></FinInstnId></Agt></Assgnr></Assgnmt>
                        <Rpt><Vrfctn>true</Vrfctn><OrgnlPtyAndAcctId><Acct><Id><IBAN>0123456789</IBAN></Id></Acct></OrgnlPtyAndAcctId>
                        <UpdtdPtyAndAcctId><Pty><Nm>Receiver</Nm></Pty></UpdtdPtyAndAcctId></Rpt>
                        <SplmtryData><Envlp><CustomData><CreditorInfo><AccountDesignation>2</AccountDesignation>
                        <IdType>BVN</IdType><IdValue>22123456789</IdValue><AccountTier>3</AccountTier></CreditorInfo>
                        <TransactionInfo><RiskRating>R000000000000000000B9</RiskRating></TransactionInfo></CustomData></Envlp></SplmtryData>
                        </IdVrfctnRpt></Document>
                        """);
        assertTrue(result.isSuccess());
        assertEquals("0123456789", result.getAccountNumber());
        assertEquals("000013", result.getBankCode());
        assertEquals("22123456789", result.getBvn());
        assertEquals("2", result.getAccountDesignation());
        assertEquals("3", result.getAccountTier());
        assertEquals("BVN", result.getIdType());
        assertEquals("22123456789", result.getIdValue());
        assertEquals("R000000000000000000B9", result.getRiskRating());
    }
    @Test
    void parsesAuthorizationAndTimeoutStatusIdsAndProprietaryReasons() {
        for (String statusId : new String[]{"AUTH", "NAUT", "NOAN"}) {
            String group = statusId.equals("AUTH") ? "ACSC" : "RJCT";
            var status = new NpsResponseParser().parsePaymentStatusResponse("""
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.12"><FIToFIPmtStsRpt>
                    <OrgnlGrpInfAndSts><OrgnlMsgId>original-id</OrgnlMsgId><GrpSts>%s</GrpSts></OrgnlGrpInfAndSts>
                    <TxInfAndSts><StsId>%s</StsId><OrgnlInstrId>instruction-id</OrgnlInstrId>
                    <StsRsnInf><Rsn><Prtry>AC01</Prtry></Rsn><AddtlInf>Rejected by timeout</AddtlInf></StsRsnInf>
                    </TxInfAndSts></FIToFIPmtStsRpt></Document>
                    """.formatted(group, statusId));
            assertEquals(statusId, status.getStatusId());
            assertEquals(group, status.getTransactionStatus());
            assertEquals("instruction-id", status.getOriginalInstructionId());
            assertEquals("AC01", status.getReasonCode());
            assertEquals("Rejected by timeout", status.getReasonDescription());
        }
    }
    @Test
    void conflictingAuthorizationAndGroupStatusCannotEstablishAnOutcome() {
        for (String values : new String[]{"ACSC NAUT", "ACSC NOAN", "ACSC UNKNOWN", "RJCT AUTH"}) {
            var pair = values.split(" ");
            var status = new NpsResponseParser().parsePaymentStatusResponse("""
                    <Document><FIToFIPmtStsRpt><OrgnlGrpInfAndSts><GrpSts>%s</GrpSts></OrgnlGrpInfAndSts>
                    <TxInfAndSts><StsId>%s</StsId></TxInfAndSts></FIToFIPmtStsRpt></Document>
                    """.formatted(pair[0], pair[1]));
            assertFalse(status.isSuccess());
            assertFalse(status.isRejected());
        }
    }
}
