package com.github.galpiii.galpi.domain.auth.dto;

public record IssuedTokens(String accessToken, String refreshToken, long accessTokenExpiresInSeconds) {

    @Override
    public String toString() {
        return "IssuedTokens[accessTokenExpiresInSeconds=" + accessTokenExpiresInSeconds + "]";
    }
}
