package com.github.galpiii.galpi.domain.github.dto;

import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.entity.RepositoryAccessStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 재연결 후 저장소 접근 상태를 다시 맞춘 결과.
 *
 * <p>연결을 끊어도 {@code repositories} 행은 남는다. 재연결하면 대부분 그대로 살아나지만,
 * 그 사이에 조직에서 나갔거나 설치 범위에서 빠진 저장소는 다시 보이지 않는다. 그런 저장소를
 * 목록에서 지우지 않고 {@code INACCESSIBLE}로 두는 이유는, 사용자가 "왜 사라졌는지" 알 수
 * 있어야 하기 때문이다 — 프로젝트 화면은 이 상태로 경고 배지를 띄운다.
 */
@Schema(description = "저장소 접근 상태 재확인 결과")
public record RepositoryAccessResyncResponse(
        @Schema(description = "다시 확인한 저장소 수") int checkedCount,
        @Schema(description = "접근할 수 있는 저장소 수") int accessibleCount,
        @Schema(description = "접근할 수 없게 된 저장소 수. 0이 아니면 경고 배지를 띄운다")
        int inaccessibleCount,
        List<RepositoryAccess> repositories
) {

    @Schema(description = "저장소 하나의 접근 상태")
    public record RepositoryAccess(
            Long repositoryId,
            Long projectId,
            String fullName,
            RepositoryAccessStatus accessStatus
    ) {

        public static RepositoryAccess from(GithubRepository repository) {
            return new RepositoryAccess(
                    repository.getId(),
                    repository.getProject().getId(),
                    repository.getFullName(),
                    repository.getAccessStatus());
        }
    }
}
