package com.github.galpiii.galpi.domain.featurespec.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.featurespec.dto.FeatureReviewFilter;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureMergeRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureSplitRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureUpdateRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewResponse;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewSummaryResponse;
import com.github.galpiii.galpi.domain.featurespec.service.FeatureReviewService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/feature-specs/{specDocumentId}")
public class FeatureReviewController implements FeatureReviewApi {

    private final FeatureReviewService featureReviewService;

    @Override
    @GetMapping("/features")
    public ResponseEntity<ApiResponse<FeatureReviewResponse>> getFeatures(
            @PathVariable Long projectId,
            @PathVariable Long specDocumentId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) FeatureReviewFilter filter
    ) {
        FeatureReviewResponse response = featureReviewService.list(
                projectId,
                specDocumentId,
                principal.userId(),
                filter
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Override
    @GetMapping("/review-summary")
    public ResponseEntity<ApiResponse<FeatureReviewSummaryResponse>> getReviewSummary(
            @PathVariable Long projectId,
            @PathVariable Long specDocumentId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        FeatureReviewSummaryResponse response = featureReviewService.summary(
                projectId,
                specDocumentId,
                principal.userId()
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Override
    @PostMapping("/features/confirm-all")
    public ResponseEntity<ApiResponse<Void>> confirmAllFeatures(
            @PathVariable Long projectId,
            @PathVariable Long specDocumentId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        featureReviewService.confirmAll(projectId, specDocumentId, principal.userId());

        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    @PatchMapping("/features/{featureId}")
    public ResponseEntity<ApiResponse<FeatureReviewResponse.Feature>> updateFeature(
            @PathVariable Long projectId,
            @PathVariable Long specDocumentId,
            @PathVariable Long featureId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody FeatureUpdateRequest request
    ) {
        FeatureReviewResponse.Feature response = featureReviewService.update(
                projectId,
                specDocumentId,
                principal.userId(),
                featureId,
                request
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Override
    @PostMapping("/features/{featureId}/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmFeature(
            @PathVariable Long projectId,
            @PathVariable Long specDocumentId,
            @PathVariable Long featureId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        featureReviewService.confirm(projectId, specDocumentId, principal.userId(), featureId);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    @PostMapping("/features/{featureId}/merge")
    public ResponseEntity<ApiResponse<Void>> mergeFeature(
            @PathVariable Long projectId,
            @PathVariable Long specDocumentId,
            @PathVariable Long featureId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody FeatureMergeRequest request
    ) {
        featureReviewService.merge(projectId, specDocumentId, principal.userId(), featureId, request);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    @PostMapping("/features/{featureId}/split")
    public ResponseEntity<ApiResponse<Void>> splitFeature(
            @PathVariable Long projectId,
            @PathVariable Long specDocumentId,
            @PathVariable Long featureId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody FeatureSplitRequest request
    ) {
        featureReviewService.split(projectId, specDocumentId, principal.userId(), featureId, request);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    @DeleteMapping("/features/{featureId}")
    public ResponseEntity<ApiResponse<Void>> deleteFeature(
            @PathVariable Long projectId,
            @PathVariable Long specDocumentId,
            @PathVariable Long featureId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        featureReviewService.delete(projectId, specDocumentId, principal.userId(), featureId);

        return ResponseEntity.ok(ApiResponse.success());
    }
}
