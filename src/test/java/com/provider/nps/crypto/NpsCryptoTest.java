package com.provider.nps.crypto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NpsCryptoTest {
    private static final String XML = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
                    <FIToFICstmrCdtTrf><GrpHdr><MsgId>test-id</MsgId></GrpHdr></FIToFICstmrCdtTrf>
                </Document>
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
    void signatureUsesGuidePlacementAndCanonicalization() throws Exception {
        var document = com.provider.nps.xml.NpsXml.parse(signer().sign(XML));
        var signatures = document.getElementsByTagNameNS(javax.xml.crypto.dsig.XMLSignature.XMLNS, "Signature");
        assertEquals(1, signatures.getLength());
        assertSame(document.getDocumentElement(), signatures.item(0).getParentNode());
        var method = (org.w3c.dom.Element) document
                .getElementsByTagNameNS(javax.xml.crypto.dsig.XMLSignature.XMLNS, "CanonicalizationMethod").item(0);
        assertEquals(javax.xml.crypto.dsig.CanonicalizationMethod.EXCLUSIVE, method.getAttribute("Algorithm"));
    }

    @Test
    void tamperedAndUnsignedMessagesAreRejected() throws Exception {
        var signer = signer();
        assertFalse(signer.verify(XML));
        String signed = signer.sign(XML);
        assertFalse(signer.verify(signed.replace("<MsgId>test-id</MsgId>", "<MsgId>tampered</MsgId>")));
    }

    @Test
    void duplicateSignaturesAreRejected() throws Exception {
        var signer = signer();
        var doc = com.provider.nps.xml.NpsXml.parse(signer.sign(XML));
        var signature = doc.getElementsByTagNameNS(javax.xml.crypto.dsig.XMLSignature.XMLNS, "Signature").item(0);
        doc.getDocumentElement().appendChild(signature.cloneNode(true));
        assertFalse(signer.verify(serialize(doc)));
    }

    @Test
    void cryptographicallyValidSubtreeSignatureCannotAuthenticateTheWholeMessage() throws Exception {
        var doc = com.provider.nps.xml.NpsXml.parse(XML);
        var body = (org.w3c.dom.Element) doc.getDocumentElement().getElementsByTagNameNS("*", "GrpHdr").item(0);
        body.setAttribute("Id", "signed-fragment");
        body.setIdAttribute("Id", true);
        var factory = javax.xml.crypto.dsig.XMLSignatureFactory.getInstance("DOM");
        var reference = factory.newReference("#signed-fragment",
                factory.newDigestMethod(javax.xml.crypto.dsig.DigestMethod.SHA256, null));
        var info = factory.newSignedInfo(
                factory.newCanonicalizationMethod(javax.xml.crypto.dsig.CanonicalizationMethod.EXCLUSIVE,
                        (javax.xml.crypto.dsig.spec.C14NMethodParameterSpec) null),
                factory.newSignatureMethod(javax.xml.crypto.dsig.SignatureMethod.RSA_SHA256, null),
                java.util.List.of(reference));
        factory.newXMLSignature(info, null).sign(new javax.xml.crypto.dsig.dom.DOMSignContext(
                NpsPemKeys.privateKey("src/test/resources/test-private.pem"), doc.getDocumentElement()));
        var signatureNode = doc.getElementsByTagNameNS(javax.xml.crypto.dsig.XMLSignature.XMLNS, "Signature").item(0);
        var context = new javax.xml.crypto.dsig.dom.DOMValidateContext(
                NpsPemKeys.publicKey("src/test/resources/test-public.pem"), signatureNode);
        assertTrue(factory.unmarshalXMLSignature(context).validate(context));
        assertFalse(signer().verify(serialize(doc)));
    }

    private static String serialize(org.w3c.dom.Document doc) throws Exception {
        var output = new java.io.StringWriter();
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
                new javax.xml.transform.dom.DOMSource(doc), new javax.xml.transform.stream.StreamResult(output));
        return output.toString();
    }

    @Test
    void signerRejectsExternalEntities() {
        var signer = signer();
        assertThrows(Exception.class, () -> signer.sign("<!DOCTYPE BizMsg><BizMsg><AppHdr/></BizMsg>"));
    }
    private NpsXmlEncryptor encryptor() {
        var encryptor = new NpsXmlEncryptor();
        encryptor.privateKeyPath = "src/test/resources/test-private.pem";
        encryptor.nibssPublicKeyPath = "src/test/resources/test-public.pem";
        encryptor.init();
        return encryptor;
    }

    @Test
    void encryptedPayloadMatchesEndpointStructureAndIndependentJceDecryption() throws Exception {
        var encryptor = encryptor();
        String encrypted = encryptor.encrypt(signer().sign(XML));
        var doc = com.provider.nps.xml.NpsXml.parse(encrypted);
        var data = (org.w3c.dom.Element) doc
                .getElementsByTagNameNS("http://www.w3.org/2001/04/xmlenc#", "EncryptedData").item(0);
        assertEquals("FIToFICstmrCdtTrf", data.getParentNode().getLocalName());
        var signature = doc.getElementsByTagNameNS(javax.xml.crypto.dsig.XMLSignature.XMLNS, "Signature").item(0);
        assertSame(doc.getDocumentElement(), signature.getParentNode());
        assertFalse(encrypted.contains("<MsgId>test-id</MsgId>"));
        byte[] wrapped = java.util.Base64.getMimeDecoder()
                .decode(com.provider.nps.xml.NpsXml.text(data, "KeyInfo/EncryptedKey/CipherData/CipherValue"));
        var rsa = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(javax.crypto.Cipher.DECRYPT_MODE, NpsPemKeys.privateKey("src/test/resources/test-private.pem"),
                new javax.crypto.spec.OAEPParameterSpec("SHA-1", "MGF1", java.security.spec.MGF1ParameterSpec.SHA1,
                        javax.crypto.spec.PSource.PSpecified.DEFAULT));
        var aesKey = new javax.crypto.spec.SecretKeySpec(rsa.doFinal(wrapped), "AES");
        assertEquals(32, aesKey.getEncoded().length);
        byte[] payload = java.util.Base64.getMimeDecoder()
                .decode(com.provider.nps.xml.NpsXml.text(data, "CipherData/CipherValue"));
        var aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(javax.crypto.Cipher.DECRYPT_MODE, aesKey,
                new javax.crypto.spec.GCMParameterSpec(128, java.util.Arrays.copyOfRange(payload, 0, 12)));
        String plaintext = new String(aes.doFinal(java.util.Arrays.copyOfRange(payload, 12, payload.length)),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(plaintext.contains("test-id"));
        assertFalse(plaintext.contains("Signature"));
        assertFalse(plaintext.contains("<Document"));
    }

    @Test
    void preservesSignatureWithPrefixedDocumentAndUnqualifiedBusinessChildren() throws Exception {
        String xml = "<ns2:Document xmlns:ns2=\"urn:iso:std:iso:20022:tech:xsd:acmt.023.001.04\">"
                + "<IdVrfctnReq><Assgnmt><MsgId>prefixed-id</MsgId></Assgnmt></IdVrfctnReq></ns2:Document>";
        var signer = signer();
        var encryptor = encryptor();
        assertTrue(signer.verify(encryptor.decrypt(encryptor.encrypt(signer.sign(xml)))));
    }

    @Test
    void encryptedBase64MayContainXmlWhitespaceAndUnknownAlgorithmsAreRejected() throws Exception {
        var encryptor = encryptor();
        var signer = signer();
        String encrypted = encryptor.encrypt(signer.sign(XML));
        var doc = com.provider.nps.xml.NpsXml.parse(encrypted);
        var values = doc.getElementsByTagNameNS("http://www.w3.org/2001/04/xmlenc#", "CipherValue");
        for (int i = 0; i < values.getLength(); i++) {
            values.item(i).setTextContent("\n  " + values.item(i).getTextContent() + "\r\n");
        }
        var writer = new java.io.StringWriter();
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
                new javax.xml.transform.dom.DOMSource(doc), new javax.xml.transform.stream.StreamResult(writer));
        assertTrue(signer.verify(encryptor.decrypt(writer.toString())));
        assertThrows(IllegalArgumentException.class,
                () -> encryptor.decrypt(encrypted.replace("aes256-gcm", "aes256-cbc")));
    }
    @Test
    void cipherReferencesAreRejectedBeforeResolution() throws Exception {
        String encrypted = encryptor().encrypt(signer().sign(XML));
        var doc = com.provider.nps.xml.NpsXml.parse(encrypted);
        var data = (org.w3c.dom.Element) doc
                .getElementsByTagNameNS("http://www.w3.org/2001/04/xmlenc#", "EncryptedData").item(0);
        data.appendChild(doc.createElementNS("http://www.w3.org/2001/04/xmlenc#", "xenc:CipherReference"));
        var writer = new java.io.StringWriter();
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
                new javax.xml.transform.dom.DOMSource(doc), new javax.xml.transform.stream.StreamResult(writer));
        assertThrows(IllegalArgumentException.class, () -> encryptor().decrypt(writer.toString()));
    }
    @Test
    void signedAndEncryptedWireMessagesIncludeUtf8XmlDeclaration() throws Exception {
        String signed = signer().sign(XML);
        assertTrue(signed.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\""));
        String encrypted = encryptor().encrypt(signed);
        assertTrue(encrypted.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\""));
        assertTrue(signer().verify(encryptor().decrypt(encrypted)));
    }
}
