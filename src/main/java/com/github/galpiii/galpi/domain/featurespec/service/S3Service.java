package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.global.config.AwsS3Properties;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.UncheckedIOException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private static final String KEY_FORMAT = "feature-specs/%d/%s.pdf";

    private final S3Client s3Client;
    private final AwsS3Properties awsS3Properties;

    // s3 업로드
    public String upload(File file, Long projectId) {
        String storageKey = KEY_FORMAT.formatted(projectId, UUID.randomUUID());

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(awsS3Properties.s3().bucket())
                .key(storageKey)
                .contentType(MediaType.APPLICATION_PDF_VALUE)
                .build();

        try {
            s3Client.putObject(request, RequestBody.fromFile(file));
        } catch (SdkException | UncheckedIOException e) {
            log.error(
                    "[기능명세서 업로드] S3 업로드 실패. projectId: {}, storageKey: {}",
                    projectId,
                    storageKey,
                    e
            );
            throw new GlobalException(ErrorCode.FEATURE_SPEC_STORAGE_UPLOAD_FAILED);
        }

        return storageKey;
    }

    // s3 삭제
    public void delete(String storageKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(awsS3Properties.s3().bucket())
                .key(storageKey)
                .build());
    }
}
