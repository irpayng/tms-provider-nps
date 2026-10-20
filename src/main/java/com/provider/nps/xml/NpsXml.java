package com.provider.nps.xml;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Secure XML parsing and prefix-independent navigation of ISO 20022 messages.
 */
public final class NpsXml {
    private NpsXml() {
    }

    public static DocumentBuilderFactory newDocumentBuilderFactory() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory;
        } catch (Exception e) {
            throw new IllegalStateException("Secure XML parser configuration failed", e);
        }
    }

    public static Document parse(String xml) {
        try {
            return newDocumentBuilderFactory().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid NPS XML", e);
        }
    }

    /**
     * Select only a direct ISO Document, or the legacy BizMsg/Document envelope.
     */
    public static String businessPath(Document document, String message) {
        if (!message.matches("[A-Za-z][A-Za-z0-9]*"))
            throw new IllegalArgumentException("Invalid message name");
        Node root = document.getDocumentElement();
        String path;
        Node body;
        if ("Document".equals(root.getLocalName()) || "Document".equals(root.getNodeName())) {
            path = "Document/";
            body = root;
        } else if ("BizMsg".equals(root.getLocalName()) || "BizMsg".equals(root.getNodeName())) {
            path = "BizMsg/Document/";
            body = singleChild(root, "Document");
        } else {
            throw new IllegalArgumentException("Unsupported NPS XML envelope");
        }
        singleChild(body, message);
        return path + message + "/";
    }

    private static Node singleChild(Node parent, String name) {
        Node found = null;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && (name.equals(child.getLocalName()) || name.equals(child.getNodeName()))) {
                if (found != null)
                    throw new IllegalArgumentException("Multiple " + name + " elements");
                found = child;
            }
        }
        if (found == null)
            throw new IllegalArgumentException("Missing " + name + " element");
        return found;
    }

    /** Prefer the guide's IBAN representation, with legacy Othr/Id support. */
    public static String account(Node document, String accountPath) {
        String iban = text(document, accountPath + "Id/IBAN");
        return iban != null ? iban : text(document, accountPath + "Id/Othr/Id");
    }

    /** Paths contain element names, never user-supplied XPath expressions. */
    public static String text(Node node, String path) {
        StringBuilder expression = new StringBuilder(".");
        for (String name : path.split("/")) {
            if (!name.matches("[A-Za-z][A-Za-z0-9]*")) {
                throw new IllegalArgumentException("Invalid XML element path");
            }
            expression.append("/*[local-name()='").append(name).append("']");
        }
        try {
            String value = XPathFactory.newInstance().newXPath().evaluate(expression.toString(), node).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot read NPS XML field", e);
        }
    }

    public static String first(Node node, String elementName) {
        if (!elementName.matches("[A-Za-z][A-Za-z0-9]*")) {
            throw new IllegalArgumentException("Invalid XML element name");
        }
        try {
            String value = XPathFactory.newInstance().newXPath()
                    .evaluate("(.//*[local-name()='" + elementName + "'])[1]", node).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot read NPS XML field", e);
        }
    }
}
