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

    public File create(MultipartFile file) {
        File tempFile = null;

        try {
            tempFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);

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
        Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        int deleted = 0;

        try (DirectoryStream<Path> files =
                     Files.newDirectoryStream(tempDirectory, TEMP_FILE_PREFIX + "*" + TEMP_FILE_SUFFIX)) {
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
}
