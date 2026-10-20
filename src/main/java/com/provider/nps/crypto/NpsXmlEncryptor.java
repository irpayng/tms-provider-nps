package com.provider.nps.crypto;

import com.provider.nps.xml.NpsXml;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.StringWriter;
import java.security.PrivateKey;
import java.security.PublicKey;
import javax.crypto.KeyGenerator;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.xml.security.encryption.XMLCipher;
import org.apache.xml.security.keys.KeyInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * NIBSS message-content encryption: AES-256-GCM, RSA-OAEP with SHA-1/MGF1-SHA1.
 */
@ApplicationScoped
public class NpsXmlEncryptor {
    private static final String XENC = "http://www.w3.org/2001/04/xmlenc#";
    private static final String DSIG = "http://www.w3.org/2000/09/xmldsig#";
    @ConfigProperty(name = "nps.private-key-path")
    String privateKeyPath;
    @ConfigProperty(name = "nps.nibss-public-key-path")
    String nibssPublicKeyPath;
    @ConfigProperty(name = "nps.certificate-path")
    String certificatePath;
    private PrivateKey privateKey;
    private PublicKey nibssPublicKey;

    @PostConstruct
    void init() {
        org.apache.xml.security.Init.init();
        reloadKeys();
    }

    public void reloadKeys() {
        try {
            privateKey = NpsPemKeys.privateKey(privateKeyPath);
            nibssPublicKey = NpsPemKeys.publicKey(nibssPublicKeyPath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load NPS encryption keys", e);
        }
    }

    public String encrypt(String xml) throws Exception {
        Document document = NpsXml.parse(xml);
        Element business = businessElement(document);
        if (document.getElementsByTagNameNS(XENC, "EncryptedData").getLength() != 0)
            throw new IllegalArgumentException("Message is already encrypted");
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        var sessionKey = generator.generateKey();
        XMLCipher wrapping = XMLCipher.getInstance(XMLCipher.RSA_OAEP);
        wrapping.init(XMLCipher.WRAP_MODE, nibssPublicKey);
        var wrappedKey = wrapping.encryptKey(document, sessionKey);
        XMLCipher cipher = XMLCipher.getInstance(XMLCipher.AES_256_GCM);
        cipher.init(XMLCipher.ENCRYPT_MODE, sessionKey);
        KeyInfo keyInfo = new KeyInfo(document);
        keyInfo.add(wrappedKey);
        cipher.getEncryptedData().setKeyInfo(keyInfo);
        // Content mode preserves Document, business element, and sibling Signature.
        cipher.doFinal(document, business, true);
        return serialize(document);
    }

    public String decrypt(String xml) throws Exception {
        Document document = NpsXml.parse(xml);
        var encrypted = document.getElementsByTagNameNS(XENC, "EncryptedData");
        if (encrypted.getLength() == 0)
            return xml;
        if (encrypted.getLength() != 1)
            throw new IllegalArgumentException("Expected one encrypted business payload");
        Element data = (Element) encrypted.item(0);
        if (data.getElementsByTagNameNS(XENC, "EncryptedKey").getLength() != 1
                || data.getElementsByTagNameNS(XENC, "CipherValue").getLength() != 2
                || data.getElementsByTagNameNS(XENC, "CipherReference").getLength() != 0
                || data.getElementsByTagNameNS(DSIG, "RetrievalMethod").getLength() != 0)
            throw new IllegalArgumentException("Expected inline encrypted key and payload values");
        Element business = businessElement(document);
        if (data.getParentNode() != business)
            throw new IllegalArgumentException("EncryptedData must be inside the business element");
        if (!(XENC + "Content").equals(data.getAttribute("Type")))
            throw new IllegalArgumentException("Expected XML Encryption content mode");
        var methods = data.getElementsByTagNameNS(XENC, "EncryptionMethod");
        if (methods.getLength() != 2
                || !XMLCipher.AES_256_GCM.equals(((Element) methods.item(0)).getAttribute("Algorithm"))
                || !XMLCipher.RSA_OAEP.equals(((Element) methods.item(1)).getAttribute("Algorithm")))
            throw new IllegalArgumentException("Unsupported NPS encryption algorithms");
        // An omitted DigestMethod means SHA-1 for rsa-oaep-mgf1p, as in the endpoint
        // samples.
        var digests = data.getElementsByTagNameNS(DSIG, "DigestMethod");
        if (digests.getLength() > 1 || (digests.getLength() == 1
                && !(DSIG + "sha1").equals(((Element) digests.item(0)).getAttribute("Algorithm"))))
            throw new IllegalArgumentException("Unsupported OAEP digest");
        XMLCipher cipher = XMLCipher.getInstance();
        cipher.init(XMLCipher.DECRYPT_MODE, null);
        cipher.setKEK(privateKey);
        cipher.doFinal(document, data);
        return serialize(document);
    }

    private Element businessElement(Document document) {
        Element container = document.getDocumentElement();
        if ("BizMsg".equals(container.getLocalName()) || "BizMsg".equals(container.getNodeName())) {
            Element payload = null;
            for (Node child = container.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element e && "Document".equals(e.getLocalName())) {
                    if (payload != null)
                        throw new IllegalArgumentException("Multiple Documents");
                    payload = e;
                }
            }
            if (payload == null)
                throw new IllegalArgumentException("Missing Document");
            container = payload;
        }
        if (!"Document".equals(container.getLocalName()) && !"Document".equals(container.getNodeName()))
            throw new IllegalArgumentException("Expected Document container");
        Element business = null;
        for (Node child = container.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && !DSIG.equals(element.getNamespaceURI())) {
                if (business != null)
                    throw new IllegalArgumentException("Multiple business elements");
                business = element;
            }
        }
        if (business == null)
            throw new IllegalArgumentException("Missing business element");
        return business;
    }

    private String serialize(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter output = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toString();
    }
}
