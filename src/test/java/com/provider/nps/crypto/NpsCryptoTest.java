package com.provider.nps.crypto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NpsCryptoTest {
    private static final String XML = """
            <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                <AppHdr><BizMsgIdr>test-id</BizMsgIdr></AppHdr>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
                    <FIToFICstmrCdtTrf><GrpHdr><MsgId>test-id</MsgId></GrpHdr></FIToFICstmrCdtTrf>
                </Document>
            </BizMsg>
            """;

    private NpsXmlSigner signer() {
        var signer = new NpsXmlSigner();
        signer.privateKeyPath = "src/test/resources/test-private.pem";
        signer.certificatePath = "src/test/resources/test-cert.pem";
        // Verify our test messages using the public key paired with the test private
        // key.
        signer.nibssPublicKeyPath = "src/test/resources/test-public.pem";
        signer.init();
        return signer;
    }

    @Test
    void signedMessageSurvivesEncryptionAndDecryption() throws Exception {
        var signer = signer();
        var encryptor = new NpsXmlEncryptor();
        encryptor.privateKeyPath = "src/test/resources/test-private.pem";
        encryptor.certificatePath = "src/test/resources/test-cert.pem";
        encryptor.nibssPublicKeyPath = "src/test/resources/test-public.pem";
        encryptor.init();
        String signed = signer.sign(XML);
        assertTrue(signer.verify(signed));
        String encrypted = encryptor.encrypt(signed);
        assertTrue(encrypted.contains("EncryptedData"));
        String decrypted = encryptor.decrypt(encrypted);
        assertTrue(signer.verify(decrypted));
    }

    @Test
    void tamperedAndUnsignedMessagesAreRejected() throws Exception {
        var signer = signer();
        assertFalse(signer.verify(XML));
        String signed = signer.sign(XML);
        assertFalse(signer.verify(signed.replace("<MsgId>test-id</MsgId>", "<MsgId>tampered</MsgId>")));
    }

    @Test
    void signerRejectsExternalEntities() {
        var signer = signer();
        assertThrows(Exception.class, () -> signer.sign("<!DOCTYPE BizMsg><BizMsg><AppHdr/></BizMsg>"));
    }
}
