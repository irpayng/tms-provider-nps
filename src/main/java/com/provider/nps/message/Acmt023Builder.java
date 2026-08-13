package com.provider.nps.message;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builder for acmt.023.001.03 - IdentificationVerificationRequest.
 * Used for Name Enquiry (account validation) before fund transfer.
 * 
 * Per NPS spec, this validates:
 * - Account number exists
 * - Account name matches
 * - Account can receive funds
 */
@ApplicationScoped
public class Acmt023Builder {

    private static final String NIBSS_BIC = "ABORNGLA"; // NIBSS BIC for routing

    @Inject
    NpsMessageBuilder baseBuilder;

    /**
     * Builds an acmt.023 name enquiry request.
     * 
     * @param accountNumber The account number to verify
     * @param bankCode The destination bank code (NIP bank code)
     * @param channelCode The channel code (1=NIP, 2=others)
     * @return Complete ISO 20022 XML message
     */
    public String build(String accountNumber, String bankCode, String channelCode) {
        String msgId = baseBuilder.getIdGenerator().generateMessageId();
        String creDate = baseBuilder.getIdGenerator().currentTimestamp();

        String appHdr = baseBuilder.buildAppHdr("acmt.023.001.03", msgId, NIBSS_BIC);

        String document = buildDocument(msgId, creDate, accountNumber, bankCode, channelCode);

        return baseBuilder.wrapInBizMsg(appHdr, document);
    }

    private String buildDocument(String msgId, String creDate, String accountNumber, 
            String bankCode, String channelCode) {
        
        return String.format("""
                <Document xmlns="%s">
                    <IdVrfctnReq>
                        <Assgnmt>
                            <MsgId>%s</MsgId>
                            <CreDtTm>%s</CreDtTm>
                            <Assgnr>
                                <Agt>
                                    <FinInstnId>
                                        <BICFI>%s</BICFI>
                                        <Nm>%s</Nm>
                                    </FinInstnId>
                                </Agt>
                            </Assgnr>
                            <Assgne>
                                <Agt>
                                    <FinInstnId>
                                        <BICFI>%s</BICFI>
                                    </FinInstnId>
                                </Agt>
                            </Assgne>
                        </Assgnmt>
                        <Vrfctn>
                            <Id>%s</Id>
                            <PtyAndAcctId>
                                <Acct>
                                    <Id>
                                        <Othr>
                                            <Id>%s</Id>
                                            <SchmeNm>
                                                <Prtry>ACCT</Prtry>
                                            </SchmeNm>
                                        </Othr>
                                    </Id>
                                </Acct>
                                <Agt>
                                    <FinInstnId>
                                        <ClrSysMmbId>
                                            <ClrSysId>
                                                <Cd>NGNC</Cd>
                                            </ClrSysId>
                                            <MmbId>%s</MmbId>
                                        </ClrSysMmbId>
                                    </FinInstnId>
                                </Agt>
                            </PtyAndAcctId>
                        </Vrfctn>
                        <SplmtryData>
                            <Envlp>
                                <ChnlCd>%s</ChnlCd>
                            </Envlp>
                        </SplmtryData>
                    </IdVrfctnReq>
                </Document>""",
                NpsMessageBuilder.ACMT_023_NS,
                msgId,
                creDate,
                baseBuilder.getSourceBic(),
                baseBuilder.getSourceName(),
                NIBSS_BIC,
                msgId, // Verification ID same as MsgId
                accountNumber,
                bankCode,
                channelCode);
    }

    /**
     * Builds a name enquiry with full details.
     */
    public String buildWithDetails(NameEnquiryRequest request) {
        return build(request.getAccountNumber(), request.getBankCode(), 
                request.getChannelCode() != null ? request.getChannelCode() : "1");
    }

    /**
     * Request data class for name enquiry.
     */
    public static class NameEnquiryRequest {
        private String accountNumber;
        private String bankCode;
        private String channelCode;

        public String getAccountNumber() {
            return accountNumber;
        }

        public void setAccountNumber(String accountNumber) {
            this.accountNumber = accountNumber;
        }

        public String getBankCode() {
            return bankCode;
        }

        public void setBankCode(String bankCode) {
            this.bankCode = bankCode;
        }

        public String getChannelCode() {
            return channelCode;
        }

        public void setChannelCode(String channelCode) {
            this.channelCode = channelCode;
        }
    }
}
