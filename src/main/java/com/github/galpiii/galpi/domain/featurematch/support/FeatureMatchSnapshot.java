package com.github.galpiii.galpi.domain.featurematch.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.galpiii.galpi.ai.dto.FeatureMatchingRequest;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchPullRequestRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRequirementRow;
import com.github.galpiii.galpi.global.util.Hashes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FeatureMatchSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FeatureMatchSnapshot() {
    }

    public static List<FeatureMatchingRequest.Section> sections(List<FeatureMatchFeatureRow> features, List<FeatureMatchRequirementRow> requirements) {
        Map<Long, List<FeatureMatchingRequest.Feature>> grouped = new LinkedHashMap<>();
        for (FeatureMatchFeatureRow f : features) {
            List<FeatureMatchingRequest.Requirement> reqs = requirements.stream().filter(r -> r.featureId() == f.id())
                    .map(r -> new FeatureMatchingRequest.Requirement(r.id(), r.content())).toList();
            grouped.computeIfAbsent(f.sectionId(), ignored -> new ArrayList<>())
                    .add(new FeatureMatchingRequest.Feature(f.id(), f.name(), reqs));
        }
        return grouped.entrySet().stream().map(entry -> new FeatureMatchingRequest.Section(entry.getKey(),
                features.stream().filter(f -> Objects.equals(f.sectionId(), entry.getKey()))
                        .findFirst().map(FeatureMatchFeatureRow::sectionTitle).orElse(null), entry.getValue())).toList();
    }

    public static String featureHash(List<FeatureMatchFeatureRow> features, List<FeatureMatchRequirementRow> requirements) {
        // 검토 여부는 매칭의 의미를 바꾸지 않는다. 이름/요구사항/섹션/순서만 비교한다.
        return Hashes.sha256Hex(json(sections(features, requirements)));
    }

    public static String analysisHash(FeatureMatchPullRequestRow pr) {
        return Hashes.sha256Hex(json(Arrays.asList(pr.id(), pr.analysisId(), pr.headSha(),
                pr.analysisHeadSha(), pr.title(), pr.body(), pr.summary(), pr.changeType())));
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("대조 입력을 직렬화할 수 없습니다.", e);
        }
    }
}
