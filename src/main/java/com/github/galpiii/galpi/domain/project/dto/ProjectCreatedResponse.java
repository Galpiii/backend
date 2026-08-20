package com.github.galpiii.galpi.domain.project.dto;

import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.entity.ProjectOnboardingStep;
import com.github.galpiii.galpi.domain.project.entity.ProjectStatus;

/** 생성 직후. 프론트는 여기서 받은 id로 명세서 업로드와 저장소 연결을 이어서 부른다. */
public record ProjectCreatedResponse(
        Long id,
        String name,
        ProjectStatus status,
        ProjectOnboardingStep onboardingStep
) {

    public static ProjectCreatedResponse from(Project project) {
        return new ProjectCreatedResponse(
                project.getId(), project.getName(), project.getStatus(),
                project.getOnboardingStep());
    }
}
