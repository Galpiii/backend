package com.github.galpiii.galpi.domain.featurespec.controller;

import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.domain.featurespec.service.FeatureSpecService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/feature-specs")
public class FeatureSpecController implements FeatureSpecApi {

    private final FeatureSpecService featureSpecService;

    @Override
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FeatureSpecUploadResponse>> uploadFeatureSpec(
            @PathVariable Long projectId,
            @RequestHeader("X-USER-ID") Long userId,
            @RequestPart("file") MultipartFile file
    ) {
        FeatureSpecUploadResponse response = featureSpecService.upload(
                projectId,
                userId,
                file
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }
}
