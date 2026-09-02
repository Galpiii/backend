package com.github.galpiii.galpi.domain.project.event;

/** 저장소 soft unlink와 같은 트랜잭션에서 그 저장소의 비동기 작업을 취소하기 위한 이벤트. */
public record ProjectRepositoryUnlinkedEvent(Long repositoryId) {
}
