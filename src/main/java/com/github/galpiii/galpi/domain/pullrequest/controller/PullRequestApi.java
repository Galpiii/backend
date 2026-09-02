package com.github.galpiii.galpi.domain.pullrequest.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestAnalysisRetryResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestDetailResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestListResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestOverviewResponse;
import com.github.galpiii.galpi.domain.pullrequest.dto.PullRequestSort;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "PR", description = "수집한 PR 목록·상세 조회와 AI 요약 재시도")
public interface PullRequestApi {

    @Operation(
            summary = "PR 목록",
            description = """
                    프로젝트에 연결된 저장소들의 PR을 한 목록으로 준다.

                    **저장소별로 묶어 주지 않는다.** 그룹핑은 페이지네이션과 충돌한다 — 저장소
                    단위로 묶으면 한 페이지에 어떤 저장소가 몇 건 들어갈지 서버가 정할 수 없다.
                    각 항목의 repository를 보고 프론트가 묶는다. 저장소별 "PR 16개" 배지는
                    /pull-requests/summary가 준다.

                    **요약 본문(summary)은 목록에 없다.** 화면이 목록에서 쓰지 않고, 수십 건 ×
                    300자를 필터를 바꿀 때마다 실어 나를 이유가 없다. 상세에서만 준다.

                    state는 항상 MERGED다. 수집기가 병합된 PR만 가져오고 merged_at이 NOT NULL이라
                    다른 값이 될 수 없다. excluded도 항상 false다 — pr_exclusions는 테이블만 있고
                    쓰는 코드가 없다.

                    author는 null일 수 있다. GitHub 계정이 삭제된 PR이고, 그때 프론트가
                    "알 수 없음"을 그린다.

                    analysis는 인계 전이거나 제외된 PR에서 null이다. analysisStatus 필터를
                    주지 않으면 그런 PR도 함께 나온다.

                    q: 숫자면 PR 번호 정확 일치 또는 제목 부분 일치, 숫자가 아니면 제목·작성자
                    부분 일치다. 숫자일 때 작성자를 보지 않는 이유는 "12"로 찾을 때 dev12가
                    섞이면 번호로 찾은 것인지 사람으로 찾은 것인지 알 수 없기 때문이다.

                    repositoryId가 이 프로젝트의 저장소가 아니면 PROJECT-002다. 남의 프로젝트와
                    삭제된 프로젝트는 PROJECT-001(404)이며, 연결을 끊은 저장소의 PR은 나오지
                    않는다.""")
    ResponseEntity<ApiResponse<PullRequestListResponse>> list(
            AuthPrincipal principal,
            Long projectId,
            Long repositoryId,
            String authorLogin,
            String query,
            PullRequestAnalysisStatus analysisStatus,
            PullRequestSort sort,
            Integer page,
            Integer size);

    @Operation(
            summary = "PR 목록 헤더 집계",
            description = """
                    목록 화면 머리말의 숫자들. 목록과 분리한 이유는 두 값의 성격이 다르기
                    때문이다 — 헤더 숫자는 필터와 무관한 프로젝트 전체 기준이고, 필터가 바뀔
                    때마다 전체 집계를 다시 돌 이유가 없다.

                    excludedCount는 항상 0이다. pr_exclusions에 쓰는 코드가 아직 없다.

                    criteria는 화면의 "기준 · Merge됨 PR · 각 저장소 기본 브랜치 · 전체 기간"
                    문구용이며 **셋 다 고정값이다.** analysis_configs에 쓰는 경로가 없어 사용자가
                    수집 기준을 바꿀 방법이 없다. 설정 화면이 생기면 그때 실제 값이 되므로,
                    프론트는 이 값을 읽어 문구를 그리고 하드코딩하지 않는다.

                    repositories에는 PR이 아직 하나도 없는 저장소도 0으로 들어간다. 방금 연결한
                    저장소가 화면에서 사라지지 않게 하기 위해서다.""")
    ResponseEntity<ApiResponse<PullRequestOverviewResponse>> overview(
            AuthPrincipal principal, Long projectId);

    @Operation(
            summary = "PR 상세",
            description = """
                    화면의 두 카드(GITHUB 원본 / AI 분석)를 채울 값을 전부 준다.

                    경로에 projectId가 없다. pullRequestId가 전역 고유하고 소유권은
                    pull_requests → repositories → projects.owner_id로 확인할 수 있어서,
                    /feature-specs/{specDocumentId}와 같은 형태를 쓴다. 남의 PR·삭제된
                    프로젝트의 PR·연결을 끊은 저장소의 PR은 모두 PULL-REQUEST-001(404)다 —
                    403으로 구분하면 id를 훑어 남의 PR 존재 여부를 알 수 있다.

                    **patch 필드는 없다.** diff 원문은 DB에 저장하지 않고 응답에도 나가지
                    않는다. 변경 내용을 보여줘야 하면 htmlUrl로 GitHub에 보낸다.

                    **"관련 기능"을 채울 필드도 없다.** 기능대조가 아직 없어 빈 배열조차 내리지
                    않는다 — 빈 배열은 "대조했는데 없다"로 읽히고 지금은 "대조하지 않았다"가
                    맞다. 프론트가 "기능대조를 실행하면 표시됩니다" 빈 상태를 그린다.

                    files나 commits가 각 상한을 넘으면 앞쪽만 담고
                    filesTruncated/commitsTruncated가 true가 된다. 전체 파일 개수는
                    stats.changedFilesCount에 그대로 남는다.

                    body와 commits[].message는 수집 시점에 마스킹을 거친 값이다.

                    analysis가 null이면 아직 인계되지 않은 PR이다. FAILED나 CANCELLED면
                    summary는 null이고 errorCode에 사유가 담긴다.""")
    ResponseEntity<ApiResponse<PullRequestDetailResponse>> detail(
            AuthPrincipal principal, Long pullRequestId);

    @Operation(
            summary = "실패한 요약 재시도",
            description = """
                    화면의 "실패한 PR만 다시 분석". FAILED인 요약을 PENDING으로 되돌리고
                    시도 횟수를 0으로 초기화한다. 워커가 다음 폴링에서 집어 간다.

                    진행 중(PENDING/RUNNING)인 요약은 건드리지 않는다. 돌고 있는 요약을
                    되돌리면 워커가 끝낸 결과와 이 갱신이 서로를 덮는다.

                    **되돌릴 것이 0건이어도 에러가 아니다.** 화면을 본 시점과 워커가 마지막
                    실패를 처리한 시점이 어긋나 있을 뿐이고, 그때 4xx를 주면 "이미 다 됐다"가
                    "실패했다"로 보인다. requeuedCount: 0으로 그 사실만 알린다.

                    외부 AI 전송 동의를 여기서 확인한다(CONSENT-001). 워커는 세션 없이 돌지만,
                    실제 AI 전송 직전에 requestedBy로 현재 동의를 한 번 더 확인한다.

                    repositoryId로 저장소 하나만 좁힐 수 있다. 이 프로젝트의 저장소가 아니면
                    PROJECT-002다.""")
    ResponseEntity<ApiResponse<PullRequestAnalysisRetryResponse>> retry(
            AuthPrincipal principal, Long projectId, Long repositoryId);
}
