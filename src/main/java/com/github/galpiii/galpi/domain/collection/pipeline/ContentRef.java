package com.github.galpiii.galpi.domain.collection.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 파일 내용을 여는 방법. 내용 자체가 아니다.
 *
 * <p>이 인터페이스가 인계 계약의 핵심이다. {@code String content}로 뒀다면 수집기가 저장소
 * 하나의 모든 파일을 메모리에 올려야 하고, 20MB 상한이 있어도 저장소를 여러 개 동시에 처리하는
 * 순간 힙이 무너진다. 파이프라인이 필요한 순간에 열어 읽고 닫게 한다.
 *
 * <p>스트림은 임시 디렉터리를 가리킨다. 그 디렉터리는 수집이 끝나면 지워지므로,
 * {@link CollectedRepositorySnapshot}을 받은 쪽은 인계가 끝난 뒤까지 참조를 들고 있으면 안 된다.
 */
@FunctionalInterface
public interface ContentRef {

    InputStream open() throws IOException;

    static ContentRef ofFile(Path path) {
        return () -> Files.newInputStream(path);
    }
}
