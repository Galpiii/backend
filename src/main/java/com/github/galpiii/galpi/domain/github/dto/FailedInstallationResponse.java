package com.github.galpiii.galpi.domain.github.dto;

import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationResponse;

/** 저장소를 읽지 못한 installation. "조직 권한 오류 보기"에 그대로 나간다. */
public record FailedInstallationResponse(
        Long installationId,
        String accountLogin,
        String accountType,
        InstallationFailureReason reason
) {

    public static FailedInstallationResponse of(GithubInstallationResponse installation,
                                                InstallationFailureReason reason) {
        GithubInstallationResponse.Account account = installation.account();
        return new FailedInstallationResponse(
                installation.id(),
                account == null ? null : account.login(),
                account == null ? null : account.type(),
                reason);
    }
}
