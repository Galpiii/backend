package com.github.galpiii.galpi.domain.featurespec.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.featurespec.dto.FeatureReviewFilter;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureMergeRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureSplitRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.request.FeatureUpdateRequest;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewResponse;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureReviewSummaryResponse;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(
        name = "기능 검토",
        description = "AI가 추출한 기능 목록 검토 및 수정 API"
)
public interface FeatureReviewApi {

    @Operation(
            summary = "기능 목록 조회",
            description = """
                    검토 화면에 표시할 기능 목록을 원문 분류별로 묶어 조회합니다.

                    페이지를 나누지 않습니다. 업로드가 100페이지 PDF까지만 받아 기능 수가
                    구조적으로 제한되고, 분류로 묶어 보여주는 화면에서 페이지 경계가 분류를
                    가르면 같은 분류 제목이 두 페이지에 나타나기 때문입니다.

                    어느 분류에도 속하지 않는 기능은 sectionId가 null인 묶음으로 맨 뒤에 옵니다.

                    filter (생략하면 ALL):
                    - ALL: 전체
                    - REVIEW_REQUIRED: 확인 필요 — 특이사항이 있는 기능
                    - NO_ISSUE: 미확인 — 특이사항 없이 아직 확인하지 않은 기능
                    - REVIEWED: 검토 완료 — 승인했거나 수정한 기능

                    duplicateCandidates는 이 기능에서 시작할 수 있는 병합만 담습니다. 같은 중복
                    관계는 한 방향으로만 저장되므로, 이 배열이 비어 있지 않은 기능에서만 병합
                    버튼을 노출하면 됩니다.
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "기능 목록 조회 성공",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "조회 성공",
                                            value = """
                                                    {
                                                      "data": {
                                                        "sections": [
                                                          {
                                                            "sectionId": 3,
                                                            "title": "게시글",
                                                            "features": [
                                                              {
                                                                "featureId": 12,
                                                                "name": "게시글 작성",
                                                                "reviewStatus": "UNREVIEWED",
                                                                "sourcePageStart": 3,
                                                                "sourcePageEnd": 4,
                                                                "requirements": [
                                                                  {
                                                                    "requirementId": 34,
                                                                    "content": "게시글을 작성한다.",
                                                                    "sourceText": "사용자는 게시글을 작성할 수 있다."
                                                                  },
                                                                  {
                                                                    "requirementId": 35,
                                                                    "content": "댓글을 작성한다.",
                                                                    "sourceText": "사용자는 댓글을 작성할 수 있다."
                                                                  }
                                                                ],
                                                                "issues": [
                                                                  {
                                                                    "issueType": "SPLIT_RECOMMENDED",
                                                                    "description": "서로 다른 대상에 대한 요구사항이 섞여 있습니다."
                                                                  },
                                                                  {
                                                                    "issueType": "DUPLICATE_SUSPECTED",
                                                                    "description": "글쓰기와 요구사항이 겹칩니다."
                                                                  }
                                                                ],
                                                                "duplicateCandidates": [
                                                                  {
                                                                    "targetFeatureId": 19,
                                                                    "targetFeatureName": "글쓰기",
                                                                    "targetSourcePageStart": 6,
                                                                    "targetSourcePageEnd": 7,
                                                                    "targetRequirements": [
                                                                      { "requirementId": 51, "content": "글을 쓴다.", "sourceText": "사용자는 글을 쓸 수 있다." }
                                                                    ],
                                                                    "reason": "게시글 작성과 동일한 흐름을 설명합니다.",
                                                                    "suggestedMergedName": "게시글 작성",
                                                                    "suggestedSection": "게시글"
                                                                  }
                                                                ],
                                                                "splitSuggestions": [
                                                                  {
                                                                    "suggestionId": 7,
                                                                    "suggestedName": "게시글 관리",
                                                                    "suggestedSection": "게시글",
                                                                    "requirements": [
                                                                      { "requirementId": 34, "content": "게시글을 작성한다.", "sourceText": "사용자는 게시글을 작성할 수 있다." }
                                                                    ]
                                                                  },
                                                                  {
                                                                    "suggestionId": 8,
                                                                    "suggestedName": "댓글 관리",
                                                                    "suggestedSection": "댓글",
                                                                    "requirements": [
                                                                      { "requirementId": 35, "content": "댓글을 작성한다.", "sourceText": "사용자는 댓글을 작성할 수 있다." }
                                                                    ]
                                                                  }
                                                                ]
                                                              }
                                                            ]
                                                          },
                                                          {
                                                            "sectionId": null,
                                                            "title": null,
                                                            "features": []
                                                          }
                                                        ]
                                                      }
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = """
                                    FEATURE-SPEC-ACCESS-001: 기능명세서를 찾을 수 없거나 접근할 수 없음
                                    """
                    )
            }
    )
    ResponseEntity<ApiResponse<FeatureReviewResponse>> getFeatures(
            @Parameter(name = "specDocumentId", in = ParameterIn.PATH, required = true, example = "1")
            Long specDocumentId,

            @Parameter(hidden = true)
            AuthPrincipal principal,

            @Parameter(
                    name = "filter",
                    description = "탭 필터. 생략하면 전체",
                    in = ParameterIn.QUERY,
                    example = "REVIEW_REQUIRED"
            )
            FeatureReviewFilter filter
    );

    @Operation(
            summary = "검토 진행 상황 요약",
            description = """
                    탭별 개수와 남은 미검토 기능 수를 조회합니다.

                    기능-PR 대조를 시작하기 전에 호출해 "아직 확인하지 않은 기능이 있습니다.
                    그래도 대조하시겠습니까?"를 물어볼지 판단하는 데 씁니다.
                    미검토 기능 수는 total - reviewed입니다.

                    세 값은 서로 겹치지 않고 합하면 total이 됩니다.
                    - reviewRequired: 특이사항이 있는 기능
                    - noIssue: 특이사항 없이 아직 확인하지 않은 기능
                    - reviewed: 승인했거나 수정한 기능
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "요약 조회 성공",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "미검토 기능이 남아 있음",
                                            value = """
                                                    {
                                                      "data": {
                                                        "reviewRequired": 5,
                                                        "noIssue": 7,
                                                        "reviewed": 25,
                                                        "total": 37
                                                      }
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = """
                                    FEATURE-SPEC-ACCESS-001: 기능명세서를 찾을 수 없거나 접근할 수 없음
                                    """
                    )
            }
    )
    ResponseEntity<ApiResponse<FeatureReviewSummaryResponse>> getReviewSummary(
            @Parameter(name = "specDocumentId", in = ParameterIn.PATH, required = true, example = "1")
            Long specDocumentId,

            @Parameter(hidden = true)
            AuthPrincipal principal
    );

    @Operation(
            summary = "변경 없이 모두 승인",
            description = """
                    아직 확인하지 않은 기능을 모두 승인 처리합니다. 특이사항이 있는 기능도
                    포함합니다 — "AI가 알려준 특이사항까지 확인했지만 수정 없이 현재 추출 결과를
                    그대로 사용하겠다"는 선택입니다.

                    이미 승인했거나 수정한 기능은 변경하지 않습니다.
                    승인된 기능의 특이사항·중복 후보·분리 추천안은 함께 삭제됩니다.
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "일괄 승인 성공"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = """
                                    FEATURE-SPEC-ACCESS-001: 기능명세서를 찾을 수 없거나 접근할 수 없음
                                    """
                    )
            }
    )
    ResponseEntity<ApiResponse<Void>> confirmAllFeatures(
            @Parameter(name = "specDocumentId", in = ParameterIn.PATH, required = true, example = "1")
            Long specDocumentId,

            @Parameter(hidden = true)
            AuthPrincipal principal
    );

    @Operation(
            summary = "기능 수정",
            description = """
                    기능명과 세부 요구사항을 한 번에 수정합니다. 임시 저장은 없으며 요청 즉시
                    반영됩니다. 수정한 기능은 USER_MODIFIED가 됩니다.

                    requirements는 전체 치환입니다. 보낸 목록이 그대로 저장 후의 목록이 됩니다.
                    - id가 있으면 해당 요구사항을 수정합니다.
                    - id가 없으면 새로 추가합니다. 사용자가 직접 입력한 문장이므로 sourceText는 null입니다.
                    - 기존 요구사항이 목록에서 빠져 있으면 삭제합니다.
                    - 표시 순서는 배열 순서를 따릅니다.

                    **필드를 보내지 않는 것과 빈 배열은 다릅니다.** requirements를 보내지 않으면
                    요구사항을 변경하지 않고, []를 보내면 전부 삭제합니다.

                    기존 요구사항의 sourceText는 content를 수정해도 변경되지 않습니다.

                    **수정하면 이 기능의 특이사항·중복 후보·분리 추천안이 모두 삭제됩니다.**
                    직접 수정했다는 것은 AI 제안을 따르지 않기로 했다는 뜻이기 때문입니다.
                    따라서 수정 후에는 이 기능에서 병합·분리를 시작할 수 없습니다.

                    **다른 기능이 이 기능을 중복 상대로 지목한 후보도 함께 삭제됩니다.**
                    그 제안은 수정 전 내용을 근거로 만들어진 것이라 더 이상 성립하지 않습니다.
                    상대가 사라진 중복 의심 표시도 함께 정리되므로, 이 기능을 지목하던 기능의
                    duplicateCandidates와 배지가 사라질 수 있습니다.
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "수정 성공. 수정된 기능을 반환합니다"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = """
                                    - FEATURE-002: 변경할 값이 없음
                                    - FEATURE-003: 이 기능의 세부 요구사항이 아닌 id를 보냄
                                    - FEATURE-007: 같은 id를 두 번 보냄
                                    - COMMON-002: 기능명 또는 요구사항 내용이 유효하지 않음
                                    """
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = "FEATURE-001: 기능을 찾을 수 없거나 접근할 수 없음"
                    )
            }
    )
    ResponseEntity<ApiResponse<FeatureReviewResponse.Feature>> updateFeature(
            @Parameter(name = "specDocumentId", in = ParameterIn.PATH, required = true, example = "1")
            Long specDocumentId,

            @Parameter(name = "featureId", in = ParameterIn.PATH, required = true, example = "12")
            Long featureId,

            @Parameter(hidden = true)
            AuthPrincipal principal,

            FeatureUpdateRequest request
    );

    @Operation(
            summary = "기능 승인",
            description = """
                    추출 결과를 변경 없이 그대로 사용합니다. USER_CONFIRMED가 됩니다.

                    이 기능의 특이사항·중복 후보·분리 추천안이 함께 삭제됩니다.
                    이미 검토가 끝난 기능에 다시 요청해도 오류가 아닙니다.

                    수정과 달리, 다른 기능이 이 기능을 중복 상대로 지목한 후보는 그대로 둡니다.
                    승인은 이 기능에 붙은 특이사항에 대한 답이고, 중복 여부는 지목한 쪽 카드에서만
                    묻는 질문이라 승인으로 답한 적이 없기 때문입니다.
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "승인 성공"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = "FEATURE-001: 기능을 찾을 수 없거나 접근할 수 없음"
                    )
            }
    )
    ResponseEntity<ApiResponse<Void>> confirmFeature(
            @Parameter(name = "specDocumentId", in = ParameterIn.PATH, required = true, example = "1")
            Long specDocumentId,

            @Parameter(name = "featureId", in = ParameterIn.PATH, required = true, example = "12")
            Long featureId,

            @Parameter(hidden = true)
            AuthPrincipal principal
    );

    @Operation(
            summary = "기능 병합",
            description = """
                    중복으로 판단된 두 기능을 하나로 합칩니다.

                    기존 기능 중 하나를 수정하는 것이 아니라 **새 기능을 만들고 두 기능은
                    삭제**합니다. 새 기능은 USER_MODIFIED이며 특이사항을 물려받지 않습니다.

                    - 두 기능의 세부 요구사항을 모두 복사합니다. 비슷한 요구사항이 있어도 서버가
                      임의로 중복 제거하지 않습니다. 필요하면 병합 후 수정으로 정리하세요.
                    - 분류는 AI가 제안한 suggestedSection을 사용합니다. 같은 이름의 분류가 없으면
                      새로 만듭니다.
                    - 원문 페이지 범위는 두 기능을 합친 범위입니다.
                    - 목록에서의 위치는 두 기능 중 앞선 쪽을 따릅니다.

                    AI가 중복으로 지목한 방향에서만 병합할 수 있습니다. 목록 응답의
                    duplicateCandidates가 비어 있지 않은 기능에서 호출하세요.
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "병합 성공"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = "FEATURE-004: 중복으로 판단된 기능이 아님"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = "FEATURE-001: 기능을 찾을 수 없거나 접근할 수 없음"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "409",
                            description = """
                                    FEATURE-006: 다른 요청이 먼저 처리되어 대상이 변경됨

                                    같은 병합이 두 번 접수된 경우입니다. 아무것도 저장되지 않으므로
                                    목록을 다시 불러오면 됩니다.
                                    """
                    )
            }
    )
    ResponseEntity<ApiResponse<Void>> mergeFeature(
            @Parameter(name = "specDocumentId", in = ParameterIn.PATH, required = true, example = "1")
            Long specDocumentId,

            @Parameter(name = "featureId", in = ParameterIn.PATH, required = true, example = "12")
            Long featureId,

            @Parameter(hidden = true)
            AuthPrincipal principal,

            FeatureMergeRequest request
    );

    @Operation(
            summary = "기능 분리",
            description = """
                    AI가 추천한 분리안대로 기능 하나를 여러 기능으로 나눕니다.

                    기존 기능의 요구사항을 옮기는 것이 아니라 **새 기능과 요구사항을 만들고 원래
                    기능은 삭제**합니다. 새 기능들은 USER_MODIFIED이며 특이사항을 물려받지 않습니다.

                    - 어느 요구사항이 어느 기능으로 갈지는 저장된 추천안이 정합니다. 요청에는
                      각 추천안의 최종 기능명만 보냅니다.
                    - 저장된 추천안 전체를 보내야 합니다. 일부만 보내면 400입니다.
                    - 원문 페이지 범위는 원래 기능의 값을 물려받습니다.
                    - 목록에서의 위치는 원래 기능의 자리이며, 나뉜 기능들이 그 자리에 차례로 놓입니다.

                    요구사항 이동이나 추천 기능 추가·삭제 같은 자유 편집은 제공하지 않습니다.
                    분리 후 생성된 기능은 일반 기능이므로 수정 API를 사용할 수 있습니다.
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "분리 성공"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = """
                                    FEATURE-005: 적용할 수 있는 분리 추천안이 아님

                                    분리 추천안이 없는 기능이거나, 보낸 suggestionId가 저장된 추천안과
                                    정확히 일대일로 대응하지 않는 경우입니다. 일부만 보내거나 같은
                                    suggestionId를 두 번 보내면 거절합니다. 기능을 수정하면 추천안이
                                    삭제되므로 이후에는 분리할 수 없습니다.
                                    """
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = "FEATURE-001: 기능을 찾을 수 없거나 접근할 수 없음"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "409",
                            description = "FEATURE-006: 다른 요청이 먼저 처리되어 대상이 변경됨"
                    )
            }
    )
    ResponseEntity<ApiResponse<Void>> splitFeature(
            @Parameter(name = "specDocumentId", in = ParameterIn.PATH, required = true, example = "1")
            Long specDocumentId,

            @Parameter(name = "featureId", in = ParameterIn.PATH, required = true, example = "12")
            Long featureId,

            @Parameter(hidden = true)
            AuthPrincipal principal,

            FeatureSplitRequest request
    );

    @Operation(
            summary = "기능 삭제",
            description = """
                    잘못 추출된 기능을 삭제합니다. 복원 기능과 삭제 이력은 제공하지 않습니다.

                    세부 요구사항·특이사항·중복 후보·분리 추천안이 함께 삭제됩니다.
                    다른 기능이 이 기능을 중복 상대로 지목하고 있었다면, 상대가 사라진 중복 의심
                    표시도 함께 정리됩니다.

                    기능이 하나도 남지 않아도 서버가 막지 않습니다.
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "삭제 성공"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = "FEATURE-001: 기능을 찾을 수 없거나 접근할 수 없음"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "409",
                            description = "FEATURE-006: 다른 요청이 먼저 처리되어 대상이 변경됨"
                    )
            }
    )
    ResponseEntity<ApiResponse<Void>> deleteFeature(
            @Parameter(name = "specDocumentId", in = ParameterIn.PATH, required = true, example = "1")
            Long specDocumentId,

            @Parameter(name = "featureId", in = ParameterIn.PATH, required = true, example = "12")
            Long featureId,

            @Parameter(hidden = true)
            AuthPrincipal principal
    );
}
