package com.provider.nps.message;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builder for pain.001.001.09 - CustomerCreditTransferInitiation.
 * Used for customer-initiated payment instructions.
 * 
 * This is an alternative to pacs.008 for customer-to-FI initiated transfers.
 */
@ApplicationScoped
public class Pain001Builder {

    private static final String NIBSS_BIC = "ABORNGLA";

    @Inject
    NpsMessageBuilder baseBuilder;

    /**
     * Builds a pain.001 payment initiation message.
     */
    public String build(PaymentInitiationRequest request) {
        String msgId = baseBuilder.getIdGenerator().generateMessageId();
        String pmtInfId = baseBuilder.getIdGenerator().generateMessageId();
        String endToEndId = request.getEndToEndId() != null ? 
                request.getEndToEndId() : baseBuilder.getIdGenerator().generateEndToEndId();
        String creDate = baseBuilder.getIdGenerator().currentTimestamp();
        String reqExecDate = baseBuilder.getIdGenerator().currentDate();

        String appHdr = baseBuilder.buildAppHdr("pain.001.001.09", msgId, NIBSS_BIC);

        String document = buildDocument(msgId, pmtInfId, endToEndId, creDate, reqExecDate, request);

        return baseBuilder.wrapInBizMsg(appHdr, document);
    }

    private String buildDocument(String msgId, String pmtInfId, String endToEndId,
            String creDate, String reqExecDate, PaymentInitiationRequest request) {

        String formattedAmount = formatAmount(request.getAmount());

        return String.format("""
                <Document xmlns="%s">
                    <CstmrCdtTrfInitn>
                        <GrpHdr>
                            <MsgId>%s</MsgId>
                            <CreDtTm>%s</CreDtTm>
                            <NbOfTxs>1</NbOfTxs>
                            <CtrlSum>%s</CtrlSum>
                            <InitgPty>
                                <Nm>%s</Nm>
                                <Id>
                                    <OrgId>
                                        <Othr>
                                            <Id>%s</Id>
                                        </Othr>
                                    </OrgId>
                                </Id>
                            </InitgPty>
                        </GrpHdr>
                        <PmtInf>
                            <PmtInfId>%s</PmtInfId>
                            <PmtMtd>TRF</PmtMtd>
                            <NbOfTxs>1</NbOfTxs>
                            <CtrlSum>%s</CtrlSum>
                            <PmtTpInf>
                                <SvcLvl>
                                    <Prtry>NURG</Prtry>
                                </SvcLvl>
                                <LclInstrm>
                                    <Prtry>NIP</Prtry>
                                </LclInstrm>
                            </PmtTpInf>
                            <ReqdExctnDt>
                                <Dt>%s</Dt>
                            </ReqdExctnDt>
                            <Dbtr>
                                <Nm>%s</Nm>
                                <Id>
                                    <PrvtId>
                                        <Othr>
                                            <Id>%s</Id>
                                            <SchmeNm>
                                                <Prtry>BVN</Prtry>
                                            </SchmeNm>
                                        </Othr>
                                    </PrvtId>
                                </Id>
                            </Dbtr>
                            <DbtrAcct>
                                <Id>
                                    <Othr>
                                        <Id>%s</Id>
                                        <SchmeNm>
                                            <Prtry>ACCT</Prtry>
                                        </SchmeNm>
                                    </Othr>
                                </Id>
                            </DbtrAcct>
                            <DbtrAgt>
                                <FinInstnId>
                                    <BICFI>%s</BICFI>
                                </FinInstnId>
                            </DbtrAgt>
                            <ChrgBr>SLEV</ChrgBr>
                            <CdtTrfTxInf>
                                <PmtId>
                                    <EndToEndId>%s</EndToEndId>
                                </PmtId>
                                <Amt>
                                    <InstdAmt Ccy="NGN">%s</InstdAmt>
                                </Amt>
                                <CdtrAgt>
                                    <FinInstnId>
                                        <ClrSysMmbId>
                                            <ClrSysId>
                                                <Cd>NGNC</Cd>
                                            </ClrSysId>
                                            <MmbId>%s</MmbId>
                                        </ClrSysMmbId>
                                    </FinInstnId>
                                </CdtrAgt>
                                <Cdtr>
                                    <Nm>%s</Nm>
                                </Cdtr>
                                <CdtrAcct>
                                    <Id>
                                        <Othr>
                                            <Id>%s</Id>
                                            <SchmeNm>
                                                <Prtry>ACCT</Prtry>
                                            </SchmeNm>
                                        </Othr>
                                    </Id>
                                </CdtrAcct>
                                <Purp>
                                    <Prtry>%s</Prtry>
                                </Purp>
                                <RmtInf>
                                    <Ustrd>%s</Ustrd>
                                </RmtInf>
                            </CdtTrfTxInf>
                        </PmtInf>
                        <SplmtryData>
                            <Envlp>
                                <ChnlCd>%s</ChnlCd>
                                <KycLvl>%s</KycLvl>
                            </Envlp>
                        </SplmtryData>
                    </CstmrCdtTrfInitn>
                </Document>""",
                NpsMessageBuilder.PAIN_001_NS,
                msgId,
                creDate,
                formattedAmount,
                baseBuilder.getSourceName(),
                baseBuilder.getSourceId(),
                pmtInfId,
                formattedAmount,
                reqExecDate,
                request.getDebtorName(),
                request.getDebtorBvn() != null ? request.getDebtorBvn() : "",
                request.getDebtorAccount(),
                baseBuilder.getSourceBic(),
                endToEndId,
                formattedAmount,
                request.getCreditorBankCode(),
                request.getCreditorName(),
                request.getCreditorAccount(),
                request.getPurposeCode() != null ? request.getPurposeCode() : "OTHR",
                escapeXml(request.getNarration() != null ? request.getNarration() : "Fund Transfer"),
                request.getChannelCode() != null ? request.getChannelCode() : "1",
                request.getKycLevel() != null ? request.getKycLevel() : "3");
    }

    private String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Request data class for payment initiation.
     */
    public static class PaymentInitiationRequest {
        private String endToEndId;
        private BigDecimal amount;
        private String debtorName;
        private String debtorAccount;
        private String debtorBvn;
        private String creditorName;
        private String creditorAccount;
        private String creditorBankCode;
        private String narration;
        private String channelCode;
        private String kycLevel;
        private String purposeCode;

        // Getters and Setters
        public String getEndToEndId() { return endToEndId; }
        public void setEndToEndId(String endToEndId) { this.endToEndId = endToEndId; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public String getDebtorName() { return debtorName; }
        public void setDebtorName(String debtorName) { this.debtorName = debtorName; }

        public String getDebtorAccount() { return debtorAccount; }
        public void setDebtorAccount(String debtorAccount) { this.debtorAccount = debtorAccount; }

        public String getDebtorBvn() { return debtorBvn; }
        public void setDebtorBvn(String debtorBvn) { this.debtorBvn = debtorBvn; }

        public String getCreditorName() { return creditorName; }
        public void setCreditorName(String creditorName) { this.creditorName = creditorName; }

        public String getCreditorAccount() { return creditorAccount; }
        public void setCreditorAccount(String creditorAccount) { this.creditorAccount = creditorAccount; }

        public String getCreditorBankCode() { return creditorBankCode; }
        public void setCreditorBankCode(String creditorBankCode) { this.creditorBankCode = creditorBankCode; }

        public String getNarration() { return narration; }
        public void setNarration(String narration) { this.narration = narration; }

        public String getChannelCode() { return channelCode; }
        public void setChannelCode(String channelCode) { this.channelCode = channelCode; }

        public String getKycLevel() { return kycLevel; }
        public void setKycLevel(String kycLevel) { this.kycLevel = kycLevel; }

        public String getPurposeCode() { return purposeCode; }
        public void setPurposeCode(String purposeCode) { this.purposeCode = purposeCode; }
    }
}
