package com.provider.nps.crypto;

import java.io.FileInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * NPS XML Encryption utility.
 * Implements AES-256-GCM content encryption with RSA-OAEP key wrapping as per NPS spec.
 * 
 * Uses W3C XML Encryption standard (xenc namespace).
 */
@ApplicationScoped
public class NpsXmlEncryptor {

    private static final String XENC_NS = "http://www.w3.org/2001/04/xmlenc#";
    private static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";
    private static final int GCM_IV_LENGTH = 12; // 96 bits
    private static final int GCM_TAG_LENGTH = 128; // 128 bits

    @ConfigProperty(name = "nps.private-key-path")
    String privateKeyPath;

    @ConfigProperty(name = "nps.nibss-public-key-path")
    String nibssPublicKeyPath;

    @ConfigProperty(name = "nps.certificate-path")
    String certificatePath;

    private PrivateKey privateKey;
    private PublicKey nibssPublicKey;
    private X509Certificate certificate;
    private DocumentBuilderFactory documentBuilderFactory;
    private SecureRandom secureRandom;

    @PostConstruct
    void init() {
        try {
            documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setNamespaceAware(true);
            secureRandom = new SecureRandom();

            loadKeys();
            Log.info("NpsXmlEncryptor initialized successfully");
        } catch (Exception e) {
            Log.error("Failed to initialize NpsXmlEncryptor", e);
            throw new RuntimeException("Failed to initialize NpsXmlEncryptor", e);
        }
    }

    private void loadKeys() throws Exception {
        privateKey = loadPrivateKey(privateKeyPath);
        nibssPublicKey = loadPublicKeyFromCert(nibssPublicKeyPath);
        certificate = loadCertificate(certificatePath);
    }

    private PrivateKey loadPrivateKey(String path) throws Exception {
        String pem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(keySpec);
    }

    private PublicKey loadPublicKeyFromCert(String path) throws Exception {
        // Try loading as certificate first
        try (FileInputStream fis = new FileInputStream(path)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(fis);
            return cert.getPublicKey();
        } catch (Exception e) {
            // Fall back to loading as raw public key
            String pem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
            String base64 = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");

            byte[] keyBytes = Base64.getDecoder().decode(base64);
            java.security.spec.X509EncodedKeySpec keySpec = new java.security.spec.X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(keySpec);
        }
    }

    private X509Certificate loadCertificate(String path) throws Exception {
        try (FileInputStream fis = new FileInputStream(path)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(fis);
        }
    }

    /**
     * Encrypts the Document element of an ISO 20022 message.
     * Uses AES-256-GCM for content and RSA-OAEP for key wrapping.
     * 
     * @param xml The signed XML message to encrypt
     * @return The encrypted XML message with EncryptedData structure
     */
    public String encrypt(String xml) throws Exception {
        DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        // Find the Document element (the business message payload)
        NodeList docList = document.getElementsByTagNameNS("*", "Document");
        if (docList.getLength() == 0) {
            throw new IllegalArgumentException("XML does not contain Document element");
        }
        Element docElement = (Element) docList.item(0);

        // Serialize Document element to encrypt
        String docXml = elementToString(docElement);
        byte[] plaintext = docXml.getBytes("UTF-8");

        // Generate random AES-256 key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, secureRandom);
        SecretKey aesKey = keyGen.generateKey();

        // Generate random IV for GCM
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        // Encrypt content with AES-256-GCM
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
        byte[] ciphertext = aesCipher.doFinal(plaintext);

        // Combine IV + ciphertext (NPS expects IV prepended)
        byte[] ivAndCiphertext = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.length);
        System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.length, ciphertext.length);

        // Wrap AES key with RSA-OAEP using NIBSS public key
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.WRAP_MODE, nibssPublicKey);
        byte[] wrappedKey = rsaCipher.wrap(aesKey);

        // Build EncryptedData XML structure
        Element encryptedData = buildEncryptedDataElement(document, ivAndCiphertext, wrappedKey);

        // Replace Document element with EncryptedData
        docElement.getParentNode().replaceChild(encryptedData, docElement);

        return documentToString(document);
    }

    private Element buildEncryptedDataElement(Document doc, byte[] encryptedContent, byte[] wrappedKey) {
        // Create EncryptedData root
        Element encryptedData = doc.createElementNS(XENC_NS, "xenc:EncryptedData");
        encryptedData.setAttribute("Type", "http://www.w3.org/2001/04/xmlenc#Content");

        // EncryptionMethod (AES-256-GCM)
        Element encMethod = doc.createElementNS(XENC_NS, "xenc:EncryptionMethod");
        encMethod.setAttribute("Algorithm", "http://www.w3.org/2009/xmlenc11#aes256-gcm");
        encryptedData.appendChild(encMethod);

        // KeyInfo with EncryptedKey
        Element keyInfo = doc.createElementNS(DSIG_NS, "ds:KeyInfo");

        Element encryptedKey = doc.createElementNS(XENC_NS, "xenc:EncryptedKey");

        // Key encryption method (RSA-OAEP)
        Element keyEncMethod = doc.createElementNS(XENC_NS, "xenc:EncryptionMethod");
        keyEncMethod.setAttribute("Algorithm", "http://www.w3.org/2001/04/xmlenc#rsa-oaep-mgf1p");

        // DigestMethod for OAEP
        Element digestMethod = doc.createElementNS(DSIG_NS, "ds:DigestMethod");
        digestMethod.setAttribute("Algorithm", "http://www.w3.org/2001/04/xmlenc#sha256");
        keyEncMethod.appendChild(digestMethod);

        encryptedKey.appendChild(keyEncMethod);

        // CipherData for wrapped key
        Element keyCipherData = doc.createElementNS(XENC_NS, "xenc:CipherData");
        Element keyCipherValue = doc.createElementNS(XENC_NS, "xenc:CipherValue");
        keyCipherValue.setTextContent(Base64.getEncoder().encodeToString(wrappedKey));
        keyCipherData.appendChild(keyCipherValue);
        encryptedKey.appendChild(keyCipherData);

        keyInfo.appendChild(encryptedKey);
        encryptedData.appendChild(keyInfo);

        // CipherData for encrypted content
        Element cipherData = doc.createElementNS(XENC_NS, "xenc:CipherData");
        Element cipherValue = doc.createElementNS(XENC_NS, "xenc:CipherValue");
        cipherValue.setTextContent(Base64.getEncoder().encodeToString(encryptedContent));
        cipherData.appendChild(cipherValue);
        encryptedData.appendChild(cipherData);

        return encryptedData;
    }

    /**
     * Decrypts an incoming NPS message containing EncryptedData.
     * 
     * @param xml The encrypted XML message
     * @return The decrypted XML message with Document element restored
     */
    public String decrypt(String xml) throws Exception {
        DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        // Find EncryptedData element
        NodeList encDataList = document.getElementsByTagNameNS(XENC_NS, "EncryptedData");
        if (encDataList.getLength() == 0) {
            // No encryption, return as-is
            Log.debug("No EncryptedData found, returning original XML");
            return xml;
        }

        Element encryptedData = (Element) encDataList.item(0);

        // Extract wrapped key
        NodeList keyCipherList = encryptedData.getElementsByTagNameNS(XENC_NS, "EncryptedKey");
        if (keyCipherList.getLength() == 0) {
            throw new IllegalArgumentException("EncryptedData does not contain EncryptedKey");
        }

        Element encryptedKey = (Element) keyCipherList.item(0);
        NodeList keyValueList = encryptedKey.getElementsByTagNameNS(XENC_NS, "CipherValue");
        String wrappedKeyBase64 = keyValueList.item(0).getTextContent().trim();
        byte[] wrappedKey = Base64.getDecoder().decode(wrappedKeyBase64);

        // Unwrap AES key using our private key
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.UNWRAP_MODE, privateKey);
        SecretKey aesKey = (SecretKey) rsaCipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY);

        // Extract encrypted content
        NodeList cipherDataList = encryptedData.getElementsByTagNameNS(XENC_NS, "CipherData");
        // Get the second CipherValue (first is for key, second is for content)
        NodeList cipherValueList = encryptedData.getElementsByTagNameNS(XENC_NS, "CipherValue");
        String encryptedContentBase64 = cipherValueList.item(cipherValueList.getLength() - 1).getTextContent().trim();
        byte[] ivAndCiphertext = Base64.getDecoder().decode(encryptedContentBase64);

        // Split IV and ciphertext
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[ivAndCiphertext.length - GCM_IV_LENGTH];
        System.arraycopy(ivAndCiphertext, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(ivAndCiphertext, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

        // Decrypt with AES-256-GCM
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);
        byte[] plaintext = aesCipher.doFinal(ciphertext);

        // Parse decrypted Document element
        String decryptedDocXml = new String(plaintext, "UTF-8");
        Document decryptedDoc = builder.parse(new InputSource(new StringReader(decryptedDocXml)));
        Element decryptedElement = decryptedDoc.getDocumentElement();

        // Import and replace EncryptedData with decrypted Document
        Element importedElement = (Element) document.importNode(decryptedElement, true);
        encryptedData.getParentNode().replaceChild(importedElement, encryptedData);

        return documentToString(document);
    }

    private String elementToString(Element element) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(element), new StreamResult(writer));
        return writer.toString();
    }

    private String documentToString(Document document) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    /**
     * Reloads keys from disk. Call after key rotation.
     */
    public void reloadKeys() {
        try {
            loadKeys();
            Log.info("NPS encryption keys reloaded successfully");
        } catch (Exception e) {
            Log.error("Failed to reload NPS encryption keys", e);
            throw new RuntimeException("Failed to reload NPS encryption keys", e);
        }
    }
}
