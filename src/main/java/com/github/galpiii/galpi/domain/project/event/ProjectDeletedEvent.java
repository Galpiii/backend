package com.github.galpiii.galpi.domain.project.event;

/** 프로젝트 soft delete와 같은 트랜잭션에서 하위 비동기 작업을 취소하기 위한 이벤트. */
public record ProjectDeletedEvent(Long projectId) {
}
