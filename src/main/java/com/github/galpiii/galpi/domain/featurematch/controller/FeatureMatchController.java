package com.github.galpiii.galpi.domain.featurematch.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFilter;
import com.github.galpiii.galpi.domain.featurematch.dto.request.FeaturePrMatchesCreateRequest;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchDetailResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchResultsResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunCreatedResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunStatusResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeaturePrMatchesCreatedResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.UnmatchedPullRequestListResponse;
import com.github.galpiii.galpi.domain.featurematch.service.FeatureMatchQueryService;
import com.github.galpiii.galpi.domain.featurematch.service.FeatureMatchRunService;
import com.github.galpiii.galpi.domain.featurematch.service.FeaturePrMatchService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class FeatureMatchController implements FeatureMatchApi {
    private final FeatureMatchRunService runs;
    private final FeatureMatchQueryService queries;
    private final FeaturePrMatchService matches;

    @Override
    @PostMapping("/projects/{projectId}/feature-match-runs")
    public ResponseEntity<ApiResponse<FeatureMatchRunCreatedResponse>> run(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(runs.create(projectId, principal.userId())));
    }

    @Override
    @GetMapping("/feature-match-runs/{id}")
    public ResponseEntity<ApiResponse<FeatureMatchRunStatusResponse>> status(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(runs.status(id, principal.userId())));
    }

    @Override
    @GetMapping("/projects/{projectId}/feature-match-results")
    public ResponseEntity<ApiResponse<FeatureMatchResultsResponse>> results(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId,
            @RequestParam(required = false) Long repositoryId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) FeatureMatchFilter filter) {
        return ResponseEntity.ok(ApiResponse.success(queries.results(projectId, principal.userId(), repositoryId, query, filter)));
    }

    @Override
    @GetMapping("/features/{featureId}/feature-match-result")
    public ResponseEntity<ApiResponse<FeatureMatchDetailResponse>> detail(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long featureId,
            @RequestParam(required = false) Long repositoryId) {
        return ResponseEntity.ok(ApiResponse.success(queries.detail(featureId, principal.userId(), repositoryId)));
    }

    @Override
    @GetMapping("/projects/{projectId}/feature-match-results/unmatched-pull-requests")
    public ResponseEntity<ApiResponse<UnmatchedPullRequestListResponse>> unmatched(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long projectId,
            @RequestParam(required = false) Long repositoryId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(queries.unmatched(projectId, principal.userId(), repositoryId, query, page, size)));
    }

    @Override
    @PostMapping("/features/{featureId}/pull-request-matches")
    public ResponseEntity<ApiResponse<FeaturePrMatchesCreatedResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long featureId,
            @Valid @RequestBody FeaturePrMatchesCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(matches.create(featureId, principal.userId(), request)));
    }

    @Override
    @DeleteMapping("/feature-pr-matches/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id) {
        matches.delete(id, principal.userId());
        return ResponseEntity.ok(ApiResponse.success());
    }
}
