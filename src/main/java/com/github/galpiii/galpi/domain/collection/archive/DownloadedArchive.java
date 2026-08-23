package com.github.galpiii.galpi.domain.collection.archive;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 내려받은 tarball 임시 파일.
 *
 * <p>{@link AutoCloseable}로 만든 것은 삭제를 잊지 않기 위해서다. 코드 원문은 분석 후 즉시
 * 지워야 하고, 예외가 나도 지워져야 한다 — try-with-resources가 그 두 가지를 문법으로 강제한다.
 *
 * @param sizeBytes 실제로 읽은 바이트. {@code Content-Length}가 아니다
 */
@Slf4j
public record DownloadedArchive(Path path, long sizeBytes) implements AutoCloseable {

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 지우지 못했다는 사실 자체가 문제라 조용히 넘기지 않는다. 다만 이것 때문에
            // 수집을 실패로 만들지는 않는다 — 이미 받은 데이터는 정상이다.
            log.error("[수집] 임시 tarball을 지우지 못했다. 남은 파일을 확인해야 한다 cause={}",
                    e.getClass().getSimpleName());
        }
    }
}
