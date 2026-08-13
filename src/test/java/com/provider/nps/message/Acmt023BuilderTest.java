package com.provider.nps.message;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.provider.nps.crypto.NpsIdGenerator;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class Acmt023BuilderTest {

    @Inject
    Acmt023Builder builder;

    @Test
    void testBuildNameEnquiryMessage() {
        String xml = builder.build("0123456789", "000013", "1");

        assertNotNull(xml);
        assertTrue(xml.contains("<BizMsg"));
        assertTrue(xml.contains("<AppHdr"));
        assertTrue(xml.contains("<Document"));
        assertTrue(xml.contains("<IdVrfctnReq>"));
        assertTrue(xml.contains("<Id>0123456789</Id>"));
        assertTrue(xml.contains("<MmbId>000013</MmbId>"));
        assertTrue(xml.contains("<ChnlCd>1</ChnlCd>"));
        assertTrue(xml.contains("acmt.023.001.03"));
    }

    @Test
    void testMessageIdFormat() {
        String xml = builder.build("0123456789", "000013", "1");

        // MsgId should be 35 characters
        int msgIdStart = xml.indexOf("<MsgId>") + 7;
        int msgIdEnd = xml.indexOf("</MsgId>");
        String msgId = xml.substring(msgIdStart, msgIdEnd);

        assertEquals(35, msgId.length(), "MsgId should be 35 characters");
    }
}
