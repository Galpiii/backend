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
import java.util.Optional;

/**
 * App 설치 흐름의 시작 사실을 두 개의 키로 기록한다.
 *
 * <p>설치 콜백에 {@code state}가 돌아오지 않는 경로가 실제로 있다. 조직 관리자 승인이 필요한
 * 설치는 승인 시점에 콜백이 다시 불리는데 원래 state가 함께 오지 않는다(GitHub 미해결 이슈).
 * state만 믿으면 조직 사용자가 통째로 막히고, 반대로 "로그인만 되어 있으면 통과"로 두면
 * 남의 installation_id를 끼워 넣을 창이 열린다.
 *
 * <p>그래서 같은 내용을 state 키와 사용자 키에 함께 적어 둔다. state가 살아 돌아오면 그것으로
 * 검증하고, 유실되면 사용자 키로 "이 사용자가 방금 설치를 시작했다"는 사실만 확인한다.
 * 어느 쪽이든 installation_id 자체는 별도로 GitHub에 대조한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class GithubInstallStateStore {

    public static final Duration TTL = Duration.ofMinutes(15);

    private static final String STATE_PREFIX = "github:install-state:";
    private static final String INTENT_PREFIX = "github:install-intent:";
    private static final int STATE_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public String issue(Long userId, String returnTo) {
        String state = Hashes.randomUrlSafe(STATE_BYTES);
        String payload = objectMapper.writeValueAsString(
                new InstallIntent(userId, state, returnTo == null ? "" : returnTo,
                        OffsetDateTime.now()));

        redisTemplate.opsForValue().set(STATE_PREFIX + state, payload, TTL);
        redisTemplate.opsForValue().set(INTENT_PREFIX + userId, payload, TTL);
        return state;
    }

    public Optional<InstallIntent> consumeState(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        return read(redisTemplate.opsForValue().getAndDelete(STATE_PREFIX + state));
    }

    public Optional<InstallIntent> consumeIntent(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return read(redisTemplate.opsForValue().getAndDelete(INTENT_PREFIX + userId));
    }

    /**
     * state로 검증에 성공했을 때 짝이 되는 사용자 키를 치운다. 남겨 두면 이미 쓴 설치 시작
     * 기록이 TTL 동안 살아 있어 두 번째 콜백이 그대로 통과한다.
     */
    public void clearIntent(Long userId) {
        if (userId != null) {
            redisTemplate.delete(INTENT_PREFIX + userId);
        }
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

    public record InstallIntent(Long userId, String state, String returnTo,
                                OffsetDateTime createdAt) {
    }
}
