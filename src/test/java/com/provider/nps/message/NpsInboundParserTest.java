package com.provider.nps.message;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NpsInboundParserTest {
    @Test
    void readsPrefixedAmountsAndAccountsWithoutConfusingNestedIdentifiers() {
        String xml = """
                <p:BizMsg xmlns:p="urn:test"><p:Document><p:FIToFICstmrCdtTrf>
                    <p:GrpHdr><p:MsgId>original-id</p:MsgId></p:GrpHdr>
                    <p:CdtTrfTxInf>
                        <p:PmtId><p:EndToEndId>end-id</p:EndToEndId><p:TxId>tx-id</p:TxId></p:PmtId>
                        <p:IntrBkSttlmAmt Ccy="NGN">50000.00</p:IntrBkSttlmAmt>
                        <p:Dbtr><p:Nm>Sender</p:Nm></p:Dbtr>
                        <p:DbtrAcct><p:Id><p:Othr><p:Id>1234567890</p:Id></p:Othr></p:Id></p:DbtrAcct>
                        <p:DbtrAgt><p:FinInstnId><p:ClrSysMmbId><p:MmbId>000013</p:MmbId></p:ClrSysMmbId></p:FinInstnId></p:DbtrAgt>
                        <p:Cdtr><p:Nm>Receiver &amp; Co</p:Nm></p:Cdtr>
                        <p:CdtrAcct><p:Id><p:Othr><p:Id>0987654321</p:Id></p:Othr></p:Id></p:CdtrAcct>
                    </p:CdtTrfTxInf>
                </p:FIToFICstmrCdtTrf></p:Document></p:BizMsg>
                """;
        var transfer = new NpsInboundParser().parseCreditTransfer(xml);
        assertEquals("50000.00", transfer.getAmount());
        assertEquals("1234567890", transfer.getDebtorAccount());
        assertEquals("0987654321", transfer.getCreditorAccount());
        assertEquals("000013", transfer.getDebtorBankCode());
        assertEquals("Receiver & Co", transfer.getCreditorName());
        assertEquals("original-id", transfer.getMessageId());
    }
}
