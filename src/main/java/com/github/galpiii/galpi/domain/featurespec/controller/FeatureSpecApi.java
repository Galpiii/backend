package com.github.galpiii.galpi.domain.featurespec.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecStatusResponse;
import com.github.galpiii.galpi.domain.featurespec.dto.response.FeatureSpecUploadResponse;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

@Tag(
        name = "기능명세서",
        description = "기능명세서 PDF 업로드·교체 및 분석 상태 조회 API"
)
public interface FeatureSpecApi {

    @Operation(
            summary = "기능명세서 PDF 업로드",
            description = """
                    기능명세서 PDF를 검증하고 업로드 접수 정보를 저장합니다.
                    정상적으로 접수된 문서는 PENDING 상태로 생성됩니다.

                    허용 파일 조건:
                    - PDF 파일
                    - 최대 20MB
                    - 1페이지 이상 100페이지 이하
                    - 암호화되거나 비밀번호로 보호되지 않은 파일
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "201",
                            description = "기능명세서 업로드 접수 성공",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "업로드 성공",
                                            value = """
                                                    {
                                                      "data": {
                                                        "specDocumentId": 1,
                                                        "fileName": "feature-spec.pdf",
                                                        "extractionStatus": "PENDING"
                                                      }
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = """
                                    파일 검증 실패:
                                    - FEATURE-SPEC-FILE-001: 비어 있음
                                    - FEATURE-SPEC-FILE-002: 원본 파일명 없음
                                    - FEATURE-SPEC-FILE-003: PDF 확장자가 아님
                                    - FEATURE-SPEC-FILE-004: Content-Type이 application/pdf가 아님
                                    - FEATURE-SPEC-FILE-005: 파일 크기가 20MB를 초과함
                                    - FEATURE-SPEC-FILE-006: 파일명이 255자를 초과함
                                    - FEATURE-SPEC-PDF-001: 정상적으로 열 수 없는 PDF
                                    - FEATURE-SPEC-PDF-002: 암호화 또는 비밀번호 보호된 PDF
                                    - FEATURE-SPEC-PDF-003: PDF 페이지가 없음
                                    - FEATURE-SPEC-PDF-004: PDF가 100페이지를 초과함
                                    """,
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "파일 검증 실패",
                                            value = """
                                                    {
                                                      "code": "FEATURE-SPEC-PDF-001",
                                                      "message": "정상적으로 열 수 있는 PDF 파일이 아닙니다."
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = """
                                    PROJECT-001: 프로젝트를 찾을 수 없거나 접근할 수 없음

                                    프로젝트가 존재하지 않는 경우와 다른 사용자의 프로젝트인 경우를
                                    구분하지 않고 동일하게 응답합니다.
                                    """,
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "프로젝트 접근 실패",
                                            value = """
                                                    {
                                                      "code": "PROJECT-001",
                                                      "message": "프로젝트를 찾을 수 없거나 접근할 수 없습니다."
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "409",
                            description = """
                                    FEATURE-SPEC-EXISTS-001: 이미 등록된 기능명세서가 있음

                                    업로드는 등록만 담당합니다. 이미 등록된 기능명세서를 바꾸려면
                                    교체 API를 사용해야 합니다.
                                    """,
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "기능명세서 중복 등록",
                                            value = """
                                                    {
                                                      "code": "FEATURE-SPEC-EXISTS-001",
                                                      "message": "이미 등록된 기능명세서가 있습니다."
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "503",
                            description = """
                                    FEATURE-SPEC-EXTRACTION-001: 분석 요청이 몰려 접수할 수 없음

                                    분석 대기열이 가득 차 업로드를 접수하지 못했습니다.
                                    아무것도 저장되지 않으므로 잠시 후 같은 파일로 다시 업로드하면 됩니다.
                                    """,
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "분석 대기열 포화",
                                            value = """
                                                    {
                                                      "code": "FEATURE-SPEC-EXTRACTION-001",
                                                      "message": "분석 요청이 많아 지금은 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."
                                                    }
                                                    """
                                    )
                            )
                    )
            }
    )
    ResponseEntity<ApiResponse<FeatureSpecUploadResponse>> uploadFeatureSpec(
            @Parameter(
                    name = "projectId",
                    description = "기능명세서를 등록할 프로젝트 ID",
                    in = ParameterIn.PATH,
                    required = true,
                    example = "1"
            )
            Long projectId,

            @Parameter(hidden = true)
            AuthPrincipal principal,

            @Parameter(
                    name = "file",
                    description = "업로드할 기능명세서 PDF 파일",
                    required = true,
                    schema = @Schema(type = "string", format = "binary")
            )
            MultipartFile file
    );

    @Operation(
            summary = "기능명세서 교체",
            description = """
                    등록된 기능명세서를 새 PDF로 바꾸고 다시 분석합니다.

                    기존 추출 결과와 사용자의 검토 기록(병합·분리·확인)은 모두 삭제됩니다.
                    되돌릴 수 없으므로 호출 전에 사용자에게 확인을 받으세요.

                    분석이 진행 중(PENDING·PROCESSING)이면 409로 거절합니다.
                    허용 파일 조건은 업로드와 같습니다.
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "기능명세서 교체 접수 성공. 새 문서가 PENDING 상태로 생성됩니다.",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "교체 성공",
                                            value = """
                                                    {
                                                      "data": {
                                                        "specDocumentId": 2,
                                                        "fileName": "feature-spec-v2.pdf",
                                                        "extractionStatus": "PENDING"
                                                      }
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = """
                                    - PROJECT-001: 프로젝트를 찾을 수 없거나 접근할 수 없음
                                    - FEATURE-SPEC-ACCESS-001: 교체할 기능명세서가 없음

                                    등록된 명세서가 없으면 교체가 아니라 업로드를 사용해야 합니다.
                                    """,
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "교체할 명세서 없음",
                                            value = """
                                                    {
                                                      "code": "FEATURE-SPEC-ACCESS-001",
                                                      "message": "기능명세서를 찾을 수 없거나 접근할 수 없습니다."
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "409",
                            description = """
                                    FEATURE-SPEC-EXTRACTION-002: 분석이 진행 중이라 교체할 수 없음

                                    분석 중인 문서를 지우면 결과를 저장할 자리가 사라집니다.
                                    상태 조회로 COMPLETED 또는 FAILED가 된 뒤에 다시 시도하세요.
                                    """,
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "분석 진행 중",
                                            value = """
                                                    {
                                                      "code": "FEATURE-SPEC-EXTRACTION-002",
                                                      "message": "분석이 진행 중인 기능명세서는 교체할 수 없습니다. 분석이 끝난 뒤 다시 시도해 주세요."
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "503",
                            description = """
                                    분석 요청이 몰려 접수할 수 없음. 기존 명세서가 남았는지가 코드로 갈립니다.

                                    - FEATURE-SPEC-EXTRACTION-001: 삭제 전에 걸렀습니다. 기존 명세서는 그대로 남아 있으므로 잠시 후 교체를 다시 시도하면 됩니다.
                                    - FEATURE-SPEC-EXTRACTION-003: 기존 명세서를 지운 뒤 제출이 거부됐습니다. 되돌릴 원본이 없어 삭제된 상태로 남으므로, 업로드로 다시 등록해야 합니다.
                                    """,
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "분석 대기열 포화",
                                            value = """
                                                    {
                                                      "code": "FEATURE-SPEC-EXTRACTION-003",
                                                      "message": "분석 요청이 많아 지금은 처리할 수 없습니다. 기존 기능명세서는 삭제되었으니 잠시 후 다시 업로드해 주세요."
                                                    }
                                                    """
                                    )
                            )
                    )
            }
    )
    ResponseEntity<ApiResponse<FeatureSpecUploadResponse>> replaceFeatureSpec(
            @Parameter(
                    name = "projectId",
                    description = "기능명세서를 교체할 프로젝트 ID",
                    in = ParameterIn.PATH,
                    required = true,
                    example = "1"
            )
            Long projectId,

            @Parameter(hidden = true)
            AuthPrincipal principal,

            @Parameter(
                    name = "file",
                    description = "새로 등록할 기능명세서 PDF 파일",
                    required = true,
                    schema = @Schema(type = "string", format = "binary")
            )
            MultipartFile file
    );

    @Operation(
            summary = "기능명세서 분석 상태 조회",
            description = """
                    기능명세서의 AI 분석 진행 상태를 조회합니다.
                    업로드 응답으로 받은 specDocumentId로 주기적으로 polling합니다.

                    상태:
                    - PENDING: 업로드 완료, 분석 대기
                    - PROCESSING: 분석 진행 중
                    - COMPLETED: 분석 및 결과 저장 완료
                    - FAILED: 자동 재시도까지 포함해 최종 실패

                    failureCode는 항상 내려가며, FAILED가 아닐 때는 null입니다.
                    - NO_FEATURE_EXTRACTED: 문서에서 기능을 하나도 추출하지 못함. 다른 PDF로 다시 업로드해야 합니다.
                    - ANALYSIS_FAILED: 그 외 실패. 같은 PDF로 다시 업로드할 수 있습니다.

                    분석 재시도 API는 제공하지 않습니다. 최종 실패한 경우 PDF를 다시 업로드합니다.
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "분석 상태 조회 성공",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = {
                                            @ExampleObject(
                                                    name = "분석 진행 중",
                                                    value = """
                                                            {
                                                              "data": {
                                                                "specDocumentId": 1,
                                                                "extractionStatus": "PROCESSING"
                                                              }
                                                            }
                                                            """
                                            ),
                                            @ExampleObject(
                                                    name = "분석 실패",
                                                    value = """
                                                            {
                                                              "data": {
                                                                "specDocumentId": 1,
                                                                "extractionStatus": "FAILED",
                                                                "failureCode": "NO_FEATURE_EXTRACTED"
                                                              }
                                                            }
                                                            """
                                            )
                                    }
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = """
                                    FEATURE-SPEC-ACCESS-001: 기능명세서를 찾을 수 없거나 접근할 수 없음

                                    존재하지 않는 경우와 접근 권한이 없는 경우를 구분하지 않고
                                    동일하게 응답합니다.
                                    """,
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "기능명세서 접근 실패",
                                            value = """
                                                    {
                                                      "code": "FEATURE-SPEC-ACCESS-001",
                                                      "message": "기능명세서를 찾을 수 없거나 접근할 수 없습니다."
                                                    }
                                                    """
                                    )
                            )
                    )
            }
    )
    ResponseEntity<ApiResponse<FeatureSpecStatusResponse>> getExtractionStatus(
            @Parameter(
                    name = "specDocumentId",
                    description = "업로드 응답으로 받은 기능명세서 ID",
                    in = ParameterIn.PATH,
                    required = true,
                    example = "1"
            )
            Long specDocumentId,

            @Parameter(hidden = true)
            AuthPrincipal principal
    );
}
