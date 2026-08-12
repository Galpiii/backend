package com.github.galpiii.galpi.domain.github.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RateLimitRecorder {
    private static final double WARN_RATIO = 0.1;
    private static final String UNKNOWN_RESOURCE = "unknown";
    private final Map<String, RateLimitSnapshot> latestByResource = new ConcurrentHashMap<>();

    public void record(RateLimitSnapshot snapshot) {
        if (snapshot == null || !snapshot.isPresent()) {
            return;
        }
        String resource = snapshot.resource() != null ? snapshot.resource() : UNKNOWN_RESOURCE;
        latestByResource.put(resource, snapshot);

        if (snapshot.isExhausted()) {
            log.warn("[GitHub] rate limit 소진 resource={} limit={} resetAt={}",
                    resource, snapshot.limit(), snapshot.resetAt());
        } else if (isNearExhaustion(snapshot)) {
            log.warn("[GitHub] rate limit 임계 접근 resource={} remaining={}/{} resetAt={}",
                    resource, snapshot.remaining(), snapshot.limit(), snapshot.resetAt());
        } else {
            log.debug("[GitHub] rate limit resource={} remaining={}/{}",
                    resource, snapshot.remaining(), snapshot.limit());
        }
    }

    public RateLimitSnapshot latest(String resource) {
        return latestByResource.get(resource);
    }

    private boolean isNearExhaustion(RateLimitSnapshot snapshot) {
        return snapshot.remaining() != null
                && snapshot.limit() != null
                && snapshot.limit() > 0
                && (double) snapshot.remaining() / snapshot.limit() < WARN_RATIO;
    }
}
