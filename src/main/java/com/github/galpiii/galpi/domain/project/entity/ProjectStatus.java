package com.github.galpiii.galpi.domain.project.entity;

import java.util.Set;

/**
 * 프로젝트의 생명주기.
 *
 * <p>{@code DRAFT}로 되돌아가는 전이는 없다. 저장소를 전부 연결 해제해도 ACTIVE로 남는다 —
 * "아직 시작하지 않았다"와 "쓰다가 비웠다"는 사용자에게 다른 상태이고, 되돌릴 수 있게 하면
 * 이미 분석 이력이 쌓인 프로젝트가 위저드 첫 단계로 끌려간다.
 */
public enum ProjectStatus {

    /** 만들어졌고 연결된 저장소가 하나도 없다. 위저드를 마치지 않은 상태다. */
    DRAFT,

    /** 저장소가 1개 이상 연결됐다. */
    ACTIVE,

    /** 사용자가 보관했다. 목록에서 기본으로 숨기고 조회는 계속 된다. */
    ARCHIVED;

    /** 사용자가 직접 지정할 수 있는 전이. 이 표에 없는 것은 서버가 거부한다. */
    private static final Set<String> ALLOWED_TRANSITIONS = Set.of(
            key(ACTIVE, ARCHIVED),
            key(ARCHIVED, ACTIVE));

    /**
     * {@code PATCH}로 이 상태에서 {@code target}으로 갈 수 있는지.
     *
     * <p>같은 상태로의 요청은 아무것도 바꾸지 않으므로 허용한다. 재시도가 400이 되면
     * 클라이언트가 성공 여부를 되묻게 된다.
     */
    public boolean allowsTransitionTo(ProjectStatus target) {
        return this == target || ALLOWED_TRANSITIONS.contains(key(this, target));
    }

    private static String key(ProjectStatus from, ProjectStatus to) {
        return from.name() + "->" + to.name();
    }
}
