package com.provider.nps.callback;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for NPS callback endpoints.
 * These endpoints receive notifications from NIBSS NPS.
 */
@QuarkusTest
class CallbackEndpointTest {

    @Test
    @DisplayName("POST /nps/callback/pacs008 should accept inbound credit transfer")
    void testInboundCreditTransfer() {
        String inboundTransfer = """
                <?xml version="1.0" encoding="UTF-8"?>
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
                        <FIToFICstmrCdtTrf>
                            <GrpHdr>
                                <MsgId>INBOUND123456789012345678901234</MsgId>
                                <CreDtTm>2026-08-05T12:00:00+01:00</CreDtTm>
                            </GrpHdr>
                            <CdtTrfTxInf>
                                <PmtId>
                                    <EndToEndId>E2E_INBOUND_123</EndToEndId>
                                    <TxId>TX_INBOUND_123</TxId>
                                </PmtId>
                                <IntrBkSttlmAmt Ccy="NGN">50000.00</IntrBkSttlmAmt>
                                <Dbtr>
                                    <Nm>SENDER NAME</Nm>
                                </Dbtr>
                                <DbtrAcct>
                                    <Id><Othr><Id>1234567890</Id></Othr></Id>
                                </DbtrAcct>
                                <DbtrAgt>
                                    <FinInstnId><BICFI>SENDERNGLA</BICFI></FinInstnId>
                                </DbtrAgt>
                                <Cdtr>
                                    <Nm>RECEIVER NAME</Nm>
                                </Cdtr>
                                <CdtrAcct>
                                    <Id><Othr><Id>0987654321</Id></Othr></Id>
                                </CdtrAcct>
                                <RmtInf>
                                    <Ustrd>Payment for services</Ustrd>
                                </RmtInf>
                            </CdtTrfTxInf>
                        </FIToFICstmrCdtTrf>
                    </Document>
                </BizMsg>
                """;

        given()
                .contentType("application/xml")
                .body(inboundTransfer)
                .when()
                .post("/nps/callback/pacs008")
                .then()
                .statusCode(200)
                .contentType(containsString("xml"));
    }

    @Test
    @DisplayName("POST /nps/callback/pacs002 should accept payment status notification")
    void testPaymentStatusCallback() {
        String statusNotification = """
                <?xml version="1.0" encoding="UTF-8"?>
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
                        <FIToFIPmtStsRpt>
                            <GrpHdr>
                                <MsgId>STATUS12345678901234567890123456</MsgId>
                                <CreDtTm>2026-08-05T12:00:00+01:00</CreDtTm>
                            </GrpHdr>
                            <OrgnlGrpInfAndSts>
                                <OrgnlMsgId>ORIGINAL_MSG_123</OrgnlMsgId>
                            </OrgnlGrpInfAndSts>
                            <TxInfAndSts>
                                <OrgnlEndToEndId>E2E_ORIG_123</OrgnlEndToEndId>
                                <TxSts>ACTC</TxSts>
                            </TxInfAndSts>
                        </FIToFIPmtStsRpt>
                    </Document>
                </BizMsg>
                """;

        given()
                .contentType("application/xml")
                .body(statusNotification)
                .when()
                .post("/nps/callback/pacs002")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("POST /nps/callback/acmt023 should handle inbound name enquiry")
    void testInboundNameEnquiry() {
        String nameEnquiry = """
                <?xml version="1.0" encoding="UTF-8"?>
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:acmt.023.001.03">
                        <IdVrfctnReq>
                            <Assgnmt>
                                <MsgId>ENQUIRY1234567890123456789012345</MsgId>
                                <CreDtTm>2026-08-05T12:00:00+01:00</CreDtTm>
                                <Assgnr>
                                    <Agt>
                                        <FinInstnId>
                                            <BICFI>OTHERNGLA</BICFI>
                                        </FinInstnId>
                                    </Agt>
                                </Assgnr>
                            </Assgnmt>
                            <Vrfctn>
                                <Id>VRF123</Id>
                                <PtyAndAcctId>
                                    <Acct>
                                        <Id><Othr><Id>0123456789</Id></Othr></Id>
                                    </Acct>
                                </PtyAndAcctId>
                            </Vrfctn>
                            <SplmtryData>
                                <Envlp>
                                    <ChnlCd>1</ChnlCd>
                                </Envlp>
                            </SplmtryData>
                        </IdVrfctnReq>
                    </Document>
                </BizMsg>
                """;

        given()
                .contentType("application/xml")
                .body(nameEnquiry)
                .when()
                .post("/nps/callback/acmt023")
                .then()
                .statusCode(200)
                .contentType(containsString("xml"));
    }

    @Test
    @DisplayName("POST /nps/callback/acmt024 should accept name enquiry response")
    void testNameEnquiryResponse() {
        String nameEnquiryResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:acmt.024.001.03">
                        <IdVrfctnRpt>
                            <Assgnmt>
                                <MsgId>RESP_ENQUIRY_123456789012345678</MsgId>
                            </Assgnmt>
                            <Rpt>
                                <Vrfctn>true</Vrfctn>
                                <UpdtdPtyAndAcctId>
                                    <Pty>
                                        <Nm>ACCOUNT HOLDER NAME</Nm>
                                    </Pty>
                                </UpdtdPtyAndAcctId>
                            </Rpt>
                        </IdVrfctnRpt>
                    </Document>
                </BizMsg>
                """;

        given()
                .contentType("application/xml")
                .body(nameEnquiryResponse)
                .when()
                .post("/nps/callback/acmt024")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("POST /nps/callback/pain002 should accept payment initiation status")
    void testPaymentInitiationStatus() {
        String painStatus = """
                <?xml version="1.0" encoding="UTF-8"?>
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.002.001.10">
                        <CstmrPmtStsRpt>
                            <GrpHdr>
                                <MsgId>PAIN_STATUS_123456789012345678</MsgId>
                            </GrpHdr>
                            <OrgnlGrpInfAndSts>
                                <OrgnlMsgId>ORIG_PAIN_123</OrgnlMsgId>
                            </OrgnlGrpInfAndSts>
                            <OrgnlPmtInfAndSts>
                                <TxInfAndSts>
                                    <OrgnlEndToEndId>E2E_PAIN_123</OrgnlEndToEndId>
                                    <TxSts>ACCP</TxSts>
                                </TxInfAndSts>
                            </OrgnlPmtInfAndSts>
                        </CstmrPmtStsRpt>
                    </Document>
                </BizMsg>
                """;

        given()
                .contentType("application/xml")
                .body(painStatus)
                .when()
                .post("/nps/callback/pain002")
                .then()
                .statusCode(200);
    }
}
