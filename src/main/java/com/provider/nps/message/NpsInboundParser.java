package com.provider.nps.message;

import com.provider.nps.callback.NpsCallbackResource.InboundCreditTransfer;
import com.provider.nps.callback.NpsCallbackResource.InboundNameEnquiry;
import com.provider.nps.xml.NpsXml;
import jakarta.enterprise.context.ApplicationScoped;

/** Reads inbound business fields without depending on namespace prefixes. */
@ApplicationScoped
public class NpsInboundParser {
    public InboundCreditTransfer parseCreditTransfer(String xml) {
        var document = NpsXml.parse(xml);
        String body = NpsXml.businessPath(document, "FIToFICstmrCdtTrf");
        String group = body + "GrpHdr/";
        String transaction = body + "CdtTrfTxInf/";
        InboundCreditTransfer transfer = new InboundCreditTransfer();
        transfer.setMessageId(NpsXml.text(document, group + "MsgId"));
        transfer.setEndToEndId(NpsXml.text(document, transaction + "PmtId/EndToEndId"));
        transfer.setTransactionId(NpsXml.text(document, transaction + "PmtId/TxId"));
        String amount = NpsXml.text(document, transaction + "IntrBkSttlmAmt");
        transfer.setAmount(amount != null ? amount : NpsXml.text(document, transaction + "InstdAmt"));
        transfer.setDebtorName(NpsXml.text(document, transaction + "Dbtr/Nm"));
        transfer.setDebtorAccount(NpsXml.account(document, transaction + "DbtrAcct/"));
        String bankCode = NpsXml.text(document, transaction + "DbtrAgt/FinInstnId/ClrSysMmbId/MmbId");
        transfer.setDebtorBankCode(
                bankCode != null ? bankCode : NpsXml.text(document, transaction + "DbtrAgt/FinInstnId/BICFI"));
        transfer.setCreditorName(NpsXml.text(document, transaction + "Cdtr/Nm"));
        transfer.setCreditorAccount(NpsXml.account(document, transaction + "CdtrAcct/"));
        transfer.setNarration(NpsXml.text(document, transaction + "RmtInf/Ustrd"));
        return transfer;
    }

    public InboundNameEnquiry parseNameEnquiry(String xml) {
        var document = NpsXml.parse(xml);
        String request = NpsXml.businessPath(document, "IdVrfctnReq");
        InboundNameEnquiry enquiry = new InboundNameEnquiry();
        enquiry.setMessageId(NpsXml.text(document, request + "Assgnmt/MsgId"));
        enquiry.setAccountNumber(NpsXml.account(document, request + "Vrfctn/PtyAndAcctId/Acct/"));
        String bankCode = NpsXml.text(document, request + "Assgnmt/Assgnr/Agt/FinInstnId/ClrSysMmbId/MmbId");
        enquiry.setSourceBankCode(
                bankCode != null ? bankCode : NpsXml.text(document, request + "Assgnmt/Assgnr/Agt/FinInstnId/BICFI"));
        enquiry.setChannelCode(NpsXml.text(document, request + "SplmtryData/Envlp/ChnlCd"));
        return enquiry;
    }

}
