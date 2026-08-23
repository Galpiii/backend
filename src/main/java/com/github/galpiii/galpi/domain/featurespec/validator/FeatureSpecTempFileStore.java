package com.github.galpiii.galpi.domain.featurespec.validator;

import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * 업로드된 PDF를 담아 두는 임시 파일을 관리한다.
 *
 * <p>원본 PDF를 보관하지 않으므로 이 파일이 검증부터 분석까지 쓰이는 유일한 실체다. 만들고
 * 지우는 일이 요청 하나 안에서 끝나지 않고 비동기 분석까지 이어져서, 파일 이름 규칙을 아는
 * 곳을 한 군데로 모아 둔다.
 */
@Slf4j
@Component
public class FeatureSpecTempFileStore {

    private static final String TEMP_FILE_PREFIX = "feature-spec-";
    private static final String TEMP_FILE_SUFFIX = ".pdf";

    /**
     * 임시 PDF만 모아 두는 자리. 시스템 임시 디렉터리를 통째로 훑으면 정리 배치가 다른 프로그램의
     * 파일까지 후보로 삼는다. 인스턴스별로 나누지는 않는다 — 죽은 인스턴스가 남긴 파일을 아무도
     * 치우지 못하게 되고, 살아 있는 인스턴스의 파일은 나이 조건이 이미 지켜 준다.
     */
    private static final String TEMP_DIRECTORY_NAME = "galpi-feature-spec";

    public File create(MultipartFile file) {
        File tempFile = null;

        try {
            tempFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, tempDirectory().toFile());

            try (InputStream source = file.getInputStream()) {
                Files.copy(source, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            return tempFile;
        } catch (IOException | IllegalStateException e) {
            if (tempFile != null) {
                delete(tempFile);
            }

            log.error("[기능명세서 업로드] 임시 파일 생성 실패.", e);
            throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public void delete(File tempFile) {
        if (!tempFile.delete()) {
            log.warn("[기능명세서 업로드] 임시 파일 삭제 실패. path: {}", tempFile.getAbsolutePath());
        }
    }

     // 주인이 사라진 임시 파일 삭제
    public int deleteOlderThan(Instant threshold) {
        int deleted = 0;

        try (DirectoryStream<Path> files =
                     Files.newDirectoryStream(tempDirectory(), TEMP_FILE_PREFIX + "*" + TEMP_FILE_SUFFIX)) {
            for (Path file : files) {
                if (Files.getLastModifiedTime(file).toInstant().isAfter(threshold)) {
                    continue;
                }

                if (Files.deleteIfExists(file)) {
                    deleted++;
                }
            }
        } catch (IOException e) {
            log.error("[기능명세서 업로드] 남은 임시 파일 정리에 실패했습니다.", e);
        }

        return deleted;
    }

    private Path tempDirectory() {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), TEMP_DIRECTORY_NAME);

        try {
            return Files.createDirectories(directory);
        } catch (IOException e) {
            log.error("[기능명세서 업로드] 임시 디렉터리를 만들지 못했습니다. path: {}", directory, e);
            throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
