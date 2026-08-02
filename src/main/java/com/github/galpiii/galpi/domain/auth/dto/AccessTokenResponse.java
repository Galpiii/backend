package com.github.galpiii.galpi.domain.auth.dto;

public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {

    public static AccessTokenResponse of(IssuedTokens tokens) {
        return new AccessTokenResponse(tokens.accessToken(), "Bearer", tokens.accessTokenExpiresInSeconds());
    }
}
