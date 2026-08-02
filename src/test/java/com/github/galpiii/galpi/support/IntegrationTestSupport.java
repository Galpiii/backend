package com.github.galpiii.galpi.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    @DynamicPropertySource
    static void githubPrivateKey(DynamicPropertyRegistry registry) {
        registry.add("galpi.github.private-key", IntegrationTestSupport::generateRsaPrivateKeyPem);
    }

    private static String generateRsaPrivateKeyPem() {
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
