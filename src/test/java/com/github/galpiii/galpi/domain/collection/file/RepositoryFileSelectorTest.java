package com.github.galpiii.galpi.domain.collection.file;

import com.github.galpiii.galpi.domain.collection.archive.ExtractedRepository;
import com.github.galpiii.galpi.domain.collection.config.CollectionProperties;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.CollectedFile;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.ExcludedFile;
import com.github.galpiii.galpi.domain.collection.pipeline.ExclusionReason;
import com.github.galpiii.galpi.domain.collection.secret.SecretContentScanner;
import com.github.galpiii.galpi.domain.collection.secret.SecretPathRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RepositoryFileSelector — 파일 선별")
class RepositoryFileSelectorTest {

    private static final String FAKE_GITHUB_TOKEN = "ghp_1234567890abcdefGHIJKLMNOPqrstuvwx12";

    @TempDir
    Path repository;

    @Nested
    @DisplayName("제외 규칙")
    class Exclusions {

        @Test
        @DisplayName(".env와 개인키를 전송 대상에서 뺀다")
        void excludesSecretPaths() throws IOException {
            write("src/App.java", "class App {}");
            write(".env", "DATABASE_URL=postgres://localhost/app");
            write("certs/server.pem", "-----BEGIN RSA PRIVATE KEY-----");

            FileSelectionResult result = select();

            assertThat(collectedPaths(result)).containsExactly("src/App.java");
            assertThat(reasonOf(result, ".env")).isEqualTo(ExclusionReason.SECRET_SUSPECTED);
            assertThat(reasonOf(result, "certs/server.pem"))
                    .isEqualTo(ExclusionReason.SECRET_SUSPECTED);
        }

        @Test
        @DisplayName("파일 내용에 심은 가짜 GitHub 토큰을 보고 파일을 뺀다")
        void excludesFileWithSecretContent() throws IOException {
            write("src/App.java", "class App {}");
            write("src/config.js", "export const auth = '" + FAKE_GITHUB_TOKEN + "';");

            FileSelectionResult result = select();

            assertThat(collectedPaths(result)).containsExactly("src/App.java");
            assertThat(reasonOf(result, "src/config.js"))
                    .isEqualTo(ExclusionReason.SECRET_SUSPECTED);
        }

        @Test
        @DisplayName("의존성 디렉터리는 안을 열거하지 않고 통째로 뺀다")
        void skipsDependencyDirectories() throws IOException {
            write("src/App.java", "class App {}");
            write("node_modules/left-pad/index.js", "module.exports = () => {};");
            write("build/classes/App.class", "compiled");

            FileSelectionResult result = select();

            assertThat(collectedPaths(result)).containsExactly("src/App.java");
            // 하위 파일이 트리에도 제외 목록에도 개별로 나오지 않는다.
            assertThat(result.fileTree()).noneMatch(path -> path.startsWith("node_modules/"));
            assertThat(result.excludedFiles())
                    .extracting(ExcludedFile::path)
                    .contains("node_modules/", "build/");
        }

        @Test
        @DisplayName("lock 파일과 최소화 코드를 뺀다")
        void excludesLockAndGeneratedFiles() throws IOException {
            write("src/App.java", "class App {}");
            write("package-lock.json", "{}");
            write("static/app.min.js", "var a=1;");
            write("static/app.js.map", "{}");

            FileSelectionResult result = select();

            assertThat(collectedPaths(result)).containsExactly("src/App.java");
            assertThat(reasonOf(result, "package-lock.json")).isEqualTo(ExclusionReason.DEPENDENCY);
            assertThat(reasonOf(result, "static/app.min.js")).isEqualTo(ExclusionReason.DEPENDENCY);
        }

        @Test
        @DisplayName("바이너리 파일을 확장자와 내용 양쪽으로 뺀다")
        void excludesBinaries() throws IOException {
            write("src/App.java", "class App {}");
            write("assets/logo.png", "not really a png");
            Files.write(repository.resolve("data.custom"), new byte[]{1, 2, 0, 3, 4});

            FileSelectionResult result = select();

            assertThat(collectedPaths(result)).containsExactly("src/App.java");
            assertThat(reasonOf(result, "assets/logo.png")).isEqualTo(ExclusionReason.BINARY);
            assertThat(reasonOf(result, "data.custom")).isEqualTo(ExclusionReason.BINARY);
        }

        @Test
        @DisplayName("Git LFS 포인터를 뺀다")
        void excludesLfsPointer() throws IOException {
            write("src/App.java", "class App {}");
            write("assets/model.bin.txt", """
                    version https://git-lfs.github.com/spec/v1
                    oid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393
                    size 12345
                    """);

            FileSelectionResult result = select();

            assertThat(reasonOf(result, "assets/model.bin.txt"))
                    .isEqualTo(ExclusionReason.LFS_POINTER);
        }

        @Test
        @DisplayName("사용자가 지정한 제외 경로를 별도 사유로 뺀다")
        void excludesConfiguredPaths() throws IOException {
            write("src/App.java", "class App {}");
            write("legacy/Old.java", "class Old {}");

            FileSelectionResult result = select(List.of("legacy/**"));

            assertThat(collectedPaths(result)).containsExactly("src/App.java");
            assertThat(reasonOf(result, "legacy/Old.java"))
                    .isEqualTo(ExclusionReason.CONFIGURED_EXCLUDE);
        }
    }

    @Nested
    @DisplayName("용량 상한")
    class ContentLimit {

        @Test
        @DisplayName("총합 상한을 넘으면 우선순위가 낮은 파일부터 뺀다")
        void keepsHighPriorityFilesWithinBudget() throws IOException {
            // 파일마다 100바이트. 상한을 250으로 두면 두 개만 들어간다.
            write("src/controller/UserController.java", "x".repeat(100));
            write("src/service/UserService.java", "x".repeat(100));
            write("docs/guide.md", "x".repeat(100));

            FileSelectionResult result = select(List.of(), DataSize.ofBytes(250));

            assertThat(collectedPaths(result)).containsExactly(
                    "src/controller/UserController.java", "src/service/UserService.java");
            assertThat(reasonOf(result, "docs/guide.md"))
                    .isEqualTo(ExclusionReason.TOTAL_CONTENT_LIMIT);
            assertThat(result.incompleteReasons())
                    .contains(IncompleteReason.TOTAL_CONTENT_LIMIT);
        }

        @Test
        @DisplayName("단일 파일 상한을 넘는 파일을 뺀다")
        void excludesOversizedFile() throws IOException {
            write("src/App.java", "class App {}");
            write("src/Huge.java", "x".repeat(2048));

            FileSelectionResult result = select(List.of(), DataSize.ofMegabytes(20),
                    DataSize.ofBytes(1024));

            assertThat(collectedPaths(result)).containsExactly("src/App.java");
            assertThat(reasonOf(result, "src/Huge.java")).isEqualTo(ExclusionReason.SIZE_LIMIT);
        }
    }

    @Nested
    @DisplayName("인계 형태")
    class Handoff {

        @Test
        @DisplayName("내용이 아니라 스트림 참조로 넘긴다")
        void handsOffContentReferenceNotContent() throws IOException {
            write("src/App.java", "class App {}");

            CollectedFile collected = select().collectedFiles().getFirst();

            assertThat(collected.sizeBytes()).isEqualTo(12);
            assertThat(collected.language()).isEqualTo("Java");
            try (InputStream in = collected.contentRef().open()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo("class App {}");
            }
        }

        @Test
        @DisplayName("제외된 파일도 트리에는 남아 저장소 구조가 보인다")
        void keepsExcludedFilesInTree() throws IOException {
            write("src/App.java", "class App {}");
            write(".env", "SECRET=value");

            FileSelectionResult result = select();

            assertThat(result.fileTree()).containsExactly(".env", "src/App.java");
        }
    }

    private FileSelectionResult select() {
        return select(List.of());
    }

    private FileSelectionResult select(List<String> excludePaths) {
        return select(excludePaths, DataSize.ofMegabytes(20));
    }

    private FileSelectionResult select(List<String> excludePaths, DataSize maxTotalContent) {
        return select(excludePaths, maxTotalContent, DataSize.ofMegabytes(1));
    }

    private FileSelectionResult select(List<String> excludePaths, DataSize maxTotalContent,
                                       DataSize maxFileSize) {
        CollectionProperties properties = new CollectionProperties(
                DataSize.ofMegabytes(200), DataSize.ofGigabytes(1), 20_000, maxFileSize,
                maxTotalContent, 3, Duration.ofSeconds(120), 30, 30, 30);
        RepositoryFileSelector selector = new RepositoryFileSelector(properties,
                new SecretPathRules(), new SecretContentScanner(), new FileExclusionRules());

        ExtractedRepository extracted = new ExtractedRepository(repository, repository, 0, 0,
                List.of(), List.of());
        return selector.select(extracted, excludePaths);
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = repository.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static List<String> collectedPaths(FileSelectionResult result) {
        return result.collectedFiles().stream().map(CollectedFile::path).sorted().toList();
    }

    private static ExclusionReason reasonOf(FileSelectionResult result, String path) {
        Map<String, ExclusionReason> byPath = result.excludedFiles().stream()
                .collect(java.util.stream.Collectors.toMap(ExcludedFile::path,
                        ExcludedFile::reason, (left, right) -> left));
        return Optional.ofNullable(byPath.get(path)).orElse(null);
    }
}
