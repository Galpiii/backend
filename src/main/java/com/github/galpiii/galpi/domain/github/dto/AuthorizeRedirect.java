package com.github.galpiii.galpi.domain.github.dto;

public record AuthorizeRedirect(String url, String state) {

    public static AuthorizeRedirect withoutState(String url) {
        return new AuthorizeRedirect(url, null);
    }

    public boolean hasState() {
        return state != null && !state.isBlank();
    }
}
