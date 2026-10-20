package com.provider.nps.internal;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import com.provider.nps.internal.grpc.CreditDecision;
import com.provider.nps.xml.NpsXml;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(InboundGrpcProfile.class)
@QuarkusTestResource(value = InternalServiceTestResource.class, restrictToAnnotatedClass = true)
class InboundGrpcTest {
    static final String SEED_ID = "00001320261017100000123456789012345";
    static final String CREDIT = """
            <ns2:Document xmlns:ns2="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.12"><FIToFICstmrCdtTrf>
            <GrpHdr><MsgId>%s</MsgId><CreDtTm>2026-10-17T10:00:00Z</CreDtTm></GrpHdr><CdtTrfTxInf>
            <PmtId><InstrId>instruction-id</InstrId><EndToEndId>end-id</EndToEndId><TxId>transaction-id</TxId></PmtId>
            <IntrBkSttlmAmt Ccy="NGN">100.50</IntrBkSttlmAmt><IntrBkSttlmDt>2026-10-17</IntrBkSttlmDt>
            <InstgAgt><FinInstnId><ClrSysMmbId><MmbId>000013</MmbId></ClrSysMmbId></FinInstnId></InstgAgt>
            <InstdAgt><FinInstnId><ClrSysMmbId><MmbId>999057</MmbId></ClrSysMmbId></FinInstnId></InstdAgt>
            <Dbtr><Nm>Sender</Nm></Dbtr><DbtrAcct><Id><IBAN>0123456789</IBAN></Id></DbtrAcct>
            <Cdtr><Nm>Receiver</Nm></Cdtr><CdtrAcct><Id><IBAN>9876543210</IBAN></Id></CdtrAcct>
            <RmtInf><Ustrd>Test &amp; payment</Ustrd></RmtInf></CdtTrfTxInf></FIToFICstmrCdtTrf></ns2:Document>
            """.formatted(SEED_ID);
    static final String ENQUIRY = """
            <ns2:Document xmlns:ns2="urn:iso:std:iso:20022:tech:xsd:acmt.023.001.04"><IdVrfctnReq><Assgnmt>
            <MsgId>%s</MsgId><CreDtTm>2026-10-17T10:00:00Z</CreDtTm><Assgnr><Pty><Nm>Sending Bank</Nm></Pty>
            <Agt><FinInstnId><ClrSysMmbId><MmbId>000013</MmbId></ClrSysMmbId></FinInstnId></Agt></Assgnr>
            <Assgne><Agt><FinInstnId><ClrSysMmbId><MmbId>999057</MmbId></ClrSysMmbId></FinInstnId></Agt></Assgne></Assgnmt>
            <Vrfctn><Id>%s</Id><PtyAndAcctId><Pty><Nm>Receiver</Nm></Pty><Acct><Id><IBAN>9876543210</IBAN></Id></Acct></PtyAndAcctId></Vrfctn>
            </IdVrfctnReq></ns2:Document>
            """
            .formatted(SEED_ID, SEED_ID);
    static final String STATUS = """
            <Document><FIToFIPmtStsRpt><GrpHdr><MsgId>%s</MsgId>
            <InstgAgt><FinInstnId><ClrSysMmbId><MmbId>000013</MmbId></ClrSysMmbId></FinInstnId></InstgAgt>
            <InstdAgt><FinInstnId><ClrSysMmbId><MmbId>999057</MmbId></ClrSysMmbId></FinInstnId></InstdAgt></GrpHdr>
            <OrgnlGrpInfAndSts><OrgnlMsgId>original-id</OrgnlMsgId><GrpSts>RJCT</GrpSts></OrgnlGrpInfAndSts>
            <TxInfAndSts><StsId>NOAN</StsId><StsRsnInf><Rsn><Prtry>AC01</Prtry></Rsn></StsRsnInf></TxInfAndSts>
            </FIToFIPmtStsRpt></Document>
            """.formatted(SEED_ID);
    @jakarta.inject.Inject
    com.provider.nps.crypto.NpsIdGenerator ids;
    @jakarta.inject.Inject
    com.provider.nps.crypto.NpsXmlSigner signer;
    @jakarta.inject.Inject
    com.provider.nps.crypto.NpsXmlEncryptor crypto;
    private String messageId;
    private void nextMessage() {
        messageId = "000013" + ids.generateMessageId().substring(6);
    }
    @BeforeEach
    void reset() {
        InternalServiceTestResource.reset();
        nextMessage();
    }
    private String post(String path, String xml, int status) {
        try {
            String request = crypto.encrypt(signer.sign(xml.replace(SEED_ID, messageId)));
            String response = given().contentType("application/xml").body(request).post("/nps/callback/" + path).then()
                    .statusCode(status).extract().asString();
            if (status == 200 && !response.isBlank()) {
                response = crypto.decrypt(response);
                assertTrue(signer.verify(response));
            }
            return response;
        } catch (Exception e) {
            throw new AssertionError("Secure callback exchange failed", e);
        }
    }
    @Test
    void approvedCreditIsForwardedWithExactAmountAndCorrelatedReply() {
        var reply = NpsXml.parse(post("pacs008", CREDIT, 200));
        String base = "Document/FIToFIPmtStsRpt/";
        assertEquals("ACSC", NpsXml.text(reply, base + "OrgnlGrpInfAndSts/GrpSts"));
        assertEquals("AUTH", NpsXml.text(reply, base + "TxInfAndSts/StsId"));
        assertEquals(messageId, NpsXml.text(reply, base + "OrgnlGrpInfAndSts/OrgnlMsgId"));
        assertEquals("instruction-id", NpsXml.text(reply, base + "TxInfAndSts/OrgnlInstrId"));
        assertEquals("100.50", InternalServiceTestResource.credit.getAmount());
        assertEquals("9876543210", InternalServiceTestResource.credit.getCreditor().getAccountNumber());
        assertEquals("Test & payment", InternalServiceTestResource.credit.getNarration());
        String key = InternalServiceTestResource.credit.getIdempotencyKey();
        post("pacs008", CREDIT.replace("><", ">\n<"), 200);
        assertEquals(key, InternalServiceTestResource.credit.getIdempotencyKey());
        assertEquals(1, InternalServiceTestResource.creditCalls.get());
    }
    @Test
    void rejectedCreditUsesInternalReasonAndNeverReturnsAuth() {
        InternalServiceTestResource.decision = CreditDecision.CREDIT_REJECTED;
        var reply = NpsXml.parse(post("pacs008", CREDIT, 200));
        assertEquals("NAUT", NpsXml.text(reply, "Document/FIToFIPmtStsRpt/TxInfAndSts/StsId"));
        assertEquals("AC01", NpsXml.text(reply, "Document/FIToFIPmtStsRpt/TxInfAndSts/StsRsnInf/Rsn/Prtry"));
        assertEquals("Account <closed>", NpsXml.text(reply, "Document/FIToFIPmtStsRpt/TxInfAndSts/StsRsnInf/AddtlInf"));
    }
    @Test
    void unresolvedUnavailableAndMismatchedResultsStayTransportFailures() {
        InternalServiceTestResource.decision = CreditDecision.CREDIT_PENDING;
        assertTrue(post("pacs008", CREDIT, 503).isBlank());
        InternalServiceTestResource.reset();
        InternalServiceTestResource.unavailable = true;
        post("pacs008", CREDIT, 503);
        InternalServiceTestResource.reset();
        InternalServiceTestResource.mismatch = true;
        post("pacs008", CREDIT, 503);
    }
    @Test
    void deadlineDoesNotTurnIntoFinancialRejection() {
        InternalServiceTestResource.hang = true;
        assertTrue(post("pacs008", CREDIT, 503).isBlank());
    }
    @Test
    void nameEnquiryReturnsRealVerifiedIdentityAndEscapesXml() {
        var reply = NpsXml.parse(post("acmt023", ENQUIRY, 200));
        String base = "Document/IdVrfctnRpt/";
        assertEquals("true", NpsXml.text(reply, base + "Rpt/Vrfctn"));
        assertEquals("Receiver & Co", NpsXml.text(reply, base + "Rpt/UpdtdPtyAndAcctId/Pty/Nm"));
        assertEquals("22123456789", NpsXml.text(reply, base + "SplmtryData/Envlp/CustomData/CreditorInfo/IdValue"));
        assertEquals(messageId, NpsXml.text(reply, base + "OrgnlAssgnmt/MsgId"));
        assertEquals("Receiver", InternalServiceTestResource.verification.getExpectedAccountName());
    }
    @Test
    void nameRejectionAndWrongInternalCorrelationAreNotFabricatedSuccess() {
        InternalServiceTestResource.verified = false;
        var reply = NpsXml.parse(post("acmt023", ENQUIRY, 200));
        assertEquals("false", NpsXml.text(reply, "Document/IdVrfctnRpt/Rpt/Vrfctn"));
        assertNull(NpsXml.text(reply, "Document/IdVrfctnRpt/SplmtryData/Envlp/CustomData/CreditorInfo/IdValue"));
        nextMessage();
        InternalServiceTestResource.mismatch = true;
        post("acmt023", ENQUIRY, 503);
    }
    @Test
    void statusAcknowledgementRequiresDurableInternalReceipt() {
        post("pacs002", STATUS, 200);
        assertEquals("NOAN", InternalServiceTestResource.notification.getStatusId());
        assertEquals("RJCT", InternalServiceTestResource.notification.getGroupStatus());
        assertEquals("", InternalServiceTestResource.notification.getTransactionStatus());
        nextMessage();
        InternalServiceTestResource.accepted = false;
        post("pacs002", STATUS, 503);
    }
    @Test
    void wrongReceivingInstitutionIsRejectedBeforeCallingInternalService() {
        post("pacs008", CREDIT.replace("999057", "999058"), 400);
        assertNull(InternalServiceTestResource.credit);
        post("acmt023", ENQUIRY.replace("999057", "999058"), 400);
        assertNull(InternalServiceTestResource.verification);
    }
    @Test
    void duplicateReplyIsStableAndChangedAmountIsRejectedWithoutAnotherRpc() {
        String first = post("pacs008", CREDIT, 200);
        String second = post("pacs008", CREDIT, 200);
        assertEquals(first, second);
        post("pacs008", CREDIT.replace("100.50", "200.50"), 409);
        assertEquals(1, InternalServiceTestResource.creditCalls.get());
    }
    @Test
    void retryAfterAnUncertainDecisionKeepsTheInternalKeyAndAllowsRecovery() {
        InternalServiceTestResource.unavailable = true;
        post("pacs008", CREDIT, 503);
        String key = InternalServiceTestResource.credit.getIdempotencyKey();
        InternalServiceTestResource.unavailable = false;
        post("pacs008", CREDIT, 200);
        assertEquals(key, InternalServiceTestResource.credit.getIdempotencyKey());
        assertEquals(2, InternalServiceTestResource.creditCalls.get());
    }
    @Test
    void duplicateStatusIsAcknowledgedWithoutAnotherInternalDelivery() {
        post("pacs002", STATUS, 200);
        post("pacs002", STATUS, 200);
        assertEquals(1, InternalServiceTestResource.statusCalls.get());
        post("pacs002", STATUS.replace("<StsId>NOAN</StsId>", "<StsId>NAUT</StsId>"), 409);
        assertEquals(1, InternalServiceTestResource.statusCalls.get());
    }
    @Test
    void unsignedAndTamperedMessagesNeverReachTheInternalService() throws Exception {
        given().contentType("application/xml").body(CREDIT.replace(SEED_ID, messageId)).post("/nps/callback/pacs008")
                .then().statusCode(400);
        String tampered = signer.sign(CREDIT.replace(SEED_ID, messageId)).replace("100.50", "200.50");
        given().contentType("application/xml").body(crypto.encrypt(tampered)).post("/nps/callback/pacs008").then()
                .statusCode(400);
        assertEquals(0, InternalServiceTestResource.creditCalls.get());
    }
    @Test
    void malformedBusinessDataIsRejectedBeforeAnyInternalSideEffect() {
        post("pacs008", CREDIT.replace("100.50", "1E2"), 400);
        post("pacs008", CREDIT.replace("2026-10-17</IntrBkSttlmDt>", "2026-02-31</IntrBkSttlmDt>"), 400);
        post("pacs008", CREDIT.replace("Ccy=\"NGN\"", "Ccy=\"USD\""), 400);
        assertEquals(0, InternalServiceTestResource.creditCalls.get());
    }
}
