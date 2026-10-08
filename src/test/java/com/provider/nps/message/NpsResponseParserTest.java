package com.provider.nps.message;

import static org.junit.jupiter.api.Assertions.*;

import com.provider.nps.message.NpsResponseParser.NameEnquiryResponse;
import com.provider.nps.message.NpsResponseParser.PaymentStatusResponse;
import org.junit.jupiter.api.Test;

class NpsResponseParserTest {

    NpsResponseParser parser = new NpsResponseParser();

    @Test
    void negativeVerificationCannotSucceedBecauseANameIsPresent() {
        String xml = """
                <BizMsg><Document><IdVrfctnRpt><Rpt>
                    <Vrfctn>false</Vrfctn>
                    <UpdtdPtyAndAcctId><Pty><Nm>Known Name</Nm></Pty>
                        <Acct><Id><Othr><Id>0123456789</Id></Othr></Id></Acct>
                    </UpdtdPtyAndAcctId>
                </Rpt></IdVrfctnRpt></Document></BizMsg>
                """;
        assertFalse(parser.parseNameEnquiryResponse(xml).isSuccess());
    }

    @Test
    void accountNumberDoesNotResolveToBvn() {
        String xml = """
                <BizMsg><Document><IdVrfctnRpt><Rpt><Vrfctn>true</Vrfctn>
                    <UpdtdPtyAndAcctId>
                        <Pty><Nm>Known Name</Nm><Id><PrvtId><Othr><Id>22123456789</Id>
                            <SchmeNm><Prtry>BVN</Prtry></SchmeNm>
                        </Othr></PrvtId></Id></Pty>
                        <Acct><Id><Othr><Id>0123456789</Id></Othr></Id></Acct>
                    </UpdtdPtyAndAcctId>
                </Rpt></IdVrfctnRpt></Document></BizMsg>
                """;
        var response = parser.parseNameEnquiryResponse(xml);
        assertEquals("0123456789", response.getAccountNumber());
        assertEquals("22123456789", response.getBvn());
    }

    @Test
    void technicalAcceptanceIsPending() {
        String xml = "<BizMsg><Document><FIToFIPmtStsRpt><TxInfAndSts><TxSts>ACTC</TxSts>"
                + "</TxInfAndSts></FIToFIPmtStsRpt></Document></BizMsg>";
        var response = parser.parsePaymentStatusResponse(xml);
        assertFalse(response.isSuccess());
        assertTrue(response.isPending());
    }

    @Test
    void testParseNameEnquirySuccessResponse() {
        String xml = """
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:acmt.024.001.03">
                        <IdVrfctnRpt>
                            <Assgnmt>
                                <MsgId>IRPYNGLA00000020260805120000123456789</MsgId>
                            </Assgnmt>
                            <Rpt>
                                <Vrfctn>true</Vrfctn>
                                <UpdtdPtyAndAcctId>
                                    <Pty>
                                        <Nm>JOHN DOE</Nm>
                                    </Pty>
                                    <Acct>
                                        <Id>
                                            <Othr>
                                                <Id>0123456789</Id>
                                            </Othr>
                                        </Id>
                                    </Acct>
                                </UpdtdPtyAndAcctId>
                            </Rpt>
                        </IdVrfctnRpt>
                    </Document>
                </BizMsg>
                """;

        NameEnquiryResponse response = parser.parseNameEnquiryResponse(xml);

        assertTrue(response.isSuccess());
        assertEquals("JOHN DOE", response.getAccountName());
        assertEquals("0123456789", response.getAccountNumber());
    }

    @Test
    void testParsePaymentStatusSuccess() {
        String xml = """
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
                        <FIToFIPmtStsRpt>
                            <GrpHdr>
                                <MsgId>MSG123456789</MsgId>
                            </GrpHdr>
                            <OrgnlGrpInfAndSts>
                                <OrgnlMsgId>ORIGINAL123</OrgnlMsgId>
                            </OrgnlGrpInfAndSts>
                            <TxInfAndSts>
                                <OrgnlEndToEndId>E2E123</OrgnlEndToEndId>
                                <TxSts>ACSP</TxSts>
                            </TxInfAndSts>
                        </FIToFIPmtStsRpt>
                    </Document>
                </BizMsg>
                """;

        PaymentStatusResponse response = parser.parsePaymentStatusResponse(xml);

        assertTrue(response.isSuccess());
        assertEquals("ACSP", response.getTransactionStatus());
        assertEquals("ORIGINAL123", response.getOriginalMessageId());
        assertEquals("E2E123", response.getEndToEndId());
        assertFalse(response.isPending());
        assertFalse(response.isRejected());
    }

    @Test
    void testParsePaymentStatusRejected() {
        String xml = """
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
                        <FIToFIPmtStsRpt>
                            <GrpHdr>
                                <MsgId>MSG123456789</MsgId>
                            </GrpHdr>
                            <TxInfAndSts>
                                <TxSts>RJCT</TxSts>
                                <StsRsnInf>
                                    <Rsn><Cd>AC01</Cd></Rsn>
                                    <AddtlInf>Invalid account</AddtlInf>
                                </StsRsnInf>
                            </TxInfAndSts>
                        </FIToFIPmtStsRpt>
                    </Document>
                </BizMsg>
                """;

        PaymentStatusResponse response = parser.parsePaymentStatusResponse(xml);

        assertFalse(response.isSuccess());
        assertTrue(response.isRejected());
        assertEquals("RJCT", response.getTransactionStatus());
        assertEquals("AC01", response.getReasonCode());
        assertEquals("Invalid account", response.getReasonDescription());
    }
}
