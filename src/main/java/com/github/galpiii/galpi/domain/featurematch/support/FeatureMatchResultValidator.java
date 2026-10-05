package com.github.galpiii.galpi.domain.featurematch.support;

import com.github.galpiii.galpi.ai.dto.FeatureMatchingResult;
import com.github.galpiii.galpi.ai.dto.FeatureMatchingRequest;
import com.github.galpiii.galpi.ai.exception.FeatureMatchingInvalidResponseException;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RequirementRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 소유 범위를 벗어난 ID는 저장하지 않는다. 정상 조각은 보존하고 잘못된 조각만 버린다.
 * 응답 전체가 깨진 경우는 '근거 없음'으로 오인하지 않도록 명시적으로 실패시킨다.
 */
@Slf4j
@Component
public class FeatureMatchResultValidator {

    public FeatureMatchingResult validate(FeatureMatchingResult result,
                                          List<FeatureRow> features,
                                          List<RequirementRow> requirements) {
        return validate(result, features.stream().map(FeatureRow::id).collect(Collectors.toSet()),
                requirements.stream().collect(Collectors.toMap(RequirementRow::id, RequirementRow::featureId)));
    }

    public FeatureMatchingResult validate(FeatureMatchingResult result, FeatureMatchingRequest input) {
        Set<Long> allowed = input.sections().stream().flatMap(section -> section.features().stream())
                .map(FeatureMatchingRequest.Feature::featureId).collect(Collectors.toSet());
        Map<Long, Long> owners = input.sections().stream().flatMap(section -> section.features().stream())
                .flatMap(feature -> feature.requirements().stream()
                        .map(requirement -> Map.entry(requirement.requirementId(), feature.featureId())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return validate(result, allowed, owners);
    }

    private FeatureMatchingResult validate(FeatureMatchingResult result, Set<Long> allowed,
                                           Map<Long, Long> owners) {
        if (result == null || result.matches() == null) {
            throw new FeatureMatchingInvalidResponseException("MISSING_MATCHES");
        }
        Set<Long> seen = new HashSet<>();
        List<FeatureMatchingResult.Match> valid = new ArrayList<>();
        for (FeatureMatchingResult.Match match : result.matches()) {
            if (match == null || !allowed.contains(match.featureId())) {
                log.warn("[기능대조] 응답 조각 제외 rule=UNKNOWN_FEATURE");
                continue;
            }
            if (match.reason() == null || match.reason().isBlank() || match.reason().length() > 500
                    || match.requirementIds() == null) {
                log.warn("[기능대조] 응답 조각 제외 rule=INVALID_MATCH featureId={}", match.featureId());
                continue;
            }
            if (!seen.add(match.featureId())) {
                log.warn("[기능대조] 응답 조각 제외 rule=DUPLICATE_FEATURE featureId={}", match.featureId());
                continue;
            }
            Set<Long> ids = new LinkedHashSet<>();
            for (Long requirementId : match.requirementIds()) {
                if (requirementId == null
                        || !Objects.equals(owners.get(requirementId), match.featureId())) {
                    log.warn("[기능대조] 요구사항 연결 제외 rule=FOREIGN_REQUIREMENT featureId={} requirementId={}",
                            match.featureId(), requirementId);
                    continue;
                }
                if (!ids.add(requirementId)) {
                    log.warn("[기능대조] 요구사항 연결 제외 rule=DUPLICATE_REQUIREMENT featureId={}",
                            match.featureId());
                }
            }
            valid.add(new FeatureMatchingResult.Match(match.featureId(), match.reason(), List.copyOf(ids)));
        }
        if (!result.matches().isEmpty() && valid.isEmpty()) {
            throw new FeatureMatchingInvalidResponseException("NO_VALID_MATCHES");
        }
        return new FeatureMatchingResult(valid);
    }
}
