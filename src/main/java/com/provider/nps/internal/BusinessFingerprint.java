package com.provider.nps.internal;

import com.provider.nps.xml.NpsXml;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Prefix-independent fingerprint of the whole business payload, excluding
 * signature and formatting whitespace.
 */
final class BusinessFingerprint {
    private BusinessFingerprint() {
    }
    static String of(Document document, String message) {
        NpsXml.businessPath(document, message);
        Element container = document.getDocumentElement();
        if ("BizMsg".equals(local(container)))
            container = child(container, "Document");
        Element business = child(container, message);
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            token(output, container.getNamespaceURI());
            write(business, output);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private static void write(Element element, DataOutputStream output) throws java.io.IOException {
        output.writeByte(1);
        token(output, element.getNamespaceURI());
        token(output, local(element));
        var attributes = new ArrayList<Node>();
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attribute = element.getAttributes().item(i);
            if (!"http://www.w3.org/2000/xmlns/".equals(attribute.getNamespaceURI()))
                attributes.add(attribute);
        }
        attributes.sort(Comparator.comparing((Node node) -> nullSafe(node.getNamespaceURI()))
                .thenComparing(BusinessFingerprint::local));
        output.writeInt(attributes.size());
        for (Node attribute : attributes) {
            token(output, attribute.getNamespaceURI());
            token(output, local(attribute));
            token(output, attribute.getNodeValue());
        }
        StringBuilder text = new StringBuilder();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                flush(text, output);
                write(child, output);
            } else if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                text.append(node.getNodeValue());
        }
        flush(text, output);
        output.writeByte(2);
    }
    private static void flush(StringBuilder text, DataOutputStream output) throws java.io.IOException {
        if (!text.toString().isBlank()) {
            output.writeByte(3);
            token(output, text.toString());
        }
        text.setLength(0);
    }
    private static void token(DataOutputStream output, String value) throws java.io.IOException {
        byte[] bytes = nullSafe(value).getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && name.equals(local(element)))
                return element;
        throw new IllegalArgumentException("Missing business payload");
    }
    private static String local(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }
    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
