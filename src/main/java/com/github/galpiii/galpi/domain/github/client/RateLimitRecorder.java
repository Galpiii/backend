package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.global.util.Hashes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class RateLimitRecorder {
    private static final double WARN_RATIO = 0.1;
    private static final int MAX_SNAPSHOTS = 2_048;
    private static final String UNKNOWN_RESOURCE = "unknown";
    private static final String UNKNOWN_CREDENTIAL = "unknown";
    private final Map<CredentialResource, RateLimitSnapshot> latestByCredential =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<CredentialResource, RateLimitSnapshot> eldest) {
                    return size() > MAX_SNAPSHOTS;
                }
            });

    public void record(String authorization, RateLimitSnapshot snapshot) {
        if (snapshot == null || !snapshot.isPresent()) {
            return;
        }
        String resource = snapshot.resource() != null ? snapshot.resource() : UNKNOWN_RESOURCE;
        latestByCredential.put(key(authorization, resource), snapshot);

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

    public RateLimitSnapshot latest(String token, String resource) {
        return latestByCredential.get(key(token, resource));
    }

    private static CredentialResource key(String credential, String resource) {
        String normalizedCredential = normalizeCredential(credential);
        String credentialHash = normalizedCredential == null
                ? UNKNOWN_CREDENTIAL
                : Hashes.sha256Hex(normalizedCredential);
        return new CredentialResource(credentialHash,
                resource != null ? resource : UNKNOWN_RESOURCE);
    }

    private static String normalizeCredential(String credential) {
        if (credential == null || credential.isBlank()) {
            return null;
        }
        int separator = credential.indexOf(' ');
        return separator >= 0 ? credential.substring(separator + 1).trim() : credential.trim();
    }

    private boolean isNearExhaustion(RateLimitSnapshot snapshot) {
        return snapshot.remaining() != null
                && snapshot.limit() != null
                && snapshot.limit() > 0
                && (double) snapshot.remaining() / snapshot.limit() < WARN_RATIO;
    }
    private record CredentialResource(String credentialHash, String resource) {
    }
}
