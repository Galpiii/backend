package com.github.galpiii.galpi.domain.github.support;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 사용자가 붙여 넣은 저장소 URL에서 {@code owner/repo}만 뽑아낸다.
 *
 * <p>여기서 하는 것은 형식 판정뿐이다. 그 저장소가 실제로 있는지, 사용자가 접근할 수 있는지는
 * GitHub에 물어야 알 수 있고 그 판정은 이 클래스의 몫이 아니다.
 *
 * <p>허용 호스트는 설정의 {@code oauth-base-url}에서 가져온다. github.com을 상수로 박으면
 * GitHub Enterprise 주소로 바꿔 띄웠을 때 정상 URL이 전부 거부된다.
 */
@Slf4j
@Component
public class GithubRepositoryUrlParser {

    /** GitHub 계정 이름 규칙. 영숫자와 하이픈, 39자 이내다. */
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})");

    /** 저장소 이름은 영숫자와 {@code -}, {@code _}, {@code .}를 쓴다. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private static final String GIT_SUFFIX = ".git";

    private final String host;

    public GithubRepositoryUrlParser(GithubAppProperties properties) {
        this.host = hostOf(properties.oauthBaseUrl());
    }

    /**
     * @return 파싱한 {@code owner/repo}. 형식이 맞지 않거나 GitHub 호스트가 아니면 빈 값
     */
    public Optional<RepositoryUrl> parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Optional.empty();
        }

        URI uri;
        try {
            // scheme 없이 "github.com/owner/repo"만 붙여 넣는 경우가 흔하다. 호스트 검사는
            // 아래에서 그대로 하므로 scheme을 채워 준다고 다른 호스트가 통과하지는 않는다.
            uri = new URI(rawUrl.contains("://") ? rawUrl : "https://" + rawUrl).normalize();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }

        if (!isGithubHttpUrl(uri)) {
            return Optional.empty();
        }

        // /owner/repo 뒤에 /tree/main, /blob/..., /pull/12 가 붙어 와도 앞 두 조각만 본다.
        String[] segments = uri.getPath() == null ? new String[0] : uri.getPath().split("/");
        if (segments.length < 3) {
            return Optional.empty();
        }

        String owner = segments[1];
        String name = trimGitSuffix(segments[2]);
        if (!OWNER.matcher(owner).matches() || !NAME.matcher(name).matches()
                || isDotName(name)) {
            return Optional.empty();
        }
        return Optional.of(new RepositoryUrl(owner, name));
    }

    private boolean isGithubHttpUrl(URI uri) {
        if (uri.getHost() == null || uri.getScheme() == null) {
            return false;
        }
        // 자격증명이 박힌 URL(user:pass@host)은 받지 않는다. 호스트를 가리는 데도 쓰인다.
        if (uri.getUserInfo() != null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return false;
        }
        String candidate = uri.getHost().toLowerCase(Locale.ROOT);
        return candidate.equals(host) || candidate.equals("www." + host);
    }

    private static String trimGitSuffix(String name) {
        return name.regionMatches(true, name.length() - GIT_SUFFIX.length(), GIT_SUFFIX, 0,
                GIT_SUFFIX.length())
                ? name.substring(0, name.length() - GIT_SUFFIX.length())
                : name;
    }

    /** {@code .}과 {@code ..}는 정규식은 통과하지만 저장소 이름이 아니다. */
    private static boolean isDotName(String name) {
        return name.equals(".") || name.equals("..");
    }

    private static String hostOf(String baseUrl) {
        try {
            String parsed = new URI(baseUrl).getHost();
            return parsed == null ? "github.com" : parsed.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            log.warn("[GitHub] oauth-base-url에서 호스트를 읽지 못해 github.com으로 둔다");
            return "github.com";
        }
    }

    /** 파싱 결과. GitHub은 이름의 대소문자를 구분하지 않으므로 원문 그대로 들고 다닌다. */
    public record RepositoryUrl(String owner, String name) {

        public String fullName() {
            return owner + "/" + name;
        }
    }
}
