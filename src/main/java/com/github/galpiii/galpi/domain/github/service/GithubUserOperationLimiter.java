package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.config.GithubOperationProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.global.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 같은 사용자가 긴 GitHub 조회를 겹쳐 실행해 servlet thread와 rate limit을 독점하지 못하게 한다. */
@Component
public class GithubUserOperationLimiter {

    private final ConcurrentHashMap<Long, Slot> slots = new ConcurrentHashMap<>();
    private final int maxConcurrentPerUser;

    public GithubUserOperationLimiter(GithubOperationProperties properties) {
        this.maxConcurrentPerUser = properties.maxConcurrentPerUser();
    }

    public <T> T execute(Long userId, Supplier<T> operation) {
        Slot slot = retain(userId);
        if (!slot.semaphore.tryAcquire()) {
            releaseReference(userId, slot);
            throw new GithubApiException(ErrorCode.GITHUB_OPERATION_IN_PROGRESS);
        }

        try {
            return operation.get();
        } finally {
            slot.semaphore.release();
            releaseReference(userId, slot);
        }
    }

    private Slot retain(Long userId) {
        return slots.compute(userId, (ignored, existing) -> {
            Slot slot = existing == null ? new Slot(maxConcurrentPerUser) : existing;
            slot.references++;
            return slot;
        });
    }

    private void releaseReference(Long userId, Slot expected) {
        slots.computeIfPresent(userId, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            current.references--;
            return current.references == 0 ? null : current;
        });
    }

    private static final class Slot {
        private final Semaphore semaphore;
        private int references;

        private Slot(int permits) {
            this.semaphore = new Semaphore(permits);
        }
    }
}
