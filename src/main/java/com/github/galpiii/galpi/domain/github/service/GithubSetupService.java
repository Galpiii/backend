package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.auth.store.RefreshTokenStore;
import com.github.galpiii.galpi.domain.auth.support.RedirectUriValidator;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.dto.InstallUrlResponse;
import com.github.galpiii.galpi.domain.github.store.GithubInstallStateStore;
import com.github.galpiii.galpi.domain.github.store.GithubInstallStateStore.InstallIntent;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.util.LogSafe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * App 설치 시작과 setup 콜백을 처리한다.
 *
 * <p>설치 정보는 DB에 저장하지 않는다. {@code installation_id}는 저장소를 연결할 때
 * {@code repositories.installation_id}에 캐시로만 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubSetupService {

    /** 콜백 결과를 프론트에 넘길 때 쓰는 쿼리 파라미터 이름. */
    private static final String RESULT_PARAM = "installation";
    private static final String RESULT_VERIFIED = "verified";
    private static final String RESULT_UNVERIFIED = "unverified";

    private final GithubInstallStateStore installStateStore;
    private final GithubInstallationService installationService;
    private final RedirectUriValidator redirectUriValidator;
    private final RefreshTokenStore refreshTokenStore;
    private final GithubAppProperties properties;

    public InstallUrlResponse buildInstallUrl(Long userId, String returnTo) {
        String validatedReturnTo = redirectUriValidator.validate(returnTo);
        String state = installStateStore.issue(userId, validatedReturnTo);
        return new InstallUrlResponse(properties.installUrl(state));
    }

    /**
     * setup 콜백. 항상 프론트로 리다이렉트할 URL을 돌려준다.
     *
     * <p>{@code setup_action}으로 분기하지 않는다. 공식 문서는 이 파라미터를 아예 언급하지
     * 않고, 실제로는 {@code install}·{@code request}·{@code update}가 관측된다. 값이 늘거나
     * 없어도 흐름이 죽지 않도록 로그로만 남기고, 판단은 {@code installation_id}를 GitHub에
     * 직접 대조한 결과로 한다.
     */
    public String handleSetupCallback(Long installationId, String setupAction,
                                      String state, String refreshToken) {
        log.info("[GitHub] setup 콜백 setupAction={} hasInstallationId={} hasState={}",
                LogSafe.text(setupAction), installationId != null,
                state != null && !state.isBlank());

        Optional<InstallIntent> intent = resolveIntent(state, refreshToken);
        if (intent.isEmpty()) {
            log.warn("[GitHub] 설치를 시작한 기록 없이 setup 콜백이 들어왔다");
            return redirectUriValidator.buildFrontendError(
                    ErrorCode.GITHUB_INSTALL_NOT_STARTED.getCode(), "");
        }

        InstallIntent resolved = intent.get();
        String returnTo = safeReturnTo(resolved.returnTo());

        if (installationId == null) {
            // 조직 관리자 승인 대기(setup_action=request)가 대부분 여기로 온다. 오류가 아니다.
            log.info("[GitHub] installation_id 없이 콜백. 설치 확인 안 됨으로 보낸다 userId={}",
                    resolved.userId());
            return buildResult(RESULT_UNVERIFIED, returnTo);
        }

        if (!ownsInstallationOrUnverified(resolved.userId(), installationId)) {
            return buildResult(RESULT_UNVERIFIED, returnTo);
        }

        log.info("[GitHub] 설치 확인 완료 userId={} installationId={}",
                resolved.userId(), installationId);
        return buildResult(RESULT_VERIFIED, returnTo);
    }

    /**
     * 설치 대조가 실패해도 리다이렉트를 유지한다.
     *
     * <p>이 경로는 GitHub이 브라우저를 직접 보낸 곳이라 무엇이 잘못되든 프론트로 돌려보내야
     * 한다. 토큰 만료·GitHub 장애·목록 상한처럼 대조 자체가 안 되는 경우를 예외로 흘리면
     * 사용자는 리다이렉트 대신 JSON 오류 화면을 본다.
     *
     * <p>확인이 안 된 것과 확인에 실패한 것은 사용자가 할 일이 같다 — 새로고침이다. 스펙의
     * "설치 확인 안 됨" 화면이 그대로 맞고, 원인은 로그로만 구분한다.
     */
    private boolean ownsInstallationOrUnverified(Long userId, Long installationId) {
        try {
            return installationService.ownsInstallation(userId, installationId);
        } catch (GlobalException e) {
            log.warn("[GitHub] 설치 대조에 실패해 확인 안 됨으로 보낸다 userId={} code={}",
                    userId, e.getErrorCode().getCode());
            return false;
        }
    }

    /**
     * 콜백을 시작한 사용자를 찾는다.
     *
     * <p>state가 살아 돌아오면 그것이 가장 강한 근거다. 유실됐으면 갈피 세션 쿠키로 사용자를
     * 특정한 뒤, 그 사용자가 설치를 시작했다는 기록이 남아 있는지까지 확인한다. "로그인만
     * 되어 있으면 통과"로 두면 아무 링크나 눌러도 콜백이 성립한다.
     */
    private Optional<InstallIntent> resolveIntent(String state, String refreshToken) {
        Optional<InstallIntent> byState = installStateStore.consumeState(state);
        if (byState.isPresent()) {
            installStateStore.clearIntent(byState.get().userId());
            return byState;
        }

        return refreshTokenStore.peek(refreshToken)
                .flatMap(installStateStore::consumeIntent);
    }

    /**
     * 발급 시점에 이미 검증한 값이지만 한 번 더 본다. Redis 값이 어떤 경로로든 오염되면
     * 그대로 Location 헤더가 되므로, 저장된 값을 무조건 믿지 않는다.
     */
    private String safeReturnTo(String returnTo) {
        try {
            return redirectUriValidator.validate(returnTo);
        } catch (GlobalException e) {
            log.warn("[GitHub] 저장된 returnTo가 허용 목록 밖이라 버린다");
            return "";
        }
    }

    private String buildResult(String result, String returnTo) {
        return redirectUriValidator.buildFrontendResult(RESULT_PARAM, result, returnTo);
    }
}
