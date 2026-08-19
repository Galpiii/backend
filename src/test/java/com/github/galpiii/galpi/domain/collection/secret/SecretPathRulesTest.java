package com.github.galpiii.galpi.domain.collection.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecretPathRules")
class SecretPathRulesTest {

    private final SecretPathRules rules = new SecretPathRules();

    @ParameterizedTest(name = "{0} 은 제외된다")
    @ValueSource(strings = {
            ".env",
            ".env.local",
            ".env.production",
            "config/.env",
            "app.env",
            "certs/server.pem",
            "keys/private.key",
            "cert.p12",
            "cert.pfx",
            "store.jks",
            "release.keystore",
            "id_rsa",
            "id_rsa.pub",
            ".ssh/id_ed25519",
            "infra/terraform.tfstate",
            "infra/terraform.tfstate.backup",
            "infra/prod.tfvars",
            "src/main/resources/application-secret.yml",
            "src/main/resources/application-prod.yaml",
            "config/db-secret.yml",
            "config/db-secrets.yaml",
            "service-account.json",
            "service-account-prod.json",
            "gcp-credentials.json",
            "firebase-adminsdk-abc123.json",
            "cluster.kubeconfig",
            "kubeconfig"
    })
    @DisplayName("§5.2(a)의 파일명 규칙에 걸리는 경로를 제외한다")
    void excludesSecretFileNames(String path) {
        assertThat(rules.isSecretPath(path)).isTrue();
    }

    @ParameterizedTest(name = "{0} 은 제외된다")
    @ValueSource(strings = {
            "secrets/db.yml",
            "config/secrets/db.yml",
            "credentials/aws.txt",
            "deep/nested/credentials/token.txt",
            ".aws/config",
            "home/.aws/credentials",
            ".ssh/known_hosts"
    })
    @DisplayName("경로 세그먼트가 비밀 디렉터리면 깊이와 무관하게 제외한다")
    void excludesSecretDirectoriesAtAnyDepth(String path) {
        assertThat(rules.isSecretPath(path)).isTrue();
    }

    @ParameterizedTest(name = "{0} 은 남는다")
    @ValueSource(strings = {
            "src/main/java/com/example/App.java",
            "README.md",
            "package.json",
            "build.gradle",
            "src/main/resources/application.yml",
            "docs/environment.md",
            "src/keyboard/Shortcut.tsx"
    })
    @DisplayName("일반 소스와 설정 파일은 제외하지 않는다")
    void keepsOrdinaryFiles(String path) {
        assertThat(rules.isSecretPath(path)).isFalse();
    }

    @Test
    @DisplayName("대소문자가 달라도 파일명 규칙이 적용된다")
    void matchesFileNameCaseInsensitively() {
        assertThat(rules.isSecretPath("config/.ENV")).isTrue();
        assertThat(rules.isSecretPath("keys/Server.PEM")).isTrue();
        assertThat(rules.isSecretPath("Id_RSA")).isTrue();
    }

    @Test
    @DisplayName("디렉터리 이름과 같은 파일명은 제외하지 않는다")
    void doesNotExcludeFileNamedLikeSecretDirectory() {
        // 마지막 세그먼트는 파일명이라 디렉터리 규칙을 적용하지 않는다.
        assertThat(rules.isSecretPath("docs/secrets")).isFalse();
    }

    @Test
    @DisplayName("null과 빈 경로는 제외 대상이 아니다")
    void handlesNullAndBlank() {
        assertThat(rules.isSecretPath(null)).isFalse();
        assertThat(rules.isSecretPath("")).isFalse();
        assertThat(rules.isSecretPath("   ")).isFalse();
    }
}
