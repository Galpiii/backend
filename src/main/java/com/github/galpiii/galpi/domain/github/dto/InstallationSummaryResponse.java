package com.github.galpiii.galpi.domain.github.dto;

import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;

/**
 * 저장소 선택 화면의 그룹 헤더에 쓰인다.
 *
 * <p>{@code repositorySelection}이 {@code selected}인데 저장소가 비어 있으면 "설치는 됐지만
 * 고른 저장소가 없음"이고, 설치 자체가 없으면 "미설치"다. 프론트가 이 둘을 다른 화면으로
 * 구분해야 하므로 값을 그대로 넘긴다.
 */
public record InstallationSummaryResponse(
        Long installationId,
        String accountLogin,
        String accountType,
        String avatarUrl,
        String repositorySelection,
        boolean suspended,
        String settingsUrl
) {

    public static InstallationSummaryResponse from(GithubInstallationResponse installation,
                                                   GithubAppProperties properties) {
        GithubInstallationResponse.Account account = installation.account();
        return new InstallationSummaryResponse(
                installation.id(),
                account == null ? null : account.login(),
                account == null ? null : account.type(),
                account == null ? null : account.avatarUrl(),
                installation.repositorySelection(),
                installation.isSuspended(),
                properties.installationSettingsUrl(
                        installation.id(), installation.accountLogin(),
                        installation.isOrganization()));
    }
}
