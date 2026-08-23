package com.github.galpiii.galpi.domain.project.entity;

/**
 * 위저드를 어디까지 진행했는지.
 *
 * <p><b>화면 라우팅 전용이다.</b> 중단했다 목록에서 다시 들어온 사용자를 어느 단계로 보낼지만
 * 정한다. 권한이나 상태 판정에 쓰지 마라 — 프론트가 보낸 값으로 갱신되는 필드다.
 *
 * <p>뒤로 가는 갱신은 허용하지 않는다. 완료된 단계를 클릭해 되돌아가는 것은 화면 안에서
 * 하는 일이고, 서버에 기록된 진행도까지 되감으면 다른 탭에서 열어 둔 위저드가 앞 단계로
 * 끌려간다.
 */
public enum ProjectOnboardingStep {

    /** ① 기능명세서 등록. 프로젝트를 만든 직후. */
    SPEC,

    /** ② 저장소 연결. 명세서를 올렸거나 "나중에 등록"으로 건너뛴 뒤. */
    REPOSITORIES,

    /** ③ 분석. 저장소가 1개 이상 연결된 뒤. */
    ANALYSIS,

    /** 위저드를 끝냈다. */
    DONE;

    public boolean isBefore(ProjectOnboardingStep other) {
        return ordinal() < other.ordinal();
    }
}
