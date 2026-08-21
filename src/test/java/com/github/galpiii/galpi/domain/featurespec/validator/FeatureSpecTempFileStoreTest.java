package com.github.galpiii.galpi.domain.featurespec.validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FeatureSpecTempFileStore — 업로드 임시 파일")
class FeatureSpecTempFileStoreTest {

    private final FeatureSpecTempFileStore store = new FeatureSpecTempFileStore();

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file", "기능명세서.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf-bytes".getBytes());
    }

    @Test
    @DisplayName("업로드 내용을 담은 임시 파일을 만든다")
    void createsTempFileWithUploadedContent() {
        File tempFile = store.create(pdfFile());

        try {
            assertThat(tempFile).exists().hasContent("pdf-bytes");
        } finally {
            store.delete(tempFile);
        }
    }

    /**
     * 정리 기준이 너무 짧으면 다른 요청이 지금 쓰고 있는 파일을 지운다. 나이로만 판단한다는 것이
     * 이 정리의 전제이므로 최근 파일이 남는지까지 함께 본다.
     */
    @Test
    @DisplayName("주인이 사라진 오래된 임시 파일만 지운다")
    void deletesOnlyStaleTempFiles() {
        File stale = store.create(pdfFile());
        File fresh = store.create(pdfFile());
        assertThat(stale.setLastModified(Instant.now().minus(Duration.ofHours(5)).toEpochMilli()))
                .isTrue();

        try {
            store.deleteOlderThan(Instant.now().minus(Duration.ofHours(3)));

            assertThat(stale).doesNotExist();
            assertThat(fresh).exists();
        } finally {
            stale.delete();
            fresh.delete();
        }
    }
}
