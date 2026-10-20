package com.provider.nps.internal;

import static com.provider.nps.message.NpsFields.agent;
import static com.provider.nps.message.NpsFields.member;
import static com.provider.nps.message.NpsFields.required;
import static com.provider.nps.message.NpsFields.xml;

import com.provider.nps.crypto.NpsIdGenerator;
import com.provider.nps.internal.grpc.AccountIdentity;
import com.provider.nps.internal.grpc.AccountVerificationRequest;
import com.provider.nps.internal.grpc.InboundCreditRequest;
import com.provider.nps.internal.grpc.InstitutionAgent;
import com.provider.nps.internal.grpc.PaymentStatusNotification;
import com.provider.nps.xml.NpsXml;
import io.grpc.Status;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.w3c.dom.Document;

/**
 * Maps verified NIBSS business messages to internal RPCs and correlated XML
 * replies.
 */
@ApplicationScoped
public class InboundNpsForwarder {
    @Inject
    InstitutionGateway gateway;
    @Inject
    NpsIdGenerator ids;
    @Inject
    AdapterJournal journal;
    @ConfigProperty(name = "nps.source-id")
    String sourceId;

    public String nameEnquiry(String xml) {
        var doc = NpsXml.parse(xml);
        String base = NpsXml.businessPath(doc, "IdVrfctnReq");
        String messageId = messageId(doc, base + "Assgnmt/MsgId");
        String created = timestamp(doc, base + "Assgnmt/CreDtTm");
        String sender = member(value(doc, base + "Assgnmt/Assgnr/Agt/FinInstnId/ClrSysMmbId/MmbId", "assigner", 6),
                "assigner");
        String receiver = localMember(doc, base + "Assgnmt/Assgne/Agt/FinInstnId/ClrSysMmbId/MmbId");
        String senderName = value(doc, base + "Assgnmt/Assgnr/Pty/Nm", "assigner name", 100);
        String account = accountNumber(doc, base + "Vrfctn/PtyAndAcctId/Acct/");
        var request = AccountVerificationRequest.newBuilder().setIdempotencyKey(key("acmt023", sender, messageId))
                .setMessageId(messageId).setCreationTime(created)
                .setVerificationId(value(doc, base + "Vrfctn/Id", "verification ID", 35))
                .setAssigner(institution(sender)).setAssignee(institution(receiver)).setAccountNumber(account)
                .setExpectedAccountName(value(doc, base + "Vrfctn/PtyAndAcctId/Pty/Nm", "party name", 100)).build();
        return journal.execute(request.getIdempotencyKey(), BusinessFingerprint.of(doc, "IdVrfctnReq"), () -> {
            var result = gateway.verify(request);
            correlated(messageId, result.getOriginalMessageId());
            if (result.getVerified()) {
                if (!account.equals(result.getCreditor().getAccountNumber()))
                    throw invalidDecision("Account verification returned a different account");
                try {
                    validateIdentity(result.getCreditor());
                } catch (IllegalArgumentException exception) {
                    throw invalidDecision("Incomplete verified account data");
                }
            } else if (result.getReasonCode().isBlank())
                throw invalidDecision("Verification rejection requires a reason");
            String reason = result.getVerified()
                    ? ""
                    : "<Rsn><Cd>" + xml(result.getReasonCode()) + "</Cd><Prtry>" + xml(result.getReasonDescription())
                            + "</Prtry></Rsn>";
            String updated = result.getVerified()
                    ? "<UpdtdPtyAndAcctId><Pty><Nm>" + xml(result.getCreditor().getAccountName())
                            + "</Nm></Pty></UpdtdPtyAndAcctId>"
                    : "";
            var verifiedIdentity = result.getVerified() ? result.getCreditor() : AccountIdentity.getDefaultInstance();
            return compact(
                    """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <ns2:Document xmlns:ns2="urn:iso:std:iso:20022:tech:xsd:acmt.024.001.04"><IdVrfctnRpt>
                            <Assgnmt><MsgId>%s</MsgId><CreDtTm>%s</CreDtTm><Assgnr><Agt>%s</Agt></Assgnr>
                            <Assgne><Pty><Nm>%s</Nm></Pty><Agt>%s</Agt></Assgne></Assgnmt>
                            <OrgnlAssgnmt><MsgId>%s</MsgId><CreDtTm>%s</CreDtTm></OrgnlAssgnmt>
                            <Rpt><OrgnlId>%s</OrgnlId><Vrfctn>%s</Vrfctn>%s<OrgnlPtyAndAcctId><Acct><Id><IBAN>%s</IBAN></Id></Acct></OrgnlPtyAndAcctId>%s</Rpt>
                            <SplmtryData><PlcAndNm>AdditionalVerificationDetails</PlcAndNm><Envlp><CustomData><CreditorInfo>%s</CreditorInfo>
                            <TransactionInfo><RiskRating>%s</RiskRating></TransactionInfo></CustomData></Envlp></SplmtryData>
                            </IdVrfctnRpt></ns2:Document>
                            """
                            .formatted(xml(ids.generateMessageId()), xml(ids.currentTimestamp()), agent(receiver),
                                    xml(senderName), agent(sender), xml(messageId), xml(created),
                                    xml(request.getVerificationId()), result.getVerified(), reason, xml(account),
                                    updated, identityXml(verifiedIdentity),
                                    xml(result.getVerified() ? result.getRiskRating() : "")));
        });
    }

    public String credit(String xml) {
        var doc = NpsXml.parse(xml);
        String base = NpsXml.businessPath(doc, "FIToFICstmrCdtTrf");
        if (doc.getElementsByTagNameNS("*", "CdtTrfTxInf").getLength() != 1)
            throw new IllegalArgumentException("Exactly one credit transfer is required");
        String tx = base + "CdtTrfTxInf/";
        String messageId = messageId(doc, base + "GrpHdr/MsgId");
        String created = timestamp(doc, base + "GrpHdr/CreDtTm");
        String sender = member(value(doc, tx + "InstgAgt/FinInstnId/ClrSysMmbId/MmbId", "instructing agent", 6),
                "instructing agent");
        String receiver = localMember(doc, tx + "InstdAgt/FinInstnId/ClrSysMmbId/MmbId");
        String amount = value(doc, tx + "IntrBkSttlmAmt", "amount", 19);
        if (!amount.matches("[0-9]+(?:\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Invalid decimal amount");
        try {
            var decimal = new BigDecimal(amount);
            if (decimal.signum() <= 0 || decimal.precision() - decimal.scale() > 16)
                throw new IllegalArgumentException("Invalid amount");
            decimal.setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Invalid amount", e);
        }
        String currency = currency(doc);
        if (!"NGN".equals(currency))
            throw new IllegalArgumentException("Only NGN inbound transfers are supported");
        String settlementDate = value(doc, tx + "IntrBkSttlmDt", "settlement date", 11);
        try {
            java.time.LocalDate.parse(settlementDate.endsWith("Z")
                    ? settlementDate.substring(0, settlementDate.length() - 1)
                    : settlementDate);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("Invalid settlement date", e);
        }
        String supplementary = base + "SplmtryData/Envlp/CustomData/";
        String info = supplementary + "TransactionInfo/";
        var request = InboundCreditRequest.newBuilder().setIdempotencyKey(key("pacs008", sender, messageId))
                .setMessageId(messageId).setCreationTime(created).setAmount(amount).setCurrency(currency)
                .setInstructionId(value(doc, tx + "PmtId/InstrId", "instruction ID", 35))
                .setEndToEndId(value(doc, tx + "PmtId/EndToEndId", "end-to-end ID", 35))
                .setTransactionId(value(doc, tx + "PmtId/TxId", "transaction ID", 35)).setSettlementDate(settlementDate)
                .setInstructingAgent(institution(sender)).setInstructedAgent(institution(receiver))
                .setDebtor(identity(doc, tx, supplementary, "Dbtr", "DebtorInfo"))
                .setCreditor(identity(doc, tx, supplementary, "Cdtr", "CreditorInfo"))
                .setNarration(text(doc, tx + "RmtInf/Ustrd"))
                .setNameEnquiryMessageId(text(doc, info + "NameEnquiryMsgId"))
                .setChannelCode(text(doc, info + "ChannelCode"))
                .setTransactionLocation(text(doc, info + "TransactionLocation"))
                .setRiskRating(text(doc, info + "RiskRating")).build();
        return journal.execute(request.getIdempotencyKey(), BusinessFingerprint.of(doc, "FIToFICstmrCdtTrf"), () -> {
            var result = gateway.credit(request);
            correlated(messageId, result.getOriginalMessageId());
            String status;
            String statusId;
            switch (result.getDecision()) {
                case CREDIT_AUTHORIZED -> {
                    status = "ACSC";
                    statusId = "AUTH";
                }
                case CREDIT_REJECTED -> {
                    if (result.getReasonCode().isBlank())
                        throw invalidDecision("Credit rejection requires a reason");
                    status = "RJCT";
                    statusId = "NAUT";
                }
                default ->
                    throw Status.UNAVAILABLE.withDescription("Credit decision is unresolved").asRuntimeException();
            }
            String reason = statusId.equals("AUTH")
                    ? ""
                    : "<StsRsnInf><Rsn><Prtry>" + xml(result.getReasonCode()) + "</Prtry></Rsn><AddtlInf>"
                            + xml(result.getReasonDescription()) + "</AddtlInf></StsRsnInf>";
            return compact(
                    """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <ns2:Document xmlns:ns2="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.12"><FIToFIPmtStsRpt>
                            <GrpHdr><MsgId>%s</MsgId><CreDtTm>%s</CreDtTm><InstgAgt>%s</InstgAgt><InstdAgt>%s</InstdAgt></GrpHdr>
                            <OrgnlGrpInfAndSts><OrgnlMsgId>%s</OrgnlMsgId><OrgnlMsgNmId>pacs.008.001.12</OrgnlMsgNmId><OrgnlCreDtTm>%s</OrgnlCreDtTm><GrpSts>%s</GrpSts></OrgnlGrpInfAndSts>
                            <TxInfAndSts><StsId>%s</StsId><OrgnlInstrId>%s</OrgnlInstrId><OrgnlEndToEndId>%s</OrgnlEndToEndId><OrgnlTxId>%s</OrgnlTxId>%s
                            <InstgAgt>%s</InstgAgt><InstdAgt>%s</InstdAgt><OrgnlTxRef><IntrBkSttlmDt>%s</IntrBkSttlmDt></OrgnlTxRef></TxInfAndSts>
                            </FIToFIPmtStsRpt></ns2:Document>
                            """
                            .formatted(xml(ids.generateMessageId()), xml(ids.currentTimestamp()), agent(receiver),
                                    agent(sender), xml(messageId), xml(created), status, statusId,
                                    xml(request.getInstructionId()), xml(request.getEndToEndId()),
                                    xml(request.getTransactionId()), reason, agent(sender), agent(receiver),
                                    xml(request.getSettlementDate())));
        });
    }

    public void paymentStatus(String xml) {
        var doc = NpsXml.parse(xml);
        String base = NpsXml.businessPath(doc, "FIToFIPmtStsRpt");
        String tx = base + "TxInfAndSts/";
        String id = messageId(doc, base + "GrpHdr/MsgId");
        String sender = member(value(doc, base + "GrpHdr/InstgAgt/FinInstnId/ClrSysMmbId/MmbId", "sender", 6),
                "sender");
        localMember(doc, base + "GrpHdr/InstdAgt/FinInstnId/ClrSysMmbId/MmbId");
        String reason = text(doc, tx + "StsRsnInf/Rsn/Prtry");
        if (reason.isBlank())
            reason = text(doc, tx + "StsRsnInf/Rsn/Cd");
        var notification = PaymentStatusNotification.newBuilder().setIdempotencyKey(key("pacs002", sender, id))
                .setMessageId(id).setCreationTime(text(doc, base + "GrpHdr/CreDtTm"))
                .setOriginalCreationTime(text(doc, base + "OrgnlGrpInfAndSts/OrgnlCreDtTm"))
                .setOriginalMessageId(value(doc, base + "OrgnlGrpInfAndSts/OrgnlMsgId", "original message ID", 35))
                .setOriginalInstructionId(text(doc, tx + "OrgnlInstrId"))
                .setOriginalEndToEndId(text(doc, tx + "OrgnlEndToEndId"))
                .setOriginalTransactionId(text(doc, tx + "OrgnlTxId"))
                .setGroupStatus(text(doc, base + "OrgnlGrpInfAndSts/GrpSts"))
                .setTransactionStatus(text(doc, tx + "TxSts")).setStatusId(text(doc, tx + "StsId"))
                .setReasonCode(reason).setReasonDescription(text(doc, tx + "StsRsnInf/AddtlInf"))
                .setSettlementDate(text(doc, tx + "OrgnlTxRef/IntrBkSttlmDt")).build();
        journal.execute(notification.getIdempotencyKey(), BusinessFingerprint.of(doc, "FIToFIPmtStsRpt"), () -> {
            var receipt = gateway.notifyStatus(notification);
            correlated(id, receipt.getMessageId());
            if (!receipt.getDurablyAccepted())
                throw invalidDecision("Internal service did not durably accept the status notification");
            return "";
        });
    }

    private AccountIdentity identity(Document doc, String tx, String supplementary, String party, String details) {
        return AccountIdentity.newBuilder().setAccountNumber(accountNumber(doc, tx + party + "Acct/"))
                .setAccountName(value(doc, tx + party + "/Nm", "account name", 100))
                .setAccountDesignation(text(doc, supplementary + details + "/AccountDesignation"))
                .setAccountTier(text(doc, supplementary + details + "/AccountTier"))
                .setIdType(text(doc, supplementary + details + "/IdType"))
                .setIdValue(text(doc, supplementary + details + "/IdValue")).build();
    }
    private static void validateIdentity(AccountIdentity account) {
        required(account.getAccountName(), "verified account name", 100);
        if (!account.getAccountDesignation().matches("[1-6]") || !account.getAccountTier().matches("[1-3]"))
            throw invalidDecision("Invalid verified account designation/tier");
        if (!java.util.Set.of("BVN", "NIN", "JTBTIN", "FIRSTIN", "RC").contains(account.getIdType()))
            throw invalidDecision("Invalid verified ID type");
        required(account.getIdValue(), "verified ID value", 35);
        if (account.getIdType().equals("NIN") && !account.getAccountTier().equals("1"))
            throw invalidDecision("NIN requires tier 1");
        if (java.util.Set.of("JTBTIN", "FIRSTIN", "RC").contains(account.getIdType())
                && !account.getAccountDesignation().equals("1"))
            throw invalidDecision("Corporate ID requires corporate designation");
    }
    private static String identityXml(AccountIdentity identity) {
        return "<AccountDesignation>" + xml(identity.getAccountDesignation()) + "</AccountDesignation><IdType>"
                + xml(identity.getIdType()) + "</IdType><IdValue>" + xml(identity.getIdValue())
                + "</IdValue><AccountTier>" + xml(identity.getAccountTier()) + "</AccountTier>";
    }
    private String localMember(Document doc, String path) {
        String receiver = member(value(doc, path, "receiving institution", 6), "receiving institution");
        if (!receiver.equals(sourceId))
            throw new IllegalArgumentException("Message is addressed to a different institution");
        return receiver;
    }
    private static String currency(Document doc) {
        var amount = doc.getElementsByTagNameNS("*", "IntrBkSttlmAmt").item(0);
        var currency = amount == null ? null : amount.getAttributes().getNamedItem("Ccy");
        return currency == null ? "" : currency.getNodeValue();
    }
    private static InstitutionAgent institution(String member) {
        return InstitutionAgent.newBuilder().setMemberId(member).build();
    }
    private static String accountNumber(Document doc, String path) {
        String number = required(NpsXml.account(doc, path), "account number", 10);
        if (!number.matches("[0-9]{10}"))
            throw new IllegalArgumentException("Account number must contain ten digits");
        return number;
    }
    private static String messageId(Document document, String path) {
        String id = value(document, path, "MsgId", 35);
        if (!id.matches("[0-9]{35}"))
            throw new IllegalArgumentException("MsgId must contain exactly 35 digits");
        return id;
    }
    private static String timestamp(Document doc, String path) {
        String value = value(doc, path, "creation time", 35);
        try {
            OffsetDateTime.parse(value);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("Invalid creation time", e);
        }
        return value;
    }
    private static String value(Document doc, String path, String field, int max) {
        return required(NpsXml.text(doc, path), field, max);
    }
    private static String text(Document doc, String path) {
        String value = NpsXml.text(doc, path);
        return value == null ? "" : value;
    }
    private static String compact(String xml) {
        return xml.replaceAll(">\\s+<", "><").strip();
    }
    private static void correlated(String expected, String actual) {
        if (!expected.equals(actual))
            throw invalidDecision("Internal response correlation mismatch");
    }
    private static io.grpc.StatusRuntimeException invalidDecision(String description) {
        return Status.UNAVAILABLE.withDescription(description).asRuntimeException();
    }
    private static String key(String type, String sender, String id) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((type + "|" + sender + "|" + id).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
