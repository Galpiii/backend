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
        description = "기능명세서 PDF 업로드 및 분석 상태 조회 API"
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
                                    PROJECT-002: 프로젝트를 찾을 수 없거나 접근할 수 없음

                                    프로젝트가 존재하지 않는 경우와 다른 사용자의 프로젝트인 경우를
                                    구분하지 않고 동일하게 응답합니다.
                                    """,
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "프로젝트 접근 실패",
                                            value = """
                                                    {
                                                      "code": "PROJECT-002",
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
            summary = "기능명세서 분석 상태 조회",
            description = """
                    기능명세서의 AI 분석 진행 상태를 조회합니다.
                    업로드 응답으로 받은 specDocumentId로 주기적으로 polling합니다.

                    상태:
                    - PENDING: 업로드 완료, 분석 대기
                    - PROCESSING: 분석 진행 중
                    - COMPLETED: 분석 및 결과 저장 완료
                    - FAILED: 자동 재시도까지 포함해 최종 실패

                    failureCode는 FAILED일 때만 내려갑니다.
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
                                    - PROJECT-002: 프로젝트를 찾을 수 없거나 접근할 수 없음
                                    - FEATURE-SPEC-ACCESS-001: 기능명세서를 찾을 수 없거나 접근할 수 없음

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
                    name = "projectId",
                    description = "기능명세서가 속한 프로젝트 ID",
                    in = ParameterIn.PATH,
                    required = true,
                    example = "1"
            )
            Long projectId,

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
