package com.github.galpiii.galpi.domain.github.client;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LinkHeaderParser {

    private static final Pattern LINK_ENTRY =
            Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"([^\"]+)\"");

    private LinkHeaderParser() {
    }

    public static Optional<String> next(String linkHeader) {
        return relation(linkHeader, "next");
    }

    public static Optional<String> relation(String linkHeader, String rel) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = LINK_ENTRY.matcher(linkHeader);
        while (matcher.find()) {
            if (rel.equalsIgnoreCase(matcher.group(2))) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }
}
