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
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "기능대조", description = "기능명세와 PR 작업 근거의 대조")
public interface FeatureMatchApi {
    @Operation(summary = "기능대조 실행", description = """
            명세서 추출과 PR 분석이 끝난 프로젝트의 대조를 시작합니다. 이전 실행은 정리하고 사용자 연결은 유지합니다.
            분석이 진행 중이면 FEATURE-MATCH-002, 중복 실행이면 FEATURE-MATCH-003입니다.
            AI 동의와 프로젝트 소유권이 필요하며, 응답 후 비동기로 실행됩니다.
            GitHub 재연결이 필요하면 기존 공통 응답 규칙에 따라 401 GITHUB-001입니다.
            """)
    ResponseEntity<ApiResponse<FeatureMatchRunCreatedResponse>> run(AuthPrincipal principal, Long projectId);

    @Operation(summary = "기능대조 진행 상태", description = """
            대상별 처리 건수와 실행 상태·대표 실패 원인을 조회합니다. 여러 원인이 있으면 최빈 원인을 반환합니다.
            실행이 없거나 접근할 수 없으면 FEATURE-MATCH-001입니다.
            """)
    ResponseEntity<ApiResponse<FeatureMatchRunStatusResponse>> status(AuthPrincipal principal, Long id);

    @Operation(summary = "기능대조 요약 및 섹션별 기능 목록", description = """
            완료 또는 일부 완료 실행만 조회합니다. 요약은 전체 실행 기준이며 목록에는 저장소·검색·근거 필터를 적용합니다.
            ATTENTION_REQUIRED는 관련 PR이 없거나 미검토인 기능입니다. 근거 존재는 구현 완료를 뜻하지 않습니다.
            진행 중에는 FEATURE-MATCH-005, 입력 변경 시 FEATURE-MATCH-010입니다.
            """)
    ResponseEntity<ApiResponse<FeatureMatchResultsResponse>> results(
            AuthPrincipal principal,
            Long projectId,
            Long repositoryId,
            String query,
            FeatureMatchFilter filter);

    @Operation(summary = "기능별 관련 PR 조회", description = """
            기능의 요구사항과 저장소별 AI/사용자 PR 연결을 반환합니다. PR 원문 상세는 기존 PR 상세 API를 사용합니다.
            기능 접근 불가 시 FEATURE-REVIEW-001, 실행 기준이 변경되면 FEATURE-MATCH-010입니다.
            """)
    ResponseEntity<ApiResponse<FeatureMatchDetailResponse>> detail(
            AuthPrincipal principal,
            Long featureId,
            Long repositoryId);

    @Operation(summary = "매칭되지 않은 PR 목록", description = """
            대조를 성공했지만 어떤 기능에도 연결되지 않은 PR을 페이지로 반환합니다. 실패·취소 대상은 제외합니다.
            저장소 및 PR 번호·제목·작성자 검색을 지원하며 page는 0부터, size는 1~100입니다.
            잘못된 페이지 값은 COMMON-002, 진행 중인 결과는 FEATURE-MATCH-005입니다.
            """)
    ResponseEntity<ApiResponse<UnmatchedPullRequestListResponse>> unmatched(
            AuthPrincipal principal,
            Long projectId,
            Long repositoryId,
            String query,
            int page,
            int size);

    @Operation(summary = "사용자 PR 직접 연결", description = """
            pullRequestIds 배열의 1~100개 PR을 하나의 기능에 원자적으로 연결합니다. 사용자 연결은 재실행 시 유지됩니다.
            배열 내 중복은 400 COMMON-002, 이미 연결된 쌍은 409 FEATURE-MATCH-008입니다.
            프로젝트 밖의 PR은 PULL-REQUEST-001이며 일부만 저장하지 않습니다.
            """)
    ResponseEntity<ApiResponse<FeaturePrMatchesCreatedResponse>> create(
            AuthPrincipal principal,
            Long featureId,
            FeaturePrMatchesCreateRequest request);

    @Operation(summary = "기능과 PR 연결 삭제", description = """
            AI 또는 사용자 연결만 삭제하며 원본 PR은 유지합니다. AI 연결은 재실행 시 다시 생성될 수 있습니다.
            연결이 없거나 접근할 수 없으면 FEATURE-MATCH-009입니다.
            """)
    ResponseEntity<ApiResponse<Void>> delete(AuthPrincipal principal, Long id);
}
