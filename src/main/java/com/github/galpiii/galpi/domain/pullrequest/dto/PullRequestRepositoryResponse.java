package com.github.galpiii.galpi.domain.pullrequest.dto;

/**
 * PR이 속한 저장소.
 *
 * <p>목록은 저장소별로 묶어 주지 않는다. 그룹핑은 페이지네이션과 충돌하므로 -- 저장소 단위로
 * 묶으면 한 페이지에 어떤 저장소가 몇 건 들어갈지 서버가 정할 수 없다 -- 플랫 목록에 이 값을
 * 실어 주고 프론트가 묶는다. 저장소별 "PR 16개" 배지는 헤더 집계가 준다.
 */
public record PullRequestRepositoryResponse(Long id, String fullName) {
}
