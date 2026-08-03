package com.github.galpiii.galpi.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    @DynamicPropertySource
    static void githubPrivateKey(DynamicPropertyRegistry registry) {
        registry.add("galpi.github.private-key", TestRsaKeys::generatePrivateKeyPem);
    }
}
