package com.github.galpiii.galpi.domain.project.dto;

import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.entity.ProjectOnboardingStep;
import com.github.galpiii.galpi.domain.project.entity.ProjectStatus;
import jakarta.validation.constraints.Size;

/**
 * 프로젝트 수정. 세 값 모두 선택이고, 온 것만 바꾼다.
 *
 * <p>{@code status}로 지정할 수 있는 것은 보관과 복구뿐이다. {@code DRAFT}로 되돌리는 전이는
 * 서버가 거부한다.
 *
 * <p>{@code onboardingStep}은 화면 라우팅 값이라 여기서 받는다. "명세서는 나중에 등록"으로
 * 건너뛴 사용자는 업로드를 하지 않으므로, 이 경로가 없으면 목록에서 다시 들어왔을 때 늘
 * 명세서 단계로 되돌아간다. 뒤로 가는 갱신은 무시된다.
 */
public record ProjectUpdateRequest(
        @Size(min = 1, max = Project.MAX_NAME_LENGTH,
                message = "프로젝트 이름은 1자 이상 100자 이하여야 합니다.")
        String name,

        ProjectStatus status,

        ProjectOnboardingStep onboardingStep
) {

    public ProjectUpdateRequest {
        name = name == null ? null : name.strip();
    }

    public boolean isEmpty() {
        return name == null && status == null && onboardingStep == null;
    }
}
