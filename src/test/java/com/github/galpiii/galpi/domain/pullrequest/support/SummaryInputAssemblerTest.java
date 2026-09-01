package com.github.galpiii.galpi.domain.pullrequest.support;

import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.entity.PullRequestCommit;
import com.github.galpiii.galpi.domain.collection.secret.SecretContentScanner;
import com.github.galpiii.galpi.domain.collection.secret.SecretPathRules;
import com.github.galpiii.galpi.domain.github.client.dto.GithubPullRequestFileResponse;
import com.github.galpiii.galpi.domain.pullrequest.config.SummaryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 요약 입력 조립.
 *
 * <p>여기서 확인해야 할 것 중 하나는 보안 요구다. patch는 저장하지 않으므로 요약 직전에
 * GitHub에서 새로 받는데, 그 값은 <b>아무 검사도 거치지 않은 원문</b>이다. 수집 시점에 걸러
 * 둔 제목·본문·커밋 메시지와 다르다. 이 조립 단계가 마스킹하지 않으면 비공개 저장소의
 * 자격증명이 그대로 외부 LLM으로 나간다.
 */
@DisplayName("SummaryInputAssembler — 요약 입력 조립")
class SummaryInputAssemblerTest {

    private static final String FAKE_GITHUB_TOKEN = "ghp_1234567890abcdefGHIJKLMNOPqrstuvwx12";

    private final SecretContentScanner scanner = new SecretContentScanner();

    @Test
    @DisplayName("저장소·PR·커밋·변경 파일·diff를 순서대로 담는다")
    void assemblesAllSections() {
        String input = assembler(60000, 8000).assemble("sample-org/backend",
                pullRequest(42, "feat: 회원가입", "이메일 가입을 추가합니다."),
                List.of(commit("a3f9c21abcdef", "feat: 회원가입 API 구현\n\n본문은 버린다")),
                List.of(file("src/AuthController.java", "@@ -0,0 +1,3 @@\n+class A {}")));

        assertThat(input)
                .contains("저장소: sample-org/backend")
                .contains("PR #42: feat: 회원가입")
                .contains("본문: 이메일 가입을 추가합니다.")
                .contains("- a3f9c21 feat: 회원가입 API 구현")
                .contains("- src/AuthController.java")
                .contains("+class A {}");
        // 커밋 본문까지 넣으면 커밋 하나가 diff 예산만큼 자리를 차지한다.
        assertThat(input).doesNotContain("본문은 버린다");
    }

    @Test
    @DisplayName("본문이 비어 있어도 조립된다")
    void handlesEmptyBody() {
        String input = assembler(60000, 8000).assemble("sample-org/backend",
                pullRequest(42, "feat: 회원가입", null), List.of(), List.of());

        assertThat(input).contains("본문: (없음)").contains("커밋:\n  (없음)");
    }

    @Nested
    @DisplayName("비밀정보")
    class Secrets {

        @Test
        @DisplayName("다시 받은 diff의 자격증명을 마스킹한다")
        void masksSecretsInPatch() {
            String patch = "@@ -1 +1 @@\n+const token = '" + FAKE_GITHUB_TOKEN + "';";

            String input = assembler(60000, 8000).assemble("sample-org/backend",
                    pullRequest(42, "feat: 설정", "본문"), List.of(),
                    List.of(file("src/config.ts", patch)));

            // 수집 시점에 걸러 둔 다른 필드와 달리 patch는 검사를 거치지 않은 원문으로 온다.
            assertThat(input).doesNotContain(FAKE_GITHUB_TOKEN).contains("***");
        }

        @Test
        @DisplayName("비밀정보 의심 경로는 이름과 patch를 모두 외부 입력에서 제외한다")
        void excludesSecretPaths() {
            String input = assembler(60000, 8000).assemble("sample-org/backend",
                    pullRequest(42, "feat: 설정", "본문"), List.of(),
                    List.of(file(".env", "+UNRECOGNIZED_SECRET=value"),
                            file("src/App.java", "+class App {}")));

            assertThat(input)
                    .doesNotContain(".env", "UNRECOGNIZED_SECRET")
                    .contains("비밀정보 의심 경로 1개 제외됨", "src/App.java");
        }
    }

    @Nested
    @DisplayName("절단")
    class Truncation {

        @Test
        @DisplayName("파일당 상한을 넘으면 잘라 내고 잘렸다고 알린다")
        void truncatesPerFile() {
            String patch = "@@ -1 +1 @@\n" + "+line\n".repeat(200);

            String input = assembler(60000, 100).assemble("sample-org/backend",
                    pullRequest(42, "feat: 대량 변경", "본문"), List.of(),
                    List.of(file("src/Big.java", patch)));

            assertThat(input).contains("... (이하 생략)");
            assertThat(input.length()).isLessThan(patch.length());
        }

        @Test
        @DisplayName("PR 전체 상한을 넘으면 나머지 파일은 이름만 남긴다")
        void truncatesAcrossFiles() {
            String patch = "@@ -1 +1 @@\n" + "+line\n".repeat(50);

            String input = assembler(120, 120).assemble("sample-org/backend",
                    pullRequest(42, "feat: 대량 변경", "본문"), List.of(),
                    List.of(file("src/AuthController.java", patch),
                            file("src/AuthService.java", patch),
                            file("src/AuthRepository.java", patch)));

            assertThat(input).contains("(diff 생략됨)");
            assertThat(input).contains("의 diff는 상한 때문에 담지 않았다");
            // 파일이 존재했다는 사실은 남는다. 프롬프트가 보이지 않는 부분을 추측하지 말라고 한다.
            assertThat(input).contains("src/AuthRepository.java");
        }

        @Test
        @DisplayName("우선순위가 높은 파일부터 담는다")
        void ordersByPriority() {
            String patch = "@@ -1 +1 @@\n+line";

            String input = assembler(60000, 8000).assemble("sample-org/backend",
                    pullRequest(42, "feat: 회원가입", "본문"), List.of(),
                    List.of(file("README.md", patch),
                            file("src/test/AuthTest.java", patch),
                            file("src/AuthController.java", patch)));

            // 진입점이 무엇이 바뀌었는지를 가장 직접적으로 보여 준다. 문서는 그 주장이다.
            assertThat(input.indexOf("--- src/AuthController.java"))
                    .isLessThan(input.indexOf("--- src/test/AuthTest.java"));
            assertThat(input.indexOf("--- src/test/AuthTest.java"))
                    .isLessThan(input.indexOf("--- README.md"));
        }

        @Test
        @DisplayName("GitHub이 생략한 파일은 생략됐다고만 적는다")
        void marksOmittedPatch() {
            String input = assembler(60000, 8000).assemble("sample-org/backend",
                    pullRequest(42, "feat: 이미지 추가", "본문"), List.of(),
                    List.of(file("assets/logo.png", null)));

            assertThat(input).contains("--- assets/logo.png").contains("(diff 생략됨)");
        }

        @Test
        @DisplayName("본문·커밋·파일 목록까지 포함한 최종 입력 상한을 지킨다")
        void capsWholeInput() {
            String input = assembler(60000, 8000, 500).assemble("sample-org/backend",
                    pullRequest(42, "제목".repeat(100), "본문".repeat(1000)),
                    List.of(commit("a3f9c21", "커밋".repeat(1000))),
                    List.of(file("src/VeryLarge.java", "+line\n".repeat(1000))));

            assertThat(input).hasSizeLessThanOrEqualTo(500)
                    .contains("입력 상한");
        }
    }

    private SummaryInputAssembler assembler(int maxPatchChars, int maxPatchCharsPerFile) {
        return assembler(maxPatchChars, maxPatchCharsPerFile, 90000);
    }

    private SummaryInputAssembler assembler(int maxPatchChars, int maxPatchCharsPerFile,
                                            int maxInputChars) {
        return new SummaryInputAssembler(scanner, new SecretPathRules(), new SummaryProperties(
                new SummaryProperties.Worker(true, Duration.ofSeconds(5), Duration.ofMinutes(10),
                        Duration.ofSeconds(5), 3, 10, 4),
                maxPatchChars, maxPatchCharsPerFile, maxInputChars, 2));
    }

    private static PullRequest pullRequest(int number, String title, String body) {
        PullRequest pullRequest = mock(PullRequest.class);
        given(pullRequest.getNumber()).willReturn(number);
        given(pullRequest.getTitle()).willReturn(title);
        given(pullRequest.getBody()).willReturn(body);
        return pullRequest;
    }

    private static PullRequestCommit commit(String sha, String message) {
        PullRequestCommit commit = mock(PullRequestCommit.class);
        given(commit.getSha()).willReturn(sha);
        given(commit.getMessage()).willReturn(message);
        return commit;
    }

    private static GithubPullRequestFileResponse file(String path, String patch) {
        return new GithubPullRequestFileResponse(path, null, "added", 10, 0, 10, patch);
    }
}
