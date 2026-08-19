package com.github.galpiii.galpi.domain.collection.archive;

import com.github.galpiii.galpi.domain.collection.config.CollectionProperties;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.util.LogSafe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * tarball을 임시 디렉터리에 안전하게 푼다.
 *
 * <p>거부하는 엔트리는 다음과 같다. 하나라도 통과하면 저장소가 서버 파일 시스템에 쓰기를 할 수
 * 있게 되므로, 해당 엔트리만 건너뛰지 않고 저장소 전체를 실패시킨다.
 *
 * <ul>
 *   <li>절대 경로 — {@code /etc/passwd}</li>
 *   <li>{@code ..}로 대상 디렉터리를 벗어나는 경로</li>
 *   <li><b>symbolic link와 hard link</b> — 경로 검사만으로는 막히지 않는다. 링크 자체는 대상
 *       디렉터리 안에 만들어지지만, 그 링크를 따라 쓰면 바깥이 열린다</li>
 *   <li>일반 파일·디렉터리가 아닌 것 — device, fifo, socket</li>
 * </ul>
 *
 * <p>압축 폭탄은 헤더가 아니라 실제로 푼 바이트로 막는다. tar 헤더의 크기는 거짓일 수 있어
 * 헤더만 더해서는 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TarballExtractor {

    private static final String TEMP_DIRECTORY_PREFIX = "galpi-repo-";
    private static final int BUFFER_SIZE = 64 * 1024;

    /** {@code C:\} 같은 Windows 드라이브 지정. Unix에서 콜론은 정상 파일명 문자라 이것만 막는다. */
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private final CollectionProperties properties;

    /**
     * @return 호출부가 반드시 닫아야 하는 디렉터리 핸들
     */
    public ExtractedRepository extract(DownloadedArchive archive) {
        Path directory = createTemporaryDirectory();
        try {
            return extractInto(archive, directory);
        } catch (RuntimeException e) {
            new ExtractedRepository(directory, directory, 0, 0, List.of(), List.of()).close();
            throw e;
        }
    }

    private ExtractedRepository extractInto(DownloadedArchive archive, Path directory) {
        Path canonicalRoot = directory;
        List<String> oversizedPaths = new ArrayList<>();
        List<IncompleteReason> incompleteReasons = new ArrayList<>();
        long totalBytes = 0;
        int fileCount = 0;
        int entryCount = 0;

        try (InputStream fileStream = Files.newInputStream(archive.path());
             InputStream buffered = new BufferedInputStream(fileStream, BUFFER_SIZE);
             GZIPInputStream gzip = new GZIPInputStream(buffered, BUFFER_SIZE);
             InputStream limited = new ExtractionLimitInputStream(
                     gzip, properties.maxExtractedBytes());
             TarArchiveInputStream tar = new TarArchiveInputStream(limited)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entryCount >= properties.maxEntryCount()) {
                    log.warn("[수집] 압축 해제 엔트리 개수 상한({})에 도달해 나머지를 건너뛴다",
                            properties.maxEntryCount());
                    incompleteReasons.add(IncompleteReason.FILE_LIMIT_EXCEEDED);
                    break;
                }
                entryCount++;
                if (entry.isPaxHeader() || entry.isGlobalPaxHeader()) {
                    // 메타데이터도 파싱 비용이 있으므로 상한에는 포함하되 파일로 만들지는 않는다.
                    continue;
                }
                rejectUnsafeType(entry);
                rejectAbsolutePath(entry.getName());

                String relativePath = stripArchiveRoot(entry.getName());
                if (relativePath.isEmpty()) {
                    continue;
                }
                Path destination = resolveWithin(canonicalRoot, relativePath);

                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }

                if (totalBytes >= properties.maxExtractedBytes()) {
                    log.warn("[수집] 압축 해제 총 용량 상한({} bytes)에 도달해 나머지를 건너뛴다",
                            properties.maxExtractedBytes());
                    incompleteReasons.add(IncompleteReason.ARCHIVE_SIZE_LIMIT);
                    break;
                }

                Files.createDirectories(destination.getParent());
                long written = copyEntry(tar, destination, oversizedPaths, relativePath);
                if (written < 0) {
                    continue;
                }
                totalBytes += written;
                fileCount++;
            }
        } catch (ExtractionLimitExceededException e) {
            log.warn("[수집] 실제 압축 해제 바이트가 상한({} bytes)을 넘어 중단한다",
                    properties.maxExtractedBytes());
            incompleteReasons.add(IncompleteReason.ARCHIVE_SIZE_LIMIT);
        } catch (IOException e) {
            log.warn("[수집] tarball을 풀지 못했다 cause={}", e.getClass().getSimpleName());
            throw new ArchiveLimitExceededException(ErrorCode.COLLECTION_ARCHIVE_INVALID);
        }

        return new ExtractedRepository(directory, canonicalRoot, fileCount, totalBytes,
                List.copyOf(oversizedPaths), List.copyOf(incompleteReasons));
    }

    /**
     * 엔트리를 파일로 옮긴다.
     *
     * <p>전체 용량 상한은 GZIP 바로 위의 제한 스트림이 모든 엔트리와 건너뛴 바이트를 포함해
     * 강제한다. 여기서는 실제 파일로 쓴 바이트만 세어 결과 통계에 사용한다.
     *
     * @return 쓴 바이트. 단일 파일 상한을 넘어 건너뛰었으면 -1
     */
    private long copyEntry(TarArchiveInputStream tar, Path destination,
                           List<String> oversizedPaths, String relativePath) throws IOException {
        long fileLimit = properties.maxFileBytes();
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0;

        // CREATE_NEW라서 이미 있는 경로에는 쓰지 않는다. 디렉터리 안에 남아 있던 symlink를
        // 따라가는 경로가 여기서 막힌다 — 이 디렉터리는 방금 만든 것이지만, 같은 tarball 안에
        // 같은 경로가 두 번 나오는 경우까지 포함해 한 번만 쓰게 한다.
        try (OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            int read;
            while ((read = tar.read(buffer)) != -1) {
                written += read;
                if (written > fileLimit) {
                    out.close();
                    Files.deleteIfExists(destination);
                    oversizedPaths.add(relativePath);
                    return -1;
                }
                out.write(buffer, 0, read);
            }
        } catch (ExtractionLimitExceededException e) {
            Files.deleteIfExists(destination);
            throw e;
        } catch (FileAlreadyExistsException e) {
            log.warn("[수집] 같은 경로가 아카이브에 두 번 나타나 뒤쪽을 버린다");
            return -1;
        }
        return written;
    }

    /**
     * 링크와 특수 파일을 거부한다.
     *
     * <p>{@code isFile()}만 확인하고 넘어가면 안 된다. commons-compress에서 symlink 엔트리는
     * {@code isFile()}이 false이므로 통과하지만, 그 사실에 기대는 대신 명시적으로 거부해야
     * 라이브러리 동작이 바뀌어도 안전하다.
     */
    private void rejectUnsafeType(TarArchiveEntry entry) {
        if (entry.isSymbolicLink() || entry.isLink()) {
            log.warn("[수집] 아카이브에 링크 엔트리가 있어 거부한다 name={}",
                    LogSafe.text(entry.getName()));
            throw new UnsafeArchiveEntryException();
        }
        if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
            log.warn("[수집] 아카이브에 특수 파일 엔트리가 있어 거부한다 name={}",
                    LogSafe.text(entry.getName()));
            throw new UnsafeArchiveEntryException();
        }
        if (!entry.isFile() && !entry.isDirectory()) {
            log.warn("[수집] 아카이브에 일반 파일도 디렉터리도 아닌 엔트리가 있어 거부한다 name={}",
                    LogSafe.text(entry.getName()));
            throw new UnsafeArchiveEntryException();
        }
    }

    /**
     * 절대 경로 엔트리를 거부한다.
     *
     * <p><b>루트 디렉터리를 벗기기 전에</b> 검사해야 한다. {@code /etc/passwd}는 첫 세그먼트를
     * 버리면 {@code etc/passwd}가 되어 대상 디렉터리 안의 평범한 경로처럼 보인다. 실제 피해는
     * 없지만 거부해야 할 입력을 조용히 받아들이게 되고, 루트를 벗기는 규칙이 조금만 달라져도
     * 그대로 구멍이 된다.
     */
    private static void rejectAbsolutePath(String entryName) {
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/") || WINDOWS_DRIVE.matcher(normalized).matches()) {
            log.warn("[수집] 아카이브에 절대 경로 엔트리가 있어 거부한다");
            throw new UnsafeArchiveEntryException();
        }
    }

    /**
     * 대상 디렉터리 안으로만 풀리도록 강제한다.
     *
     * <p>{@code normalize()}만으로는 부족하다. {@code a/../../etc}는 정규화하면
     * {@code ../etc}가 되어 대상 밖을 가리키는데, 문자열만 보고 {@code ..}가 없다고 통과시키면
     * 그대로 뚫린다. 정규화한 절대 경로가 루트로 시작하는지까지 확인한다.
     */
    private static Path resolveWithin(Path root, String relativePath) {
        Path resolved;
        try {
            resolved = root.resolve(relativePath).normalize();
        } catch (InvalidPathException e) {
            log.warn("[수집] 아카이브 엔트리 경로를 해석할 수 없어 거부한다");
            throw new UnsafeArchiveEntryException();
        }

        if (!resolved.startsWith(root)) {
            log.warn("[수집] 아카이브 엔트리가 대상 디렉터리를 벗어나 거부한다");
            throw new UnsafeArchiveEntryException();
        }
        return resolved;
    }

    /**
     * tarball이 만드는 {@code {owner}-{repo}-{sha7}/} 한 겹을 벗긴다.
     *
     * <p>이 이름에는 저장소 이름이 들어가는데, 이름이 바뀌면 값도 바뀐다. 그래서 이름을
     * 맞춰 보지 않고 첫 세그먼트를 무조건 버린다.
     */
    private static String stripArchiveRoot(String entryName) {
        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        int slash = normalized.indexOf('/');
        if (slash < 0) {
            // 루트 디렉터리 엔트리 자체. 만들 것이 없다.
            return "";
        }
        String stripped = normalized.substring(slash + 1);
        return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
    }

    /**
     * 임시 디렉터리를 만들고 실제 경로로 되돌린다.
     *
     * <p>실제 경로로 바꾸는 것이 중요하다. macOS의 {@code /var}는 {@code /private/var}를
     * 가리키는 링크라, 이 과정을 빼면 이후의 {@code startsWith} 검사가 멀쩡한 경로를 탈출로
     * 오판한다. 여기서 한 번 정리해 두면 이후 단계는 전부 같은 형태의 경로만 본다.
     */
    private static Path createTemporaryDirectory() {
        try {
            // 이름은 랜덤이다. 저장소 이름 같은 사용자 입력을 경로에 쓰지 않는다.
            Path directory = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX);
            return directory.toRealPath().toAbsolutePath();
        } catch (IOException e) {
            throw new ArchiveLimitExceededException(ErrorCode.COLLECTION_ARCHIVE_INVALID);
        }
    }

    /**
     * tar 엔트리를 저장했는지와 무관하게 GZIP에서 실제로 풀린 모든 바이트를 제한한다.
     *
     * <p>큰 파일을 디스크에 쓰지 않고 건너뛰더라도 {@link TarArchiveInputStream}은 다음 엔트리로
     * 이동하기 위해 남은 내용을 읽어 버린다. 이 계층에서 제한하지 않으면 그 바이트가 총량에서
     * 빠져 압축 폭탄 방어를 우회할 수 있다.
     */
    private static final class ExtractionLimitInputStream extends FilterInputStream {

        private final long limit;
        private long consumed;

        private ExtractionLimitInputStream(InputStream delegate, long limit) {
            super(delegate);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            ensureRemaining();
            int value = super.read();
            if (value != -1) {
                consumed++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            ensureRemaining();
            int allowed = (int) Math.min(length, limit - consumed);
            int read = super.read(bytes, offset, allowed);
            if (read > 0) {
                consumed += read;
            }
            return read;
        }

        @Override
        public long skip(long length) throws IOException {
            if (length <= 0) {
                return 0;
            }
            ensureRemaining();
            long skipped = super.skip(Math.min(length, limit - consumed));
            consumed += skipped;
            return skipped;
        }

        private void ensureRemaining() throws ExtractionLimitExceededException {
            if (consumed >= limit) {
                throw new ExtractionLimitExceededException();
            }
        }
    }

    private static final class ExtractionLimitExceededException extends IOException {
    }
}
