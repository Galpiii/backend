package com.github.galpiii.galpi.domain.featurematch.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFilter;
import com.github.galpiii.galpi.domain.featurematch.dto.request.FeaturePrMatchesCreateRequest;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchDetailResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchChangesResponse;
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
            명세서 추출과 PR 분석이 끝난 프로젝트의 전체 대조를 시작합니다. 성공한 대상은 현재 결과에 반영하고 사용자 연결은 유지합니다.
            분석이 진행 중이면 FEATURE-MATCH-002, 중복 실행이면 FEATURE-MATCH-003입니다.
            AI 동의와 프로젝트 소유권이 필요하며, 응답 후 비동기로 실행됩니다.
            GitHub 재연결이 필요하면 기존 공통 응답 규칙에 따라 401 GITHUB-001입니다.
            """)
    ResponseEntity<ApiResponse<FeatureMatchRunCreatedResponse>> run(AuthPrincipal principal, Long projectId);

    @Operation(summary = "변경된 PR 부분 재대조", description = """
            변경된 PR만 현재 기능 전체와 비동기로 재대조합니다(202). 기능 삭제만 있으면 LLM 호출 없이 즉시 정리합니다(200).
            기능 추가·수정(또는 이전 기능별 기준을 알 수 없는 변경)은 FEATURE-MATCH-013으로 전체 대조가 필요합니다.
            프로젝트의 PR 수집 또는 분석이 하나라도 진행 중이면 FEATURE-MATCH-002로 거절합니다.
            원본이 변경됐지만 PR 재분석에 실패·취소된 대상만 남아도 FEATURE-MATCH-002입니다.
            변경사항이 없으면 FEATURE-MATCH-012, 실행이 이미 진행 중이면 FEATURE-MATCH-003입니다.
            기준 결과가 없으면 FEATURE-MATCH-006, 대조할 기능이 없으면 FEATURE-MATCH-004,
            기능 목록이 입력 상한을 넘으면 FEATURE-MATCH-011입니다. LLM 호출 시 AI 동의(CONSENT-001)와
            GitHub 연결이 필요하며, 재연결이 필요하면 401 GITHUB-001입니다.
            """)
    ResponseEntity<ApiResponse<FeatureMatchRunCreatedResponse>> partial(AuthPrincipal principal, Long projectId);

    @Operation(summary = "최신 기능대조 실행 조회", description = """
            프로젝트의 최신 실행을 실행 ID 내림차순으로 조회합니다. 대기·진행·완료·일부 완료·실패·취소 상태를 모두 반환합니다.
            새로고침, 화면 복귀 또는 실행 요청 응답 유실 시 실행 ID와 진행 상태를 복구할 수 있습니다.
            입력 변경 여부와 무관하게 실행 상태를 반환하며, 결과의 최신 여부는 결과 조회 또는 changes API에서 확인합니다.
            실행 이력이 없으면 404 FEATURE-MATCH-001, 프로젝트가 없거나 접근할 수 없으면 404 PROJECT-001입니다.
            진행 중인 실행의 중복 생성은 기존 실행 API에서 409 FEATURE-MATCH-003으로 거부합니다.
            """)
    ResponseEntity<ApiResponse<FeatureMatchRunStatusResponse>> latest(AuthPrincipal principal, Long projectId);

    @Operation(summary = "기능대조 진행 상태", description = """
            대상별 처리 건수와 실행 상태·대표 실패 원인을 조회합니다. 여러 원인이 있으면 최빈 원인을 반환합니다.
            실행이 없거나 접근할 수 없으면 FEATURE-MATCH-001입니다.
            """)
    ResponseEntity<ApiResponse<FeatureMatchRunStatusResponse>> status(AuthPrincipal principal, Long id);

    @Operation(summary = "기능대조 요약 및 섹션별 기능 목록", description = """
            현재 표시 결과가 있으면 새 실행 중에도 조회할 수 있습니다. 실행 ID·상태는 기준 FULL 실행이며,
            요약은 현재 프로젝트 데이터와 PR별 마지막 종료된 대조 시도를 기준으로 합니다. 목록에는 저장소·검색·근거 필터를 적용합니다.
            ATTENTION_REQUIRED는 관련 PR이 없거나 미검토인 기능입니다. 근거 존재는 구현 완료를 뜻하지 않습니다.
            변경사항과 프로젝트 전체 최신 여부는 changes에 반환합니다.
            """)
    ResponseEntity<ApiResponse<FeatureMatchResultsResponse>> results(
            AuthPrincipal principal,
            Long projectId,
            Long repositoryId,
            String query,
            FeatureMatchFilter filter);

    @Operation(summary = "기능대조 변경사항 확인", description = "저장된 PR 분석과 기능명세를 현재 결과 기준과 비교합니다. 이전 대조 결과가 없는 PR은 분석이 완료되기 전까지 freshness를 바꾸지 않습니다. 변경 PR 목록은 분석 완료 후 대조 가능한 대상만 반환하며, PR 수집·분석이 준비되지 않았으면 비웁니다. rerunBlockReasons로 분석 미준비와 원본 변경 후 재분석 실패·취소를 구분하며 PR별 재분석 대상은 PR 분석 API에서 조회합니다. GitHub 수집이나 AI 호출은 시작하지 않습니다.")
    ResponseEntity<ApiResponse<FeatureMatchChangesResponse>> changes(AuthPrincipal principal, Long projectId);

    @Operation(summary = "기능별 관련 PR 조회", description = """
            기능의 요구사항과 저장소별 AI/사용자 PR 연결을 반환합니다. PR 원문 상세는 기존 PR 상세 API를 사용합니다.
            기능 접근 불가 시 FEATURE-REVIEW-001입니다. 프로젝트 전체 입력 변경 여부는 freshness에 반환합니다.
            """)
    ResponseEntity<ApiResponse<FeatureMatchDetailResponse>> detail(
            AuthPrincipal principal,
            Long featureId,
            Long repositoryId);

    @Operation(summary = "매칭되지 않은 PR 목록", description = """
            과거 대조에 성공했지만 현재 표시 연결이 없는 PR을 페이지로 반환합니다. 대조를 성공한 적 없는 PR은 제외합니다.
            이후 PR 재분석이 실패·취소돼도 이전 미매칭 결과는 유지되며, 분석 요약은 비어 있을 수 있습니다.
            저장소 및 PR 번호·제목·작성자 검색을 지원하며 page는 0부터, size는 1~100입니다.
            잘못된 페이지 값은 COMMON-002, 첫 실행이 진행 중이라 현재 결과가 없으면 FEATURE-MATCH-005입니다.
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
