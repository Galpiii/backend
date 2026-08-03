package com.github.galpiii.galpi.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 테스트용 RSA 키. {@code GithubAppJwtGenerator}가 생성자에서 PEM을 파싱하므로 컨텍스트를 띄우려면
 * 진짜 키가 필요한데, 저장소에 개인키를 남기지 않으려고 실행 시점에 만들어 쓴다.
 */
public final class TestRsaKeys {

    private TestRsaKeys() {
    }

    public static String generatePrivateKeyPem() {
        KeyPair keyPair;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keyPair = generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("테스트용 RSA 키 생성에 실패했습니다.", e);
        }

        String encoded = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
    }
}
