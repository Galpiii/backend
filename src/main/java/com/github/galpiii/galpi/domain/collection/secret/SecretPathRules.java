package com.github.galpiii.galpi.domain.collection.secret;

import org.springframework.stereotype.Component;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 경로만 보고 제외하는 규칙 (공통 문서 §5.2(a)).
 *
 * <p>내용 스캔보다 이쪽이 먼저다. {@code .env}나 개인키는 안을 들여다볼 필요 없이 이름만으로
 * 전송 대상이 아니고, 내용 스캔은 정규식이라 놓칠 수 있지만 이름 규칙은 놓치지 않는다.
 *
 * <p>규칙을 두 갈래로 나눴다. 파일명 규칙은 깊이와 무관하게 <b>파일명만</b> 본다 —
 * {@code *.pem}을 전체 경로에 대고 glob으로 맞추면 {@code a/b/c.pem}이 걸리지 않는다.
 * 디렉터리 규칙은 경로 세그먼트가 하나라도 일치하면 걸린다 — {@code **}{@code /secrets/**}를
 * 그대로 쓰면 저장소 루트의 {@code secrets/}가 앞에 붙일 세그먼트가 없어 빠져나간다.
 */
@Component
public class SecretPathRules {

    /**
     * 파일명 기준 제외 glob. 경로가 아니라 파일명 하나에만 맞춘다.
     *
     * <p>{@code *.key}는 소스에서 흔한 이름이 아니지만 {@code i18n} 산출물 같은 오탐이 있을 수
     * 있다. 그래도 §5.2가 고정 목록으로 지정했고, 방향은 "의심스러우면 뺀다"가 맞다.
     */
    private static final List<String> FILE_NAME_GLOBS = List.of(
            ".env", ".env.*", "*.env",
            "*.pem", "*.key", "*.p12", "*.pfx", "*.jks", "*.keystore",
            "id_rsa*", "id_dsa*", "id_ecdsa*", "id_ed25519*",
            "terraform.tfstate*", "*.tfvars",
            "application-secret.*", "application-prod.*", "*-secret.yml", "*-secrets.yaml",
            "service-account*.json", "gcp-*.json", "firebase-adminsdk*.json",
            "*.kubeconfig", "kubeconfig");

    /** 경로 세그먼트 중 하나라도 이 이름이면 제외한다. 깊이와 무관하다. */
    private static final Set<String> SECRET_DIRECTORIES =
            Set.of("secrets", "credentials", ".aws", ".ssh");

    private final List<PathMatcher> fileNameMatchers = FILE_NAME_GLOBS.stream()
            .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
            .toList();

    /**
     * @param relativePath 저장소 루트 기준 상대 경로. {@code /}로 구분한다
     */
    public boolean isSecretPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        String[] segments = normalized.split("/");

        for (int i = 0; i < segments.length - 1; i++) {
            if (SECRET_DIRECTORIES.contains(segments[i].toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return matchesFileName(segments[segments.length - 1]);
    }

    private boolean matchesFileName(String fileName) {
        if (fileName.isEmpty()) {
            return false;
        }
        // glob 매칭은 대소문자를 구분한다. 파일명을 소문자로 낮춰 .ENV / Id_Rsa 같은 변형도 잡는다.
        Path candidate = Path.of(fileName.toLowerCase(Locale.ROOT));
        return fileNameMatchers.stream().anyMatch(matcher -> matcher.matches(candidate));
    }
}
