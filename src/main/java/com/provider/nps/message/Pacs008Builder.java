package com.provider.nps.message;

import static com.provider.nps.message.NpsFields.agent;
import static com.provider.nps.message.NpsFields.member;
import static com.provider.nps.message.NpsFields.required;
import static com.provider.nps.message.NpsFields.xml;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** NIBSS pacs.008.001.12 credit transfer. */
@ApplicationScoped
public class Pacs008Builder {
    @Inject
    NpsMessageBuilder baseBuilder;

    public String build(CreditTransferRequest request) {
        String sourceMemberId = member(baseBuilder.getSourceId(), "NPS_SOURCE_ID");
        member(request.getCreditorBankCode(), "bank_code");
        account(request.getDebtorAccount(), "debtor_account");
        account(request.getCreditorAccount(), "account_number");
        required(request.getDebtorName(), "debtor_name", 100);
        required(request.getCreditorName(), "account_name", 100);
        String amount = formatAmount(request.getAmount());
        Map<String, String> metadata = request.getVerificationDetails();
        String debtorVerification = buildPartyVerification(metadata, "debtor");
        String creditorVerification = buildPartyVerification(metadata, "creditor");
        String nameEnquiryMessageId = required(metadata.get("name_enquiry_msg_id"), "name_enquiry_msg_id", 35);
        String channelCode = required(metadata.get("channel_code"), "channel_code", 1);
        if (!channelCode.matches("[0-9]"))
            throw new IllegalArgumentException("Invalid channel_code");
        String transactionLocation = required(metadata.get("transaction_location"), "transaction_location", 25);
        String narration = request.getNarration() == null ? "Fund Transfer" : request.getNarration();
        if (narration.length() > 100)
            throw new IllegalArgumentException("narration exceeds 100 characters");
        String messageId = baseBuilder.getIdGenerator().generateMessageId();
        String instructionId = baseBuilder.getIdGenerator().generateTransactionId();
        String endToEndId = request.getEndToEndId() == null
                ? baseBuilder.getIdGenerator().generateEndToEndId()
                : required(request.getEndToEndId(), "end_to_end_id", 35);
        String transactionId = baseBuilder.getIdGenerator().generateTransactionId();
        String creationTime = baseBuilder.getIdGenerator().currentTimestamp();
        String settlementDate = baseBuilder.getIdGenerator().currentDate();
        String agents = "<InstgAgt>" + agent(sourceMemberId) + "</InstgAgt><InstdAgt>"
                + agent(request.getCreditorBankCode()) + "</InstdAgt>";
        String categoryPurpose = request.getCategoryPurpose() == null
                ? "001"
                : required(request.getCategoryPurpose(), "category_purpose", 3);
        String document = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ns2:Document xmlns:ns2="%s">
                    <FIToFICstmrCdtTrf>
                        <GrpHdr>
                            <MsgId>%s</MsgId>
                            <CreDtTm>%s</CreDtTm>
                            <BtchBookg>false</BtchBookg>
                            <NbOfTxs>1</NbOfTxs>
                            <SttlmInf><SttlmMtd>CLRG</SttlmMtd></SttlmInf>
                            %s
                        </GrpHdr>
                        <CdtTrfTxInf>
                            <PmtId>
                                <InstrId>%s</InstrId>
                                <EndToEndId>%s</EndToEndId>
                                <TxId>%s</TxId>
                            </PmtId>
                            <PmtTpInf>
                                <ClrChanl>RTNS</ClrChanl>
                                <SvcLvl><Prtry>0100</Prtry></SvcLvl>
                                <LclInstrm><Prtry>CTAA</Prtry></LclInstrm>
                                <CtgyPurp><Prtry>%s</Prtry></CtgyPurp>
                            </PmtTpInf>
                            <IntrBkSttlmAmt Ccy="NGN">%s</IntrBkSttlmAmt>
                            <IntrBkSttlmDt>%s</IntrBkSttlmDt>
                            <ChrgBr>SLEV</ChrgBr>
                            %s
                            <Dbtr><Nm>%s</Nm></Dbtr>
                            %s
                            <DbtrAgt>%s</DbtrAgt>
                            <CdtrAgt>%s</CdtrAgt>
                            <Cdtr><Nm>%s</Nm></Cdtr>
                            %s
                            <RmtInf><Ustrd>%s</Ustrd></RmtInf>
                        </CdtTrfTxInf>
                        <SplmtryData>
                            <PlcAndNm>AdditionalVerificationDetails</PlcAndNm>
                            <Envlp>
                                <CustomData>
                                    <DebtorInfo>%s</DebtorInfo>
                                    <CreditorInfo>%s</CreditorInfo>
                                    <TransactionInfo>
                                        <TransactionLocation>%s</TransactionLocation>
                                        <NameEnquiryMsgId>%s</NameEnquiryMsgId>
                                        <ChannelCode>%s</ChannelCode>
                                    </TransactionInfo>
                                </CustomData>
                            </Envlp>
                        </SplmtryData>
                    </FIToFICstmrCdtTrf>
                </ns2:Document>
                """.formatted(NpsMessageBuilder.PACS_008_NS, xml(messageId), xml(creationTime), agents,
                xml(instructionId), xml(endToEndId), xml(transactionId), xml(categoryPurpose), amount,
                xml(settlementDate), agents, xml(request.getDebtorName()),
                accountXml("DbtrAcct", request.getDebtorAccount(), request.getDebtorName()), agent(sourceMemberId),
                agent(request.getCreditorBankCode()), xml(request.getCreditorName()),
                accountXml("CdtrAcct", request.getCreditorAccount(), request.getCreditorName()), xml(narration),
                debtorVerification, creditorVerification, xml(transactionLocation), xml(nameEnquiryMessageId),
                xml(channelCode));
        // Remove template-only whitespace before signing; never reformat a signed
        // message.
        return document.replaceAll(">\\s+<", "><").strip();
    }

    private static String formatAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.precision() - amount.scale() > 16) {
            throw new IllegalArgumentException("Invalid amount");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Amount must have at most two decimal places", exception);
        }
    }

    private static void account(String value, String name) {
        required(value, name, 10);
        if (!value.matches("[0-9]{10}"))
            throw new IllegalArgumentException(name + " must contain ten digits");
    }
    private static String accountXml(String tag, String number, String name) {
        return "<" + tag + "><Id><IBAN>" + xml(number) + "</IBAN></Id><Nm>" + xml(name) + "</Nm></" + tag + ">";
    }
    private static String buildPartyVerification(Map<String, String> metadata, String party) {
        String designation = required(metadata.get(party + "_account_designation"), party + "_account_designation", 1);
        String tier = required(metadata.get(party + "_account_tier"), party + "_account_tier", 1);
        String type = required(metadata.get(party + "_id_type"), party + "_id_type", 7);
        String value = required(metadata.get(party + "_id_value"), party + "_id_value", 35);
        if (!designation.matches("[1-6]") || !tier.matches("[1-3]"))
            throw new IllegalArgumentException("Invalid " + party + " designation or tier");
        if (!java.util.Set.of("BVN", "NIN", "JTBTIN", "FIRSTIN", "RC").contains(type))
            throw new IllegalArgumentException("Invalid " + party + "_id_type");
        if (type.equals("NIN") && !tier.equals("1"))
            throw new IllegalArgumentException("NIN requires account tier 1");
        if (java.util.Set.of("JTBTIN", "FIRSTIN", "RC").contains(type) && !designation.equals("1"))
            throw new IllegalArgumentException("Corporate ID requires corporate account designation 1");
        return "<AccountDesignation>" + xml(designation) + "</AccountDesignation><IdType>" + xml(type)
                + "</IdType><IdValue>" + xml(value) + "</IdValue><AccountTier>" + xml(tier) + "</AccountTier>";
    }

    public static class CreditTransferRequest {
        private java.util.Map<String, String> verificationDetails = java.util.Map.of();
        public void setVerificationDetails(java.util.Map<String, String> values) {
            verificationDetails = java.util.Map.copyOf(values);
        }
        public java.util.Map<String, String> getVerificationDetails() {
            return verificationDetails;
        }
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
        public String getEndToEndId() {
            return endToEndId;
        }
        public void setEndToEndId(String endToEndId) {
            this.endToEndId = endToEndId;
        }

        public BigDecimal getAmount() {
            return amount;
        }
        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public String getDebtorName() {
            return debtorName;
        }
        public void setDebtorName(String debtorName) {
            this.debtorName = debtorName;
        }

        public String getDebtorAccount() {
            return debtorAccount;
        }
        public void setDebtorAccount(String debtorAccount) {
            this.debtorAccount = debtorAccount;
        }

        public String getDebtorBvn() {
            return debtorBvn;
        }
        public void setDebtorBvn(String debtorBvn) {
            this.debtorBvn = debtorBvn;
        }

        public String getCreditorName() {
            return creditorName;
        }
        public void setCreditorName(String creditorName) {
            this.creditorName = creditorName;
        }

        public String getCreditorAccount() {
            return creditorAccount;
        }
        public void setCreditorAccount(String creditorAccount) {
            this.creditorAccount = creditorAccount;
        }

        public String getCreditorBankCode() {
            return creditorBankCode;
        }
        public void setCreditorBankCode(String creditorBankCode) {
            this.creditorBankCode = creditorBankCode;
        }

        public String getNarration() {
            return narration;
        }
        public void setNarration(String narration) {
            this.narration = narration;
        }

        public String getChannelCode() {
            return channelCode;
        }
        public void setChannelCode(String channelCode) {
            this.channelCode = channelCode;
        }

        public String getKycLevel() {
            return kycLevel;
        }
        public void setKycLevel(String kycLevel) {
            this.kycLevel = kycLevel;
        }

        public String getTransactionLocation() {
            return transactionLocation;
        }
        public void setTransactionLocation(String transactionLocation) {
            this.transactionLocation = transactionLocation;
        }

        public String getCategoryPurpose() {
            return categoryPurpose;
        }
        public void setCategoryPurpose(String categoryPurpose) {
            this.categoryPurpose = categoryPurpose;
        }

        public String getPurposeCode() {
            return purposeCode;
        }
        public void setPurposeCode(String purposeCode) {
            this.purposeCode = purposeCode;
        }
    }
}
