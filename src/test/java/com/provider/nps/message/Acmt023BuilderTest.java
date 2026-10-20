package com.provider.nps.message;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class Acmt023BuilderTest {

    @Inject
    Acmt023Builder builder;

    @Test
    void testBuildNameEnquiryMessage() {
        String xml = builder.build("0123456789", "000013", "1", "Receiver & Co");

        assertNotNull(xml);
        assertFalse(xml.contains("<BizMsg"));
        assertFalse(xml.contains("<AppHdr"));
        assertTrue(xml.contains("<ns2:Document"));
        assertTrue(xml.contains("<IdVrfctnReq>"));
        assertTrue(xml.contains("<IBAN>0123456789</IBAN>"));
        assertTrue(xml.contains("<MmbId>000013</MmbId>"));
        assertTrue(xml.contains("<Nm>Receiver &amp; Co</Nm>"));
        assertTrue(xml.contains("acmt.023.001.04"));
    }

    @Test
    void testMessageIdFormat() {
        String xml = builder.build("0123456789", "000013", "1", "Receiver & Co");

        // MsgId should be 35 characters
        int msgIdStart = xml.indexOf("<MsgId>") + 7;
        int msgIdEnd = xml.indexOf("</MsgId>");
        String msgId = xml.substring(msgIdStart, msgIdEnd);

        assertEquals(35, msgId.length(), "MsgId should be 35 characters");
    }
    @Test
    void guideFieldsRouteDirectlyToDestinationAndEscapePartyName() {
        var doc = com.provider.nps.xml.NpsXml.parse(builder.build("0123456789", "000013", "1", "Receiver & Co"));
        String base = "Document/IdVrfctnReq/";
        assertEquals("000013",
                com.provider.nps.xml.NpsXml.text(doc, base + "Assgnmt/Assgne/Agt/FinInstnId/ClrSysMmbId/MmbId"));
        assertEquals("999057",
                com.provider.nps.xml.NpsXml.text(doc, base + "Assgnmt/Assgnr/Agt/FinInstnId/ClrSysMmbId/MmbId"));
        assertEquals("TEST BANK LIMITED", com.provider.nps.xml.NpsXml.text(doc, base + "Assgnmt/Cretr/Pty/Nm"));
        assertEquals("Receiver & Co", com.provider.nps.xml.NpsXml.text(doc, base + "Vrfctn/PtyAndAcctId/Pty/Nm"));
    }

    @Test
    void missingNameAndInvalidAccountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> builder.build("0123456789", "000013", "1"));
        assertThrows(IllegalArgumentException.class, () -> builder.build("bad-account", "000013", "1", "Receiver"));
        assertThrows(IllegalArgumentException.class, () -> builder.build("0123456789", "bad-bank", "1", "Receiver"));
    }
}
