package com.github.galpiii.galpi.domain.featurematch.worker;

import com.github.galpiii.galpi.domain.featurematch.config.FeatureMatchProperties;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class FeatureMatchClaimer {

    private final FeatureMatchTargetRepository targetRepository;
    private final FeatureMatchProperties properties;

    @Transactional
    public Optional<Long> claim(String token) {
        OffsetDateTime expired = OffsetDateTime.now().minus(properties.lease());
        return targetRepository.findClaimableIds(expired, 1).stream()
                .filter(id -> targetRepository.claim(id, token, expired) > 0)
                .findFirst();
    }

    @Transactional
    public boolean heartbeat(long targetId, String token) {
        return targetRepository.heartbeat(targetId, token) > 0;
    }

    @Transactional
    public void release(long targetId, String token) {
        targetRepository.releaseClaim(targetId, token);
    }
}
