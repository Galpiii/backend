package com.github.galpiii.galpi.domain.github.appjwt;

import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

final class PemPrivateKeyParser {

    private static final String PKCS1_HEADER = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----";

    private static final byte[] RSA_ALGORITHM_IDENTIFIER = {
            0x30, 0x0d,
            0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
    };

    private static final byte[] VERSION_ZERO = {0x02, 0x01, 0x00};

    private PemPrivateKeyParser() {
    }

    static RSAPrivateKey parse(String pem) {
        String normalized = pem.replace("\\n", "\n").trim();
        boolean pkcs1 = normalized.startsWith(PKCS1_HEADER);
        if (!pkcs1 && !normalized.startsWith(PKCS8_HEADER)) {
            throw new IllegalStateException(
                    "GitHub App 개인키 형식을 인식할 수 없습니다. PKCS#1 또는 PKCS#8 PEM이어야 합니다.");
        }

        byte[] der = Base64.getDecoder().decode(stripPemArmor(normalized));
        byte[] pkcs8 = pkcs1 ? wrapPkcs1AsPkcs8(der) : der;

        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "GitHub App 개인키를 읽지 못했습니다: " + e.getClass().getSimpleName());
        }
    }

    private static String stripPemArmor(String pem) {
        return pem.replaceAll("-----(BEGIN|END)[^-]*-----", "")
                .replaceAll("\\s", "");
    }

    private static byte[] wrapPkcs1AsPkcs8(byte[] pkcs1Der) {
        byte[] privateKeyOctetString = derTlv(0x04, pkcs1Der);

        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        contents.writeBytes(VERSION_ZERO);
        contents.writeBytes(RSA_ALGORITHM_IDENTIFIER);
        contents.writeBytes(privateKeyOctetString);

        return derTlv(0x30, contents.toByteArray());
    }

    private static byte[] derTlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        out.writeBytes(derLength(value.length));
        out.writeBytes(value);

        return out.toByteArray();
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }

        int byteCount = 0;
        for (int remaining = length; remaining > 0; remaining >>>= 8) {
            byteCount++;
        }

        byte[] encoded = new byte[byteCount + 1];
        encoded[0] = (byte) (0x80 | byteCount);
        for (int i = 0; i < byteCount; i++) {
            encoded[byteCount - i] = (byte) (length >>> (8 * i));
        }

        return encoded;
    }
}
