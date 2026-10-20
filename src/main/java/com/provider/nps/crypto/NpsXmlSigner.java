package com.provider.nps.crypto;

import com.provider.nps.xml.NpsXml;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
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

/**
 * NPS XML Digital Signature utility. Implements W3C XMLDSIG with RSA-SHA256 as
 * per NPS integration spec.
 * 
 * Signature is enveloped within the Document element.
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
            documentBuilderFactory = NpsXml.newDocumentBuilderFactory();

            loadKeys();
            Log.info("NpsXmlSigner initialized successfully");
        } catch (Exception e) {
            Log.error("Failed to initialize NpsXmlSigner", e);
            throw new RuntimeException("Failed to initialize NpsXmlSigner", e);
        }
    }

    private void loadKeys() throws Exception {
        // Load private key (PEM format)
        privateKey = NpsPemKeys.privateKey(privateKeyPath);

        // Load certificate (PEM format)
        certificate = NpsPemKeys.certificate(certificatePath);

        // Load NIBSS public key for signature verification
        nibssPublicKey = NpsPemKeys.publicKey(nibssPublicKeyPath);
    }

    /**
     * Signs an ISO 20022 XML message with enveloped W3C XMLDSIG signature. The
     * signature is placed in Document as per NPS spec.
     * 
     * @param xml
     *            The unsigned XML message
     * @return The signed XML message
     */
    public String sign(String xml) throws Exception {
        DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        Element root = document.getDocumentElement();
        if (!"Document".equals(root.getLocalName()) && !"BizMsg".equals(root.getLocalName())) {
            throw new IllegalArgumentException("Expected ISO 20022 Document or legacy BizMsg root");
        }

        // Create signature reference (signs entire document with enveloped transform)
        List<Transform> transforms = new ArrayList<>();
        transforms.add(signatureFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null));

        Reference reference = signatureFactory.newReference("", // Empty URI means sign entire document
                signatureFactory.newDigestMethod(DigestMethod.SHA256, null), transforms, null, null);

        // Create SignedInfo with RSA-SHA256
        SignedInfo signedInfo = signatureFactory.newSignedInfo(
                signatureFactory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                        (C14NMethodParameterSpec) null),
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
        DOMSignContext signContext = new DOMSignContext(privateKey, root);
        signature.sign(signContext);

        return documentToString(document);
    }

    /**
     * Verifies the signature on an incoming NPS XML message.
     * 
     * @param xml
     *            The signed XML message
     * @return true if signature is valid
     */
    public boolean verify(String xml) throws Exception {
        DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        // Find signature element
        NodeList signatureList = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (signatureList.getLength() != 1) {
            Log.warn("Expected one XML signature");
            return false;
        }

        Element signatureElement = (Element) signatureList.item(0);
        if (signatureElement.getParentNode() != document.getDocumentElement())
            return false;
        DOMValidateContext validateContext = new DOMValidateContext(nibssPublicKey, signatureElement);
        validateContext.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
        XMLSignature signature = signatureFactory.unmarshalXMLSignature(validateContext);
        SignedInfo signedInfo = signature.getSignedInfo();
        if (!SignatureMethod.RSA_SHA256.equals(signedInfo.getSignatureMethod().getAlgorithm())
                || !CanonicalizationMethod.EXCLUSIVE.equals(signedInfo.getCanonicalizationMethod().getAlgorithm())
                || signedInfo.getReferences().size() != 1)
            return false;
        Reference reference = (Reference) signedInfo.getReferences().get(0);
        if (!"".equals(reference.getURI()) || !DigestMethod.SHA256.equals(reference.getDigestMethod().getAlgorithm())
                || reference.getTransforms().size() != 1
                || !Transform.ENVELOPED.equals(((Transform) reference.getTransforms().get(0)).getAlgorithm()))
            return false;

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
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

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
