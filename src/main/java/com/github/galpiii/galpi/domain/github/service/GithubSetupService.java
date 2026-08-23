package com.github.galpiii.galpi.domain.github.service;

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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** 설치 화면에 나가기 전 골라 둔 저장소를 그대로 돌려주는 파라미터. */
    private static final String SELECTED_PARAM = "selectedRepositoryIds";

    /** 그중 돌아와 보니 접근할 수 없게 된 것. 선택에서 빠졌다는 사실을 화면이 알려야 한다. */
    private static final String UNAVAILABLE_PARAM = "unavailableRepositoryIds";

    private final GithubInstallStateStore installStateStore;
    private final GithubInstallationService installationService;
    private final RedirectUriValidator redirectUriValidator;
    private final GithubAppProperties properties;

    /**
     * @param selectedRepositoryIds ② 단계에서 이미 골라 둔 저장소. 설치 화면으로 나갔다
     *                              돌아오면 프론트 상태가 날아가므로 서버가 들고 있는다
     */
    public InstallUrlResponse buildInstallUrl(Long userId, String returnTo,
                                              List<Long> selectedRepositoryIds) {
        String validatedReturnTo = redirectUriValidator.validate(returnTo);
        String state = installStateStore.issue(userId, validatedReturnTo, selectedRepositoryIds);
        return new InstallUrlResponse(properties.installUrl(state));
    }

    /**
     * setup 콜백. 항상 프론트로 리다이렉트할 URL을 돌려준다.
     *
     * <p>{@code setup_action}으로 분기하지 않는다. 공식 문서는 이 파라미터를 아예 언급하지
     * 않고, 실제로는 {@code install}·{@code request}·{@code update}가 관측된다. 값이 늘거나
     * 없어도 흐름이 죽지 않도록 로그로만 남기고, 판단은 {@code installation_id}를 GitHub에
     * 직접 대조한 결과로 한다.
     *
     * <p>사용자를 찾는 근거는 {@code state} 하나다. 유실되면 여기서 끝내고 프론트로 돌려보낸다.
     * 설치 자체는 목록 조회가 GitHub에 직접 묻기 때문에 새로고침으로 복구된다.
     */
    public String handleSetupCallback(Long installationId, String setupAction, String state) {
        log.info("[GitHub] setup 콜백 setupAction={} hasInstallationId={} hasState={}",
                LogSafe.text(setupAction), installationId != null,
                state != null && !state.isBlank());

        Optional<InstallIntent> intent = installStateStore.consumeState(state);
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
            return withSelection(buildResult(RESULT_UNVERIFIED, returnTo), resolved);
        }

        if (!ownsInstallationOrUnverified(resolved.userId(), installationId)) {
            return withSelection(buildResult(RESULT_UNVERIFIED, returnTo), resolved);
        }

        log.info("[GitHub] 설치 확인 완료 userId={} installationId={}",
                resolved.userId(), installationId);
        return withSelection(buildResult(RESULT_VERIFIED, returnTo), resolved);
    }

    /**
     * 설치 화면에 나가기 전의 선택을 리다이렉트에 실어 되돌려준다.
     *
     * <p>그 사이에 접근할 수 없게 된 저장소는 선택에서 빼고 따로 알린다. 조직 설치를 다시
     * 설정하면서 저장소를 뺀 경우가 여기에 해당한다 — 그대로 돌려주면 사용자는 고른 적 없는
     * 실패를 연결 단계에서 만난다.
     *
     * <p>대조 자체가 실패하면 선택을 그대로 돌려준다. 되살린 선택은 표시일 뿐이고, 실제 연결은
     * {@code POST /projects/{id}/repositories}가 같은 검증을 다시 하기 때문에 여기서 못 걸러도
     * 권한이 새지 않는다. 반대로 조회 실패를 이유로 선택을 통째로 버리면 사용자만 손해다.
     */
    private String withSelection(String url, InstallIntent intent) {
        List<Long> selected = intent.selectedRepositoryIds();
        if (selected.isEmpty()) {
            return url;
        }

        List<Long> available = accessibleOrAll(intent.userId(), selected);
        List<Long> unavailable = selected.stream()
                .filter(id -> !available.contains(id))
                .toList();

        String result = available.isEmpty()
                ? url
                : redirectUriValidator.withParam(url, SELECTED_PARAM, join(available));
        return unavailable.isEmpty()
                ? result
                : redirectUriValidator.withParam(result, UNAVAILABLE_PARAM, join(unavailable));
    }

    private List<Long> accessibleOrAll(Long userId, List<Long> selected) {
        try {
            Set<Long> accessible =
                    installationService.accessibleSnapshots(userId, selected).keySet();
            return selected.stream().filter(accessible::contains).toList();
        } catch (GlobalException e) {
            log.warn("[GitHub] 선택 복원 중 접근 권한을 대조하지 못했다 userId={} code={}",
                    userId, e.getErrorCode().getCode());
            return selected;
        }
    }

    private static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
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
