package com.github.galpiii.galpi.domain.github.store;

import com.github.galpiii.galpi.global.util.Hashes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * App 설치 흐름의 시작 사실을 {@code state} 키 하나로 기록한다.
 *
 * <p>state가 돌아오지 않는 콜백은 "설치를 시작한 기록이 없다"로 끝난다. 사용자 세션으로
 * 받아내려는 시도를 하지 않는다 — 세션이 있다고 해서 이 콜백을 그 사용자가 시작했다는 근거가
 * 되지 않고, 정작 받아내고 싶은 조직 관리자 승인 케이스는 리다이렉트가 승인한 관리자 브라우저로
 * 가기 때문에 갈피 세션 자체가 없거나 다른 사용자다.
 *
 * <p>state가 유실돼도 사용자가 막히지는 않는다. 설치 목록은 {@code GET /user/installations}로
 * 매번 GitHub에 직접 묻기 때문에, 화면을 새로고침하면 설치가 그대로 보인다. 이 콜백은 그
 * 리다이렉트 한 번을 "확인됨"으로 표시할지만 정한다. 승인 시점을 서버가 직접 통지받는 것은
 * Phase 2의 웹훅({@code installation.created}) 몫이다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class GithubInstallStateStore {

    public static final Duration TTL = Duration.ofMinutes(15);

    private static final String STATE_PREFIX = "github:install-state:";
    private static final int STATE_BYTES = 32;

    /** 되살릴 선택의 상한. 한 번에 연결할 수 있는 저장소 수와 같게 둔다. */
    private static final int MAX_SELECTED_REPOSITORIES = 100;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * @param selectedRepositoryIds 설치 화면으로 나가기 전에 골라 둔 저장소.
     *                              돌아왔을 때 선택을 되살리는 데만 쓰고 권한 판단에는 쓰지 않는다
     */
    public String issue(Long userId, String returnTo, List<Long> selectedRepositoryIds) {
        String state = Hashes.randomUrlSafe(STATE_BYTES);
        String payload = objectMapper.writeValueAsString(
                new InstallIntent(userId, state, returnTo == null ? "" : returnTo,
                        capped(selectedRepositoryIds), OffsetDateTime.now()));

        redisTemplate.opsForValue().set(STATE_PREFIX + state, payload, TTL);
        return state;
    }

    /**
     * 저장하는 선택 개수를 제한한다.
     *
     * <p>이 값은 Redis에 그대로 들어갔다가 리다이렉트 URL에 실려 나간다. 개수를 열어 두면
     * 사용자가 보낸 목록 길이가 그대로 저장 크기와 URL 길이가 된다.
     */
    private static List<Long> capped(List<Long> selectedRepositoryIds) {
        if (selectedRepositoryIds == null || selectedRepositoryIds.isEmpty()) {
            return List.of();
        }
        return selectedRepositoryIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_SELECTED_REPOSITORIES)
                .toList();
    }

    /** 한 번 읽으면 사라진다. 같은 state로 들어오는 두 번째 콜백은 기록 없음으로 떨어진다. */
    public Optional<InstallIntent> consumeState(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        return read(redisTemplate.opsForValue().getAndDelete(STATE_PREFIX + state));
    }

    private Optional<InstallIntent> read(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(payload, InstallIntent.class));
        } catch (JacksonException e) {
            log.warn("[GitHub] 설치 기록을 읽을 수 없어 없는 것으로 처리한다");
            return Optional.empty();
        }
    }

    /**
     * 설치 흐름을 시작한 사실.
     *
     * <p>{@code selectedRepositoryIds}가 없는 예전 payload가 TTL 동안 남아 있을 수 있어
     * {@code null}을 빈 목록으로 받는다.
     */
    public record InstallIntent(Long userId, String state, String returnTo,
                                List<Long> selectedRepositoryIds, OffsetDateTime createdAt) {

        public InstallIntent {
            selectedRepositoryIds = selectedRepositoryIds == null
                    ? List.of()
                    : List.copyOf(selectedRepositoryIds);
        }
    }
}
