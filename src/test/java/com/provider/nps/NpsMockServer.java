package com.provider.nps;

import java.util.HashMap;
import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * WireMock server that simulates NIBSS NPS endpoints for testing.
 */
public class NpsMockServer implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wireMockServer;

    @Override
    public Map<String, String> start() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();

        String baseUrl = "http://localhost:" + wireMockServer.port();

        // Setup OAuth token endpoint
        setupTokenEndpoint();

        // Setup NPS endpoints
        setupNameEnquiryEndpoint();
        setupCreditTransferEndpoint();
        setupPaymentStatusEndpoint();

        Map<String, String> config = new HashMap<>();
        config.put("nps.base-url", baseUrl);
        config.put("nps.token-url", baseUrl + "/token");
        config.put("nps.client-id", "test-client");
        config.put("nps.client-secret", "test-secret");
        config.put("nps.source-id", "000000000000");
        config.put("nps.source-bic", "TESTNGLA");
        config.put("nps.source-name", "TEST BANK LIMITED");

        return config;
    }

    private void setupTokenEndpoint() {
        // OAuth2 token response
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/token"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "access_token": "test-token-12345",
                                    "token_type": "Bearer",
                                    "expires_in": 3600
                                }
                                """)));
    }

    private void setupNameEnquiryEndpoint() {
        // Successful name enquiry response (acmt.024)
        String successResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <AppHdr xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                        <BizMsgIdr>RESP12345678901234567890123456789</BizMsgIdr>
                        <MsgDefIdr>acmt.024.001.03</MsgDefIdr>
                        <CreDt>2026-08-05T12:00:00+01:00</CreDt>
                    </AppHdr>
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:acmt.024.001.03">
                        <IdVrfctnRpt>
                            <Assgnmt>
                                <MsgId>RESP12345678901234567890123456789</MsgId>
                                <CreDtTm>2026-08-05T12:00:00+01:00</CreDtTm>
                            </Assgnmt>
                            <Rpt>
                                <OrgnlId>REQ123</OrgnlId>
                                <Vrfctn>true</Vrfctn>
                                <UpdtdPtyAndAcctId>
                                    <Pty>
                                        <Nm>JOHN DOE SMITH</Nm>
                                        <Id>
                                            <PrvtId>
                                                <Othr>
                                                    <Id>22123456789</Id>
                                                    <SchmeNm>
                                                        <Prtry>BVN</Prtry>
                                                    </SchmeNm>
                                                </Othr>
                                            </PrvtId>
                                        </Id>
                                    </Pty>
                                    <Acct>
                                        <Id>
                                            <Othr>
                                                <Id>0123456789</Id>
                                                <SchmeNm>
                                                    <Prtry>ACCT</Prtry>
                                                </SchmeNm>
                                            </Othr>
                                        </Id>
                                    </Acct>
                                    <Agt>
                                        <FinInstnId>
                                            <ClrSysMmbId>
                                                <MmbId>000013</MmbId>
                                            </ClrSysMmbId>
                                        </FinInstnId>
                                    </Agt>
                                </UpdtdPtyAndAcctId>
                            </Rpt>
                            <SplmtryData>
                                <Envlp>
                                    <SessId>SESSION123456</SessId>
                                    <KycLvl>3</KycLvl>
                                </Envlp>
                            </SplmtryData>
                        </IdVrfctnRpt>
                    </Document>
                </BizMsg>
                """;

        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/nps/acmt/023"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(successResponse)));
    }

    private void setupCreditTransferEndpoint() {
        // Successful credit transfer response (pacs.002)
        String successResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <AppHdr xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                        <BizMsgIdr>RESP98765432109876543210987654321</BizMsgIdr>
                        <MsgDefIdr>pacs.002.001.10</MsgDefIdr>
                        <CreDt>2026-08-05T12:00:00+01:00</CreDt>
                    </AppHdr>
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
                        <FIToFIPmtStsRpt>
                            <GrpHdr>
                                <MsgId>RESP98765432109876543210987654321</MsgId>
                                <CreDtTm>2026-08-05T12:00:00+01:00</CreDtTm>
                            </GrpHdr>
                            <OrgnlGrpInfAndSts>
                                <OrgnlMsgId>ORIG123</OrgnlMsgId>
                                <OrgnlMsgNmId>pacs.008.001.08</OrgnlMsgNmId>
                            </OrgnlGrpInfAndSts>
                            <TxInfAndSts>
                                <OrgnlEndToEndId>E2E123456789</OrgnlEndToEndId>
                                <OrgnlTxId>TX123456789</OrgnlTxId>
                                <TxSts>ACTC</TxSts>
                                <StsRsnInf>
                                    <AddtlInf>Transaction successful</AddtlInf>
                                </StsRsnInf>
                            </TxInfAndSts>
                            <SplmtryData>
                                <Envlp>
                                    <SessId>SESSION789012</SessId>
                                </Envlp>
                            </SplmtryData>
                        </FIToFIPmtStsRpt>
                    </Document>
                </BizMsg>
                """;

        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/nps/pacs/008"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(successResponse)));
    }

    private void setupPaymentStatusEndpoint() {
        // Payment status query response (pacs.002)
        String successResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                    <AppHdr xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                        <BizMsgIdr>REQRY1234567890123456789012345678</BizMsgIdr>
                        <MsgDefIdr>pacs.002.001.10</MsgDefIdr>
                        <CreDt>2026-08-05T12:00:00+01:00</CreDt>
                    </AppHdr>
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
                        <FIToFIPmtStsRpt>
                            <GrpHdr>
                                <MsgId>REQRY1234567890123456789012345678</MsgId>
                                <CreDtTm>2026-08-05T12:00:00+01:00</CreDtTm>
                            </GrpHdr>
                            <OrgnlGrpInfAndSts>
                                <OrgnlMsgId>ORIGINAL_MSG_123</OrgnlMsgId>
                                <OrgnlMsgNmId>pacs.008.001.08</OrgnlMsgNmId>
                            </OrgnlGrpInfAndSts>
                            <TxInfAndSts>
                                <OrgnlEndToEndId>ORIG_E2E_123</OrgnlEndToEndId>
                                <TxSts>ACSP</TxSts>
                                <StsRsnInf>
                                    <AddtlInf>Settlement completed</AddtlInf>
                                </StsRsnInf>
                            </TxInfAndSts>
                        </FIToFIPmtStsRpt>
                    </Document>
                </BizMsg>
                """;

        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/nps/pacs/028"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(successResponse)));
    }

    @Override
    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    public WireMockServer getServer() {
        return wireMockServer;
    }
}
