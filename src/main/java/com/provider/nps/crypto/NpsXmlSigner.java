package com.provider.nps.crypto;

import java.io.FileInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
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
 * NPS XML Digital Signature utility.
 * Implements W3C XMLDSIG with RSA-SHA256 as per NPS integration spec.
 * 
 * Signature is enveloped within the AppHdr/Sgntr element.
 */
@ApplicationScoped
public class NpsXmlSigner {

    @ConfigProperty(name = "nps.private-key-path")
    String privateKeyPath;

    @ConfigProperty(name = "nps.certificate-path")
    String certificatePath;

    @ConfigProperty(name = "nps.nibss-public-key-path")
    String nibssPublicKeyPath;

    private PrivateKey privateKey;
    private X509Certificate certificate;
    private PublicKey nibssPublicKey;
    private XMLSignatureFactory signatureFactory;
    private DocumentBuilderFactory documentBuilderFactory;

    @PostConstruct
    void init() {
        try {
            signatureFactory = XMLSignatureFactory.getInstance("DOM");
            documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setNamespaceAware(true);

            loadKeys();
            Log.info("NpsXmlSigner initialized successfully");
        } catch (Exception e) {
            Log.error("Failed to initialize NpsXmlSigner", e);
            throw new RuntimeException("Failed to initialize NpsXmlSigner", e);
        }
    }

    private void loadKeys() throws Exception {
        // Load private key (PEM format)
        privateKey = loadPrivateKey(privateKeyPath);

        // Load certificate (PEM format)
        certificate = loadCertificate(certificatePath);

        // Load NIBSS public key for signature verification
        nibssPublicKey = loadPublicKey(nibssPublicKeyPath);
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

    private PublicKey loadPublicKey(String path) throws Exception {
        String pem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(keySpec);
    }

    private X509Certificate loadCertificate(String path) throws Exception {
        try (FileInputStream fis = new FileInputStream(path)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(fis);
        }
    }

    /**
     * Signs an ISO 20022 XML message with enveloped W3C XMLDSIG signature.
     * The signature is placed in AppHdr/Sgntr as per NPS spec.
     * 
     * @param xml The unsigned XML message
     * @return The signed XML message
     */
    public String sign(String xml) throws Exception {
        DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        // Find the AppHdr element to add signature
        NodeList appHdrList = document.getElementsByTagNameNS("*", "AppHdr");
        if (appHdrList.getLength() == 0) {
            throw new IllegalArgumentException("XML does not contain AppHdr element");
        }
        Element appHdr = (Element) appHdrList.item(0);

        // Create or find Sgntr element
        Element sgntr = findOrCreateSgntrElement(document, appHdr);

        // Create signature reference (signs entire document with enveloped transform)
        List<Transform> transforms = new ArrayList<>();
        transforms.add(signatureFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null));
        transforms.add(signatureFactory.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null));

        Reference reference = signatureFactory.newReference(
                "", // Empty URI means sign entire document
                signatureFactory.newDigestMethod(DigestMethod.SHA256, null),
                transforms,
                null,
                null);

        // Create SignedInfo with RSA-SHA256
        SignedInfo signedInfo = signatureFactory.newSignedInfo(
                signatureFactory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                signatureFactory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                Collections.singletonList(reference));

        // Create KeyInfo with X509 certificate data
        KeyInfoFactory keyInfoFactory = signatureFactory.getKeyInfoFactory();
        List<Object> x509Content = new ArrayList<>();
        x509Content.add(certificate.getSubjectX500Principal().getName());
        x509Content.add(certificate);
        X509Data x509Data = keyInfoFactory.newX509Data(x509Content);
        KeyInfo keyInfo = keyInfoFactory.newKeyInfo(Collections.singletonList(x509Data));

        // Create and sign
        XMLSignature signature = signatureFactory.newXMLSignature(signedInfo, keyInfo);
        DOMSignContext signContext = new DOMSignContext(privateKey, sgntr);
        signature.sign(signContext);

        return documentToString(document);
    }

    private Element findOrCreateSgntrElement(Document document, Element appHdr) {
        NodeList sgntrList = appHdr.getElementsByTagNameNS("*", "Sgntr");
        if (sgntrList.getLength() > 0) {
            return (Element) sgntrList.item(0);
        }

        // Create Sgntr element with proper namespace
        String namespace = appHdr.getNamespaceURI();
        Element sgntr = document.createElementNS(namespace, "Sgntr");
        appHdr.appendChild(sgntr);
        return sgntr;
    }

    /**
     * Verifies the signature on an incoming NPS XML message.
     * 
     * @param xml The signed XML message
     * @return true if signature is valid
     */
    public boolean verify(String xml) throws Exception {
        DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        // Find signature element
        NodeList signatureList = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (signatureList.getLength() == 0) {
            Log.warn("No signature found in XML message");
            return false;
        }

        Element signatureElement = (Element) signatureList.item(0);
        DOMValidateContext validateContext = new DOMValidateContext(nibssPublicKey, signatureElement);
        XMLSignature signature = signatureFactory.unmarshalXMLSignature(validateContext);

        boolean valid = signature.validate(validateContext);
        if (!valid) {
            Log.warn("XML signature validation failed");
            // Log detailed validation status for debugging
            boolean signatureValid = signature.getSignatureValue().validate(validateContext);
            Log.debugf("Signature value valid: %s", signatureValid);
            for (Object ref : signature.getSignedInfo().getReferences()) {
                Reference r = (Reference) ref;
                boolean refValid = r.validate(validateContext);
                Log.debugf("Reference '%s' valid: %s", r.getURI(), refValid);
            }
        }

        return valid;
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
            Log.info("NPS keys reloaded successfully");
        } catch (Exception e) {
            Log.error("Failed to reload NPS keys", e);
            throw new RuntimeException("Failed to reload NPS keys", e);
        }
    }
}
