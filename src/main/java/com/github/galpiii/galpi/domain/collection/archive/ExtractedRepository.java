package com.github.galpiii.galpi.domain.collection.archive;

import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 압축을 푼 저장소 트리.
 *
 * <p>{@code root}는 tarball이 만드는 {@code {owner}-{repo}-{sha7}/} 한 겹을 이미 벗겨낸
 * 경로다. 이후 단계는 이 아래의 상대 경로만 다룬다.
 *
 * <p>닫으면 임시 디렉터리 전체가 사라진다. 코드 원문을 디스크에 남기지 않는 것이 목적이라
 * 예외가 나는 경로에서도 반드시 닫혀야 한다.
 *
 * @param oversizedPaths 단일 파일 상한을 넘어 아예 풀지 않은 파일. 내용 없이 경로만 안다
 * @param incompleteReasons 상한에 걸려 트리가 온전하지 않을 때의 사유
 */
@Slf4j
public record ExtractedRepository(Path temporaryDirectory,
                                  Path root,
                                  int fileCount,
                                  long totalBytes,
                                  List<String> oversizedPaths,
                                  List<IncompleteReason> incompleteReasons)
        implements AutoCloseable {

    @Override
    public void close() {
        if (temporaryDirectory == null || !Files.exists(temporaryDirectory)) {
            return;
        }
        try (var paths = Files.walk(temporaryDirectory)) {
            // 깊은 것부터 지워야 디렉터리가 빈 상태로 삭제된다.
            paths.sorted(Comparator.reverseOrder()).forEach(ExtractedRepository::deleteQuietly);
        } catch (IOException e) {
            log.error("[수집] 임시 디렉터리를 정리하지 못했다. 코드 원문이 남았을 수 있다 cause={}",
                    e.getClass().getSimpleName());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.error("[수집] 임시 파일 삭제 실패 cause={}", e.getClass().getSimpleName());
        }
    }
}
