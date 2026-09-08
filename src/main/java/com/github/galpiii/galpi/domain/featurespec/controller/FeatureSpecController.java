package com.github.galpiii.galpi.domain.featurespec.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecStatusResponse;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.service.FeatureSpecService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class FeatureSpecController implements FeatureSpecApi {

    private final FeatureSpecService featureSpecService;

    @Override
    @PostMapping(path = "/projects/{projectId}/feature-specs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FeatureSpecUploadResponse>> uploadFeatureSpec(
            @PathVariable Long projectId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file
    ) {
        FeatureSpecUploadResponse response = featureSpecService.upload(
                projectId,
                principal.userId(),
                file
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @Override
    @PutMapping(path = "/projects/{projectId}/feature-specs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FeatureSpecUploadResponse>> replaceFeatureSpec(
            @PathVariable Long projectId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file
    ) {
        FeatureSpecUploadResponse response = featureSpecService.replace(
                projectId,
                principal.userId(),
                file
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Override
    @GetMapping("/feature-specs/{specDocumentId}/status")
    public ResponseEntity<ApiResponse<FeatureSpecStatusResponse>> getExtractionStatus(
            @PathVariable Long specDocumentId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        FeatureSpecStatusResponse response = featureSpecService.getStatus(
                specDocumentId,
                principal.userId()
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
