package com.github.galpiii.galpi.domain.consent.controller;

import com.github.galpiii.galpi.domain.auth.jwt.AuthPrincipal;
import com.github.galpiii.galpi.domain.consent.dto.AiDataConsentRequest;
import com.github.galpiii.galpi.domain.consent.dto.AiDataConsentStatusResponse;
import com.github.galpiii.galpi.domain.consent.service.AiDataConsentService;
import com.github.galpiii.galpi.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "동의")
@RestController
@RequiredArgsConstructor
@RequestMapping("/consents/ai-data")
public class AiDataConsentController {

    private final AiDataConsentService consentService;

    @Operation(summary = "외부 AI 전송 동의 상태",
            description = """
                    현재 고지 버전과 문구, 사용자의 동의 여부를 함께 내린다.
                    agreed가 false면 분석 실행이 403 CONSENT-001로 막히므로 동의 화면을 띄운다.
                    agreedVersion이 있는데 agreed가 false면 정책이 바뀐 재동의 상황이다.""")
    @GetMapping
    public ResponseEntity<ApiResponse<AiDataConsentStatusResponse>> status(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(consentService.status(principal.userId())));
    }

    @Operation(summary = "외부 AI 전송 동의",
            description = """
                    동의 화면에 표시된 고지 버전을 그대로 보낸다. 서버의 현재 버전과 다르면
                    409 CONSENT-002로 거부되므로, 상태를 다시 조회해 최신 문구로 동의를 받는다.
                    같은 버전에 여러 번 보내도 결과는 같다.""")
    @PostMapping
    public ResponseEntity<ApiResponse<AiDataConsentStatusResponse>> agree(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AiDataConsentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                consentService.agree(principal.userId(), request.consentVersion())));
    }
}
