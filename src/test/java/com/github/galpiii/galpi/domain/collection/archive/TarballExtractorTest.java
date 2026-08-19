package com.github.galpiii.galpi.domain.collection.archive;

import com.github.galpiii.galpi.domain.collection.config.CollectionProperties;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TarballExtractor — 안전 해제")
class TarballExtractorTest {

    private static final String ROOT = "octocat-hello-world-7fd1a60/";

    @TempDir
    Path workspace;

    @Nested
    @DisplayName("경로 탈출과 링크 방어")
    class Guards {

        @Test
        @DisplayName("절대 경로 엔트리를 거부한다")
        void rejectsAbsolutePath() throws IOException {
            // commons-compress는 기본적으로 쓰기 시점에 선행 슬래시를 떼어낸다. 악의적인 tar은
            // 다른 도구로 만들어지므로, 실제로 들어올 수 있는 형태를 그대로 재현해야 한다.
            Path archive = archive(tar -> {
                TarArchiveEntry entry = new TarArchiveEntry("/etc/passwd", true);
                entry.setSize(0);
                writeEntry(tar, entry);
            });

            assertThatThrownBy(() -> extract(archive))
                    .isInstanceOf(UnsafeArchiveEntryException.class);
        }

        @Test
        @DisplayName(".. 로 대상 디렉터리를 벗어나는 엔트리를 거부한다")
        void rejectsPathTraversal() throws IOException {
            Path archive = archive(tar -> writeFile(tar, ROOT + "../../escaped.txt", "pwned"));

            assertThatThrownBy(() -> extract(archive))
                    .isInstanceOf(UnsafeArchiveEntryException.class);
        }

        @Test
        @DisplayName("정규화하면 벗어나는 우회 경로도 거부한다")
        void rejectsNormalizedTraversal() throws IOException {
            // a/../../etc 는 문자열만 보면 평범해 보이지만 정규화하면 대상 밖이다.
            Path archive = archive(tar ->
                    writeFile(tar, ROOT + "a/b/../../../../escaped.txt", "pwned"));

            assertThatThrownBy(() -> extract(archive))
                    .isInstanceOf(UnsafeArchiveEntryException.class);
        }

        @Test
        @DisplayName("symbolic link 엔트리를 거부한다")
        void rejectsSymbolicLink() throws IOException {
            Path archive = archive(tar -> {
                TarArchiveEntry entry = new TarArchiveEntry(ROOT + "link", TarArchiveEntry.LF_SYMLINK);
                entry.setLinkName("/etc/passwd");
                writeEntry(tar, entry);
            });

            assertThatThrownBy(() -> extract(archive))
                    .isInstanceOf(UnsafeArchiveEntryException.class);
        }

        @Test
        @DisplayName("hard link 엔트리를 거부한다")
        void rejectsHardLink() throws IOException {
            Path archive = archive(tar -> {
                TarArchiveEntry entry = new TarArchiveEntry(ROOT + "hard", TarArchiveEntry.LF_LINK);
                entry.setLinkName("/etc/passwd");
                writeEntry(tar, entry);
            });

            assertThatThrownBy(() -> extract(archive))
                    .isInstanceOf(UnsafeArchiveEntryException.class);
        }

        @Test
        @DisplayName("일반 파일도 디렉터리도 아닌 엔트리를 거부한다")
        void rejectsSpecialFile() throws IOException {
            Path archive = archive(tar ->
                    writeEntry(tar, new TarArchiveEntry(ROOT + "fifo", TarArchiveEntry.LF_FIFO)));

            assertThatThrownBy(() -> extract(archive))
                    .isInstanceOf(UnsafeArchiveEntryException.class);
        }
    }

    @Nested
    @DisplayName("상한")
    class Limits {

        @Test
        @DisplayName("단일 파일 상한을 넘는 파일은 풀지 않고 경로만 남긴다")
        void skipsOversizedFile() throws IOException {
            Path archive = archive(tar -> {
                writeFile(tar, ROOT + "small.txt", "ok");
                writeFile(tar, ROOT + "huge.txt", "x".repeat(2048));
            });

            try (ExtractedRepository extracted = extract(archive, properties(DataSize.ofBytes(1024),
                    DataSize.ofMegabytes(1), 20_000))) {
                assertThat(extracted.oversizedPaths()).containsExactly("huge.txt");
                assertThat(extracted.fileCount()).isEqualTo(1);
                assertThat(Files.exists(extracted.root().resolve("huge.txt"))).isFalse();
                assertThat(Files.exists(extracted.root().resolve("small.txt"))).isTrue();
            }
        }

        @Test
        @DisplayName("단일 파일 상한으로 버린 바이트도 압축 해제 총량에 포함한다")
        void countsDiscardedOversizedBytesTowardExtractionLimit() throws IOException {
            Path archive = archive(tar ->
                    writeFile(tar, ROOT + "bomb.txt", "x".repeat(16 * 1024)));

            try (ExtractedRepository extracted = extract(archive,
                    properties(DataSize.ofBytes(1024), DataSize.ofBytes(2048), 20_000))) {
                assertThat(extracted.incompleteReasons())
                        .contains(IncompleteReason.ARCHIVE_SIZE_LIMIT);
                assertThat(Files.exists(extracted.root().resolve("bomb.txt"))).isFalse();
            }
        }

        @Test
        @DisplayName("크기 초과로 버린 파일도 엔트리 개수 상한에 포함한다")
        void countsOversizedFilesTowardEntryLimit() throws IOException {
            Path archive = archive(tar -> {
                writeFile(tar, ROOT + "first.txt", "x".repeat(2048));
                writeFile(tar, ROOT + "second.txt", "x".repeat(2048));
            });

            try (ExtractedRepository extracted = extract(archive,
                    properties(DataSize.ofBytes(1024), DataSize.ofMegabytes(1), 1))) {
                assertThat(extracted.oversizedPaths()).containsExactly("first.txt");
                assertThat(extracted.incompleteReasons())
                        .contains(IncompleteReason.FILE_LIMIT_EXCEEDED);
            }
        }

        @Test
        @DisplayName("파일 개수 상한에 도달하면 멈추고 사유를 남긴다")
        void stopsAtEntryCountLimit() throws IOException {
            Path archive = archive(tar -> {
                for (int i = 0; i < 5; i++) {
                    writeFile(tar, ROOT + "file" + i + ".txt", "content");
                }
            });

            try (ExtractedRepository extracted = extract(archive,
                    properties(DataSize.ofMegabytes(1), DataSize.ofMegabytes(1), 2))) {
                assertThat(extracted.fileCount()).isEqualTo(2);
                assertThat(extracted.incompleteReasons())
                        .contains(IncompleteReason.FILE_LIMIT_EXCEEDED);
            }
        }

        @Test
        @DisplayName("압축 해제 총 용량 상한에 도달하면 멈추고 사유를 남긴다")
        void stopsAtTotalSizeLimit() throws IOException {
            Path archive = archive(tar -> {
                for (int i = 0; i < 5; i++) {
                    writeFile(tar, ROOT + "file" + i + ".txt", "x".repeat(100));
                }
            });

            try (ExtractedRepository extracted = extract(archive,
                    properties(DataSize.ofMegabytes(1), DataSize.ofBytes(150), 20_000))) {
                assertThat(extracted.incompleteReasons())
                        .contains(IncompleteReason.ARCHIVE_SIZE_LIMIT);
                assertThat(extracted.fileCount()).isLessThan(5);
            }
        }
    }

    @Nested
    @DisplayName("정상 해제")
    class HappyPath {

        @Test
        @DisplayName("{owner}-{repo}-{sha7} 루트 한 겹을 벗긴다")
        void stripsArchiveRootDirectory() throws IOException {
            Path archive = archive(tar -> {
                writeDirectory(tar, ROOT);
                writeDirectory(tar, ROOT + "src/");
                writeFile(tar, ROOT + "src/App.java", "class App {}");
                writeFile(tar, ROOT + "README.md", "# hello");
            });

            try (ExtractedRepository extracted = extract(archive)) {
                assertThat(Files.readString(extracted.root().resolve("src/App.java")))
                        .isEqualTo("class App {}");
                assertThat(Files.readString(extracted.root().resolve("README.md")))
                        .isEqualTo("# hello");
                assertThat(extracted.fileCount()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("닫으면 임시 디렉터리가 통째로 사라진다")
        void deletesTemporaryDirectoryOnClose() throws IOException {
            Path archive = archive(tar -> writeFile(tar, ROOT + "src/App.java", "class App {}"));

            Path temporary;
            try (ExtractedRepository extracted = extract(archive)) {
                temporary = extracted.temporaryDirectory();
                assertThat(Files.exists(temporary)).isTrue();
            }
            assertThat(Files.exists(temporary)).isFalse();
        }

        @Test
        @DisplayName("안전하지 않은 엔트리로 실패해도 임시 디렉터리를 남기지 않는다")
        void cleansUpWhenExtractionFails() throws IOException {
            Path archive = archive(tar -> {
                writeFile(tar, ROOT + "ok.txt", "fine");
                writeFile(tar, ROOT + "../escaped.txt", "pwned");
            });

            List<Path> before = temporaryDirectories();
            assertThatThrownBy(() -> extract(archive))
                    .isInstanceOf(UnsafeArchiveEntryException.class);

            assertThat(temporaryDirectories()).hasSameSizeAs(before);
        }
    }

    private ExtractedRepository extract(Path archive) {
        return extract(archive, properties(DataSize.ofMegabytes(1), DataSize.ofGigabytes(1),
                20_000));
    }

    private ExtractedRepository extract(Path archive, CollectionProperties properties) {
        return new TarballExtractor(properties)
                .extract(new DownloadedArchive(archive, sizeOf(archive)));
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 임시 디렉터리 누수를 보기 위해 galpi-repo-* 만 센다. */
    private static List<Path> temporaryDirectories() throws IOException {
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"));
        List<Path> found = new ArrayList<>();
        try (var stream = Files.list(tempRoot)) {
            stream.filter(path -> path.getFileName().toString().startsWith("galpi-repo-"))
                    .forEach(found::add);
        }
        return found;
    }

    private static CollectionProperties properties(DataSize maxFileSize, DataSize maxExtracted,
                                                   int maxEntryCount) {
        return new CollectionProperties(
                DataSize.ofMegabytes(200), maxExtracted, maxEntryCount, maxFileSize,
                DataSize.ofMegabytes(20), DataSize.ofMegabytes(20), 3,
                Duration.ofSeconds(120), 30, 30, 30, 900);
    }

    private Path archive(Consumer<TarArchiveOutputStream> content) throws IOException {
        Path archive = Files.createTempFile(workspace, "archive-", ".tar.gz");
        try (OutputStream fileStream = Files.newOutputStream(archive);
             GZIPOutputStream gzip = new GZIPOutputStream(fileStream);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            // 경로 탈출 테스트가 긴 이름을 쓰므로 GNU 확장을 켜 둔다.
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            content.accept(tar);
            tar.finish();
        }
        return archive;
    }

    private static void writeFile(TarArchiveOutputStream tar, String name, String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(bytes.length);
            tar.putArchiveEntry(entry);
            tar.write(bytes);
            tar.closeArchiveEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeDirectory(TarArchiveOutputStream tar, String name) {
        writeEntry(tar, new TarArchiveEntry(name));
    }

    /** 내용 없는 엔트리 하나. 링크와 특수 파일을 심는 데 쓴다. */
    private static void writeEntry(TarArchiveOutputStream tar, TarArchiveEntry entry) {
        try {
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
