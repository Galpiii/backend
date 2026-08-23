package com.github.galpiii.galpi.domain.collection.file;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code analysis_configs}의 include·exclude glob을 저장소 하나당 한 번만 컴파일해 둔 것.
 *
 * <p>파일마다 컴파일하면 곱셈이 된다. 해제 상한이 파일 20,000개고 패턴이 10개면 순회 한 번에
 * 20만 번을 컴파일하게 되는데, 결과는 매번 같다.
 *
 * <p>패턴은 사용자가 넣은 값이라 깨질 수 있다. 잘못된 패턴 하나로 수집 전체가 죽지 않도록
 * 여기서 버리고, 버렸다는 사실은 순회 전에 한 번만 남긴다.
 */
@Slf4j
public final class ConfiguredPathFilters {

    private static final ConfiguredPathFilters NONE =
            new ConfiguredPathFilters(false, List.of(), List.of());

    /**
     * include 목록이 설정되어 있었는지.
     *
     * <p>컴파일 결과가 비었는지로 대신 판단하면 안 된다. 패턴을 넣었는데 전부 깨진 경우와
     * 아예 넣지 않은 경우는 뜻이 다르다 — 앞은 "아무것도 포함하지 않는다"이고, 뒤는
     * "전부 포함한다"이다.
     */
    private final boolean includeConfigured;
    private final List<PathMatcher> includes;
    private final List<PathMatcher> excludes;

    private ConfiguredPathFilters(boolean includeConfigured, List<PathMatcher> includes,
                                  List<PathMatcher> excludes) {
        this.includeConfigured = includeConfigured;
        this.includes = includes;
        this.excludes = excludes;
    }

    public static ConfiguredPathFilters of(List<String> includePaths, List<String> excludePaths) {
        if (isEmpty(includePaths) && isEmpty(excludePaths)) {
            return NONE;
        }
        return new ConfiguredPathFilters(!isEmpty(includePaths),
                compile(includePaths, "include"), compile(excludePaths, "exclude"));
    }

    /** include 목록이 없으면 전부 포함하고, 있으면 하나 이상의 glob에 맞아야 한다. */
    public boolean matchesInclude(String relativePath) {
        return !includeConfigured || matchesAny(includes, relativePath);
    }

    /** {@code exclude_paths}에 걸리는지. */
    public boolean matchesExclude(String relativePath) {
        return !excludes.isEmpty() && matchesAny(excludes, relativePath);
    }

    private static boolean matchesAny(List<PathMatcher> matchers, String relativePath) {
        Path candidate = Path.of(relativePath);
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static List<PathMatcher> compile(List<String> patterns, String kind) {
        if (isEmpty(patterns)) {
            return List.of();
        }
        List<PathMatcher> matchers = new ArrayList<>(patterns.size());
        int discarded = 0;
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
            } catch (IllegalArgumentException e) {
                // PatternSyntaxException도 여기로 온다. 그 패턴만 버리고 나머지는 그대로 쓴다.
                discarded++;
            }
        }
        if (discarded > 0) {
            log.warn("[수집] 해석할 수 없는 {} 패턴을 무시한다 count={}", kind, discarded);
        }
        return List.copyOf(matchers);
    }

    private static boolean isEmpty(List<String> patterns) {
        return patterns == null || patterns.isEmpty();
    }
}
