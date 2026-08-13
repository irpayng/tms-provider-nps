package com.provider.nps.message;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builder for pacs.008.001.08 - FIToFICustomerCreditTransfer.
 * Used for fund transfers between financial institutions.
 * 
 * This is the primary message for NIP-like bank transfers via NPS.
 */
@ApplicationScoped
public class Pacs008Builder {

    private static final String NIBSS_BIC = "ABORNGLA";

    @Inject
    NpsMessageBuilder baseBuilder;

    /**
     * Builds a pacs.008 credit transfer message.
     */
    public String build(CreditTransferRequest request) {
        String msgId = baseBuilder.getIdGenerator().generateMessageId();
        String endToEndId = request.getEndToEndId() != null ? 
                request.getEndToEndId() : baseBuilder.getIdGenerator().generateEndToEndId();
        String txId = baseBuilder.getIdGenerator().generateTransactionId();
        String creDate = baseBuilder.getIdGenerator().currentTimestamp();
        String settlementDate = baseBuilder.getIdGenerator().currentDate();

        String appHdr = baseBuilder.buildAppHdr("pacs.008.001.08", msgId, NIBSS_BIC);

        String document = buildDocument(msgId, endToEndId, txId, creDate, settlementDate, request);

        return baseBuilder.wrapInBizMsg(appHdr, document);
    }

    private String buildDocument(String msgId, String endToEndId, String txId, 
            String creDate, String settlementDate, CreditTransferRequest request) {

        String formattedAmount = formatAmount(request.getAmount());

        return String.format("""
                <Document xmlns="%s">
                    <FIToFICstmrCdtTrf>
                        <GrpHdr>
                            <MsgId>%s</MsgId>
                            <CreDtTm>%s</CreDtTm>
                            <NbOfTxs>1</NbOfTxs>
                            <SttlmInf>
                                <SttlmMtd>CLRG</SttlmMtd>
                                <ClrSys>
                                    <Prtry>NPS</Prtry>
                                </ClrSys>
                            </SttlmInf>
                        </GrpHdr>
                        <CdtTrfTxInf>
                            <PmtId>
                                <EndToEndId>%s</EndToEndId>
                                <TxId>%s</TxId>
                            </PmtId>
                            <PmtTpInf>
                                <SvcLvl>
                                    <Prtry>NURG</Prtry>
                                </SvcLvl>
                                <LclInstrm>
                                    <Prtry>NIP</Prtry>
                                </LclInstrm>
                                <CtgyPurp>
                                    <Prtry>%s</Prtry>
                                </CtgyPurp>
                            </PmtTpInf>
                            <IntrBkSttlmAmt Ccy="NGN">%s</IntrBkSttlmAmt>
                            <IntrBkSttlmDt>%s</IntrBkSttlmDt>
                            <ChrgBr>SLEV</ChrgBr>
                            <InstgAgt>
                                <FinInstnId>
                                    <BICFI>%s</BICFI>
                                </FinInstnId>
                            </InstgAgt>
                            <InstdAgt>
                                <FinInstnId>
                                    <ClrSysMmbId>
                                        <ClrSysId>
                                            <Cd>NGNC</Cd>
                                        </ClrSysId>
                                        <MmbId>%s</MmbId>
                                    </ClrSysMmbId>
                                </FinInstnId>
                            </InstdAgt>
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
                            <SplmtryData>
                                <Envlp>
                                    <ChnlCd>%s</ChnlCd>
                                    <KycLvl>%s</KycLvl>
                                    <TxnLoc>%s</TxnLoc>
                                </Envlp>
                            </SplmtryData>
                        </CdtTrfTxInf>
                    </FIToFICstmrCdtTrf>
                </Document>""",
                NpsMessageBuilder.PACS_008_NS,
                msgId,
                creDate,
                endToEndId,
                txId,
                request.getCategoryPurpose() != null ? request.getCategoryPurpose() : "91", // 91 = Transfer
                formattedAmount,
                settlementDate,
                baseBuilder.getSourceBic(),
                request.getCreditorBankCode(),
                request.getDebtorName(),
                request.getDebtorBvn() != null ? request.getDebtorBvn() : "",
                request.getDebtorAccount(),
                baseBuilder.getSourceBic(),
                request.getCreditorBankCode(),
                request.getCreditorName(),
                request.getCreditorAccount(),
                request.getPurposeCode() != null ? request.getPurposeCode() : "OTHR",
                request.getNarration() != null ? escapeXml(request.getNarration()) : "Fund Transfer",
                request.getChannelCode() != null ? request.getChannelCode() : "1",
                request.getKycLevel() != null ? request.getKycLevel() : "3",
                request.getTransactionLocation() != null ? request.getTransactionLocation() : "6.4300747,3.4110715");
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
     * Request data class for credit transfer.
     */
    public static class CreditTransferRequest {
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
        private String transactionLocation;
        private String categoryPurpose;
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

        public String getTransactionLocation() { return transactionLocation; }
        public void setTransactionLocation(String transactionLocation) { this.transactionLocation = transactionLocation; }

        public String getCategoryPurpose() { return categoryPurpose; }
        public void setCategoryPurpose(String categoryPurpose) { this.categoryPurpose = categoryPurpose; }

        public String getPurposeCode() { return purposeCode; }
        public void setPurposeCode(String purposeCode) { this.purposeCode = purposeCode; }
    }
}
