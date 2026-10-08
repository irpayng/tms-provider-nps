package com.provider.nps.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the PEM formats supported by the service without changing key material.
 */
final class NpsPemKeys {
    // DER AlgorithmIdentifier for rsaEncryption, including its NULL parameter.
    private static final byte[] RSA_ALGORITHM = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};

    private NpsPemKeys() {
    }

    static PrivateKey privateKey(String path) throws Exception {
        String pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII);
        byte[] encoded;
        if (pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            byte[] pkcs1 = decode(pem, "RSA PRIVATE KEY");
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            content.write(new byte[]{0x02, 0x01, 0x00}); // PKCS#8 version 0.
            content.write(RSA_ALGORITHM);
            content.write(derValue(0x04, pkcs1));
            encoded = derValue(0x30, content.toByteArray());
        } else {
            encoded = decode(pem, "PRIVATE KEY");
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    static PublicKey publicKey(String path) throws Exception {
        String pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII);
        if (pem.contains("-----BEGIN CERTIFICATE-----")) {
            return certificate(path).getPublicKey();
        }
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(pem, "PUBLIC KEY")));
    }

    static X509Certificate certificate(String path) throws Exception {
        try (var input = Files.newInputStream(Path.of(path))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static byte[] decode(String pem, String label) {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!pem.contains(begin) || !pem.contains(end)) {
            throw new IllegalArgumentException("Expected PEM " + label);
        }
        return Base64.getDecoder()
                .decode(pem.substring(pem.indexOf(begin) + begin.length(), pem.indexOf(end)).replaceAll("\\s", ""));
    }

    private static byte[] derValue(int tag, byte[] content) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(tag);
        if (content.length < 128) {
            encoded.write(content.length);
        } else {
            int count = (Integer.SIZE - Integer.numberOfLeadingZeros(content.length) + 7) / 8;
            encoded.write(0x80 | count);
            for (int shift = (count - 1) * 8; shift >= 0; shift -= 8) {
                encoded.write(content.length >>> shift & 0xff);
            }
        }
        encoded.writeBytes(content);
        return encoded.toByteArray();
    }
}
