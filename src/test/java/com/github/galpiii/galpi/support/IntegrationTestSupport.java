package com.github.galpiii.galpi.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 스프링 컨텍스트를 띄우는 테스트의 공통 기반.
 *
 * <p>설정 값은 {@code src/test/resources/application-test.yml}에서 온다. 다만 GitHub App
 * 개인키만은 파일에 둘 수 없다. {@code GithubAppJwtGenerator}가 생성자에서 PEM을 파싱하므로 형식이
 * 맞는 실제 RSA 키가 필요한데, 저장소에 개인키를 커밋하면 시크릿 스캐너에 걸린다. 그래서 매 실행마다
 * 새 키쌍을 만들어 주입한다. 이 키로 서명한 App JWT는 GitHub에 보내지지 않는다.
 *
 * <p>DB와 Redis에는 접속하지 않는다. Flyway를 끄고 Hibernate의 JDBC 메타데이터 조회를 막아 두었다.
 * 실제 스키마까지 검증하려면 Testcontainers를 붙이면 된다.
 */
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
