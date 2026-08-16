package com.github.galpiii.galpi.domain.featurespec.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
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
        description = "기능명세서 PDF 업로드 API"
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
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = """
                                    업로드 처리 실패:
                                    - FEATURE-SPEC-STORAGE-001: 원본 파일을 스토리지에 저장하지 못함
                                    - FEATURE-SPEC-SAVE-001: 기능명세서 정보를 저장하지 못함

                                    FEATURE-SPEC-SAVE-001인 경우 이미 저장된 원본 파일은 함께 삭제됩니다.
                                    """,
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    examples = @ExampleObject(
                                            name = "업로드 처리 실패",
                                            value = """
                                                    {
                                                      "code": "FEATURE-SPEC-SAVE-001",
                                                      "message": "기능명세서 정보 저장에 실패했습니다."
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
}
