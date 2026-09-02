package com.github.galpiii.galpi.domain.pullrequest.support;

/** AI 전송 직전 단 한 번의 DB 조회로 읽는 현재 실행 근거. */
public interface SummaryHandoffState {

    String getStatus();

    String getClaimedBy();

    boolean getProjectDeleted();

    boolean getRepositoryUnlinked();

    String getGithubConnectionStatus();

    boolean getConsented();
}
