package com.provider.nps.message;

import static com.provider.nps.message.NpsFields.agent;
import static com.provider.nps.message.NpsFields.member;
import static com.provider.nps.message.NpsFields.required;
import static com.provider.nps.message.NpsFields.xml;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** NIBSS identification verification request, acmt.023.001.04. */
@ApplicationScoped
public class Acmt023Builder {
    @Inject
    NpsMessageBuilder baseBuilder;

    /** The guide requires the party name; do not invent one for legacy callers. */
    @Deprecated
    public String build(String accountNumber, String bankCode, String channelCode) {
        throw new IllegalArgumentException("Missing required metadata: account_name");
    }

    public String build(String accountNumber, String bankCode, String channelCode, String accountName) {
        required(accountNumber, "account_number", 10);
        if (!accountNumber.matches("[0-9]{10}"))
            throw new IllegalArgumentException("account_number must contain ten digits");
        required(accountName, "account_name", 100);
        member(bankCode, "bank_code");
        String source = member(baseBuilder.getSourceId(), "NPS_SOURCE_ID");
        String institution = required(baseBuilder.getSourceName(), "NPS_SOURCE_NAME", 100);
        String id = baseBuilder.getIdGenerator().generateMessageId();
        return NpsMessageBuilder.XML_DECLARATION + "<ns2:Document xmlns:ns2=\"" + NpsMessageBuilder.ACMT_023_NS
                + "\"><IdVrfctnReq><Assgnmt><MsgId>" + xml(id) + "</MsgId><CreDtTm>"
                + xml(baseBuilder.getIdGenerator().currentTimestamp()) + "</CreDtTm>" + "<Cretr><Pty><Nm>"
                + xml(institution) + "</Nm></Pty></Cretr><Assgnr><Pty><Nm>" + xml(institution) + "</Nm></Pty><Agt>"
                + agent(source) + "</Agt></Assgnr><Assgne><Agt>" + agent(bankCode)
                + "</Agt></Assgne></Assgnmt><Vrfctn><Id>" + xml(id) + "</Id><PtyAndAcctId><Pty><Nm>" + xml(accountName)
                + "</Nm></Pty><Acct><Id><IBAN>" + xml(accountNumber)
                + "</IBAN></Id></Acct></PtyAndAcctId></Vrfctn></IdVrfctnReq></ns2:Document>";
    }

    public String buildWithDetails(NameEnquiryRequest request) {
        return build(request.getAccountNumber(), request.getBankCode(), request.getChannelCode(),
                request.getAccountName());
    }

    public static class NameEnquiryRequest {
        private String accountName;
        public String getAccountName() {
            return accountName;
        }
        public void setAccountName(String value) {
            accountName = value;
        }
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
