package com.github.galpiii.galpi.domain.collection.file;

import com.github.galpiii.galpi.domain.collection.archive.ExtractedRepository;
import com.github.galpiii.galpi.domain.collection.config.CollectionProperties;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.CollectedFile;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.ExcludedFile;
import com.github.galpiii.galpi.domain.collection.pipeline.ContentRef;
import com.github.galpiii.galpi.domain.collection.pipeline.ExclusionReason;
import com.github.galpiii.galpi.domain.collection.secret.SecretContentScanner;
import com.github.galpiii.galpi.domain.collection.secret.SecretFinding;
import com.github.galpiii.galpi.domain.collection.secret.SecretPathRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 압축을 푼 트리에서 LLM에 보낼 파일을 고른다.
 *
 * <p>검사 순서에 의미가 있다. 싸고 확실한 것부터 본다.
 *
 * <ol>
 *   <li>경로 기반 비밀정보 — 내용을 열 필요조차 없다</li>
 *   <li>사용자가 지정한 제외 경로</li>
 *   <li>의존성·빌드 산출물·바이너리 확장자 — 확장자만 본다</li>
 *   <li>크기 상한</li>
 *   <li>내용 스니핑 — 바이너리, LFS 포인터</li>
 *   <li>내용 기반 비밀정보 스캔 — 가장 비싸다</li>
 * </ol>
 *
 * <p>내용은 한 번도 메모리에 통째로 담기지 않는다. 스캔은 스트리밍이고, 파이프라인에는
 * {@link ContentRef}로 임시 파일 참조만 넘긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepositoryFileSelector {

    /** LFS 포인터 파일의 첫 줄. 실제 내용이 아니라 참조라 분석 가치가 없다. */
    private static final String LFS_POINTER_PREFIX = "version https://git-lfs.github.com/spec/v1";

    /** 바이너리 판별용으로 들여다볼 앞부분. */
    private static final int SNIFF_BYTES = 8_192;

    private final CollectionProperties properties;
    private final SecretPathRules secretPathRules;
    private final SecretContentScanner secretContentScanner;
    private final FileExclusionRules exclusionRules;

    public FileSelectionResult select(ExtractedRepository repository, List<String> includePaths,
                                      List<String> excludePaths) {
        Scan scan = walk(repository, includePaths, excludePaths);

        // 상한에 걸릴 때 무엇을 남길지가 여기서 정해진다. 같은 우선순위면 경로 순서라 결과가
        // 재현 가능하다 — 같은 커밋을 두 번 분석했는데 다른 파일이 빠지면 원인을 못 찾는다.
        scan.candidates.sort(Comparator
                .comparingInt((Candidate candidate) -> candidate.priority().ordinal())
                .thenComparing(Candidate::path));

        List<CollectedFile> collected = new ArrayList<>();
        List<ExcludedFile> excluded = new ArrayList<>(scan.excluded);
        List<IncompleteReason> incompleteReasons = new ArrayList<>(
                repository.incompleteReasons());
        incompleteReasons.addAll(scan.incompleteReasons);
        long budget = properties.maxTotalContentBytes();
        long usedBytes = 0;
        boolean budgetExhausted = false;

        for (Candidate candidate : scan.candidates) {
            if (budgetExhausted || usedBytes + candidate.sizeBytes() > budget) {
                budgetExhausted = true;
                excluded.add(new ExcludedFile(candidate.path(),
                        ExclusionReason.TOTAL_CONTENT_LIMIT));
                continue;
            }
            usedBytes += candidate.sizeBytes();
            collected.add(new CollectedFile(
                    candidate.path(),
                    ContentRef.ofFile(candidate.file()),
                    candidate.sizeBytes(),
                    false,
                    LanguageDetector.detect(candidate.path())));
        }

        if (budgetExhausted) {
            log.info("[수집] 내용 총합 상한({} bytes)에 걸려 우선순위가 낮은 파일을 제외했다", budget);
            incompleteReasons.add(IncompleteReason.TOTAL_CONTENT_LIMIT);
        }

        scan.fileTree.sort(Comparator.naturalOrder());
        return new FileSelectionResult(List.copyOf(collected), List.copyOf(excluded),
                List.copyOf(scan.fileTree), List.copyOf(incompleteReasons), usedBytes);
    }

    private Scan walk(ExtractedRepository repository, List<String> includePaths,
                      List<String> excludePaths) {
        Scan scan = new Scan();
        Path root = repository.root();

        // 해제 단계에서 크기 때문에 아예 풀지 않은 파일. 트리에는 있어야 저장소 구조가 맞는다.
        for (String oversized : repository.oversizedPaths()) {
            scan.fileTree.add(oversized);
            scan.excluded.add(new ExcludedFile(oversized, ExclusionReason.SIZE_LIMIT));
        }

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory,
                                                         BasicFileAttributes attributes) {
                    if (directory.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = directory.getFileName().toString();
                    if (exclusionRules.isExcludedDirectory(name)) {
                        // 안을 열거하지 않는다. node_modules 하나로 파일이 수만 개 늘어나고,
                        // 그 목록은 트리에도 제외 목록에도 쓸모가 없다.
                        scan.excluded.add(new ExcludedFile(relativize(root, directory) + "/",
                                ExclusionReason.DEPENDENCY));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (!attributes.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    classify(root, file, attributes.size(), includePaths, excludePaths, scan);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    log.warn("[수집] 파일을 읽지 못해 건너뛴다 cause={}", e.getClass().getSimpleName());
                    scan.incompleteReasons.add(IncompleteReason.FILE_READ_FAILED);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[수집] 저장소 트리를 순회하지 못했다 cause={}", e.getClass().getSimpleName());
            scan.incompleteReasons.add(IncompleteReason.FILE_READ_FAILED);
        }
        return scan;
    }

    private void classify(Path root, Path file, long sizeBytes, List<String> includePaths,
                          List<String> excludePaths, Scan scan) {
        String relativePath = relativize(root, file);
        scan.fileTree.add(relativePath);

        Optional<ExclusionReason> reason = exclusionReason(file, relativePath, sizeBytes,
                includePaths, excludePaths, scan);
        if (reason.isPresent()) {
            scan.excluded.add(new ExcludedFile(relativePath, reason.get()));
            return;
        }
        scan.candidates.add(new Candidate(relativePath, file, sizeBytes,
                FilePriority.of(relativePath)));
    }

    private Optional<ExclusionReason> exclusionReason(Path file, String relativePath,
                                                      long sizeBytes, List<String> includePaths,
                                                      List<String> excludePaths, Scan scan) {
        if (secretPathRules.isSecretPath(relativePath)) {
            return Optional.of(ExclusionReason.SECRET_SUSPECTED);
        }
        if (!exclusionRules.matchesConfiguredInclude(relativePath, includePaths)) {
            return Optional.of(ExclusionReason.CONFIGURED_INCLUDE);
        }
        if (exclusionRules.matchesConfiguredExclude(relativePath, excludePaths)) {
            return Optional.of(ExclusionReason.CONFIGURED_EXCLUDE);
        }
        if (exclusionRules.isDependencyArtifact(relativePath)) {
            return Optional.of(ExclusionReason.DEPENDENCY);
        }
        if (exclusionRules.hasBinaryExtension(relativePath)) {
            return Optional.of(ExclusionReason.BINARY);
        }
        if (sizeBytes > properties.maxFileBytes()) {
            return Optional.of(ExclusionReason.SIZE_LIMIT);
        }
        if (sizeBytes == 0) {
            // 빈 파일은 보낼 것이 없다. 제외 사유를 붙일 만큼의 의미도 없어 트리에만 남긴다.
            return Optional.of(ExclusionReason.DEPENDENCY);
        }

        Sniffed sniffed = sniff(file);
        if (sniffed == Sniffed.UNREADABLE) {
            scan.incompleteReasons.add(IncompleteReason.FILE_READ_FAILED);
            return Optional.of(ExclusionReason.READ_FAILED);
        }
        if (sniffed == Sniffed.BINARY) {
            return Optional.of(ExclusionReason.BINARY);
        }
        if (sniffed == Sniffed.LFS_POINTER) {
            return Optional.of(ExclusionReason.LFS_POINTER);
        }

        Optional<SecretFinding> finding;
        try {
            finding = secretContentScanner.firstFindingOrThrow(file);
        } catch (IOException e) {
            log.warn("[수집] 비밀정보 검사 중 파일을 읽지 못했다 cause={}",
                    e.getClass().getSimpleName());
            scan.incompleteReasons.add(IncompleteReason.FILE_READ_FAILED);
            return Optional.of(ExclusionReason.READ_FAILED);
        }
        if (finding.isPresent()) {
            // 경로와 패턴 종류만 남긴다. 탐지된 값은 로그에도 반환값에도 넣지 않는다.
            log.info("[수집] 비밀정보가 의심되어 파일을 제외한다 path={} kind={} line={}",
                    relativePath, finding.get().kind(), finding.get().line());
            return Optional.of(ExclusionReason.SECRET_SUSPECTED);
        }
        return Optional.empty();
    }

    /**
     * 앞부분만 읽어 바이너리와 LFS 포인터를 가른다.
     *
     * <p>NUL 바이트 하나면 충분하다. 텍스트 소스에 NUL이 들어가는 경우는 사실상 없고,
     * 바이너리에는 거의 항상 있다.
     */
    private Sniffed sniff(Path file) {
        byte[] head = new byte[SNIFF_BYTES];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            log.warn("[수집] 파일 앞부분을 읽지 못했다 cause={}", e.getClass().getSimpleName());
            return Sniffed.UNREADABLE;
        }

        for (int i = 0; i < read; i++) {
            if (head[i] == 0) {
                return Sniffed.BINARY;
            }
        }
        String prefix = new String(head, 0, Math.min(read, LFS_POINTER_PREFIX.length()),
                StandardCharsets.UTF_8);
        return prefix.startsWith(LFS_POINTER_PREFIX) ? Sniffed.LFS_POINTER : Sniffed.TEXT;
    }

    private static String relativize(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private enum Sniffed {
        TEXT, BINARY, LFS_POINTER, UNREADABLE
    }

    private record Candidate(String path, Path file, long sizeBytes, FilePriority priority) {
    }

    private static final class Scan {
        private final List<Candidate> candidates = new ArrayList<>();
        private final List<ExcludedFile> excluded = new ArrayList<>();
        private final List<String> fileTree = new ArrayList<>();
        private final List<IncompleteReason> incompleteReasons = new ArrayList<>();
    }
}
