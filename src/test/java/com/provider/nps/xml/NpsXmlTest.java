package com.provider.nps.xml;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NpsXmlTest {
    @Test
    void rejectsDoctypeAndExternalEntities() {
        assertThrows(IllegalArgumentException.class, () -> NpsXml.parse("""
                <!DOCTYPE Document [<!ENTITY secret SYSTEM "file:///missing-secret">]>
                <Document>&secret;</Document>
                """));
    }

    @Test
    void rejectsMalformedXml() {
        assertThrows(IllegalArgumentException.class, () -> NpsXml.parse("<Document>"));
    }
}
