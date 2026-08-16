package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.global.config.AwsS3Properties;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3Service — 기능명세서 원본 보관")
class S3ServiceTest {

    private static final long PROJECT_ID = 1L;
    private static final String BUCKET = "galpi-test-bucket";
    private static final String STORAGE_KEY = "feature-specs/1/3b62218a-980a-4160-bc4e-f52a38c9806c.pdf";

    @Mock
    private S3Client s3Client;

    @TempDir
    Path tempDir;

    private S3Service service;
    private File file;

    @BeforeEach
    void setUp() throws IOException {
        AwsS3Properties properties =
                new AwsS3Properties("ap-northeast-2", new AwsS3Properties.S3(BUCKET));
        service = new S3Service(s3Client, properties);

        file = Files.write(tempDir.resolve("feature-spec.pdf"), "pdf".getBytes()).toFile();
    }

    private PutObjectRequest capturedPutRequest() {
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        return captor.getValue();
    }

    @Test
    @DisplayName("프로젝트별 경로에 UUID 이름으로 올린다")
    void uploadsToProjectScopedKey() {
        String storageKey = service.upload(file, PROJECT_ID);

        assertThat(storageKey).matches(
                "feature-specs/1/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf");
        assertThat(capturedPutRequest().key()).isEqualTo(storageKey);
    }

    @Test
    @DisplayName("설정된 버킷에 application/pdf로 올린다")
    void uploadsAsPdfToConfiguredBucket() {
        service.upload(file, PROJECT_ID);

        PutObjectRequest request = capturedPutRequest();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
    }

    @Test
    @DisplayName("올릴 때마다 다른 키를 쓴다")
    void usesUniqueKeyPerUpload() {
        String first = service.upload(file, PROJECT_ID);
        String second = service.upload(file, PROJECT_ID);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("SDK 예외는 감추고 저장 실패 코드로 바꾼다")
    void translatesSdkFailure() {
        willThrow(SdkClientException.create("connection reset"))
                .given(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        assertThatThrownBy(() -> service.upload(file, PROJECT_ID))
                .isInstanceOf(GlobalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_STORAGE_UPLOAD_FAILED);
    }

    @Test
    @DisplayName("읽을 수 없는 파일도 저장 실패 코드로 바꾼다")
    void translatesUnreadableFile() {
        File missing = tempDir.resolve("does-not-exist.pdf").toFile();

        assertThatThrownBy(() -> service.upload(missing, PROJECT_ID))
                .isInstanceOf(GlobalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_STORAGE_UPLOAD_FAILED);
    }

    @Test
    @DisplayName("삭제는 설정된 버킷의 해당 키를 지운다")
    void deletesByKey() {
        service.delete(STORAGE_KEY);

        ArgumentCaptor<DeleteObjectRequest> captor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());

        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo(STORAGE_KEY);
    }

    @Test
    @DisplayName("삭제 실패는 감추지 않고 그대로 던진다")
    void propagatesDeleteFailure() {
        willThrow(SdkClientException.create("connection reset"))
                .given(s3Client).deleteObject(any(DeleteObjectRequest.class));

        assertThatThrownBy(() -> service.delete(STORAGE_KEY))
                .isInstanceOf(SdkClientException.class);
    }
}
