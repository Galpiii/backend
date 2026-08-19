package com.github.galpiii.galpi.domain.collection.file;

import org.springframework.stereotype.Component;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 비밀정보와 무관한 제외 규칙 (Phase 1C §5).
 *
 * <p>여기서 빼는 것은 "저장소가 직접 쓴 코드가 아닌 것"이다. 남의 라이브러리와 빌드 산출물을
 * LLM에 보내면 토큰만 태우고 기능 대조에는 아무 도움이 안 된다.
 *
 * <p>디렉터리 규칙은 순회 자체를 자르는 데 쓴다. {@code node_modules} 안을 열거한 다음 하나씩
 * 거르면 파일 수만 명 단위로 늘어나고, 그 목록은 어디에도 쓸모가 없다.
 */
@Component
public class FileExclusionRules {

    /** 만나면 하위 전체를 순회하지 않는다. */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "node_modules", "dist", "build", "out", "target", "vendor", ".gradle", "venv",
            ".venv", ".git", ".idea", ".vscode", "__pycache__", ".next", ".nuxt", ".terraform");

    private static final Set<String> LOCK_FILE_NAMES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock", "gemfile.lock",
            "composer.lock", "cargo.lock", "go.sum");

    private static final List<String> LOCK_FILE_GLOBS = List.of("*.lock");

    /** 바이너리·미디어. 확장자로 먼저 거르고, 남은 것은 내용 스니핑이 잡는다. */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "tiff", "avif",
            "svg", "psd", "ai", "sketch", "fig",
            "woff", "woff2", "ttf", "otf", "eot",
            "mp3", "mp4", "avi", "mov", "wmv", "flv", "mkv", "webm", "wav", "ogg", "m4a",
            "jar", "war", "ear", "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
            "so", "dll", "dylib", "exe", "bin", "o", "a", "class", "pyc", "pyo",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp",
            "db", "sqlite", "sqlite3", "mdb", "dat", "pack", "idx");

    /** 최소화·소스맵. 사람이 쓴 코드가 아니다. */
    private static final List<String> GENERATED_GLOBS = List.of(
            "*.min.js", "*.min.css", "*.map", "*.bundle.js", "*.chunk.js",
            "*_pb2.py", "*_pb.go", "*.pb.go", "*.g.dart", "*.freezed.dart", "*.generated.ts");

    private final List<PathMatcher> lockFileMatchers = compile(LOCK_FILE_GLOBS);
    private final List<PathMatcher> generatedMatchers = compile(GENERATED_GLOBS);

    /** 순회를 멈춰야 하는 디렉터리인지. */
    public boolean isExcludedDirectory(String directoryName) {
        return EXCLUDED_DIRECTORIES.contains(directoryName.toLowerCase(Locale.ROOT));
    }

    /** 의존성·빌드 산출물·lock·생성 코드. */
    public boolean isDependencyArtifact(String relativePath) {
        String fileName = fileNameOf(relativePath);
        String lower = fileName.toLowerCase(Locale.ROOT);

        if (LOCK_FILE_NAMES.contains(lower)) {
            return true;
        }
        Path candidate = Path.of(lower);
        return lockFileMatchers.stream().anyMatch(matcher -> matcher.matches(candidate))
                || generatedMatchers.stream().anyMatch(matcher -> matcher.matches(candidate));
    }

    public boolean hasBinaryExtension(String relativePath) {
        return BINARY_EXTENSIONS.contains(extensionOf(relativePath));
    }

    /**
     * {@code analysis_configs.exclude_paths}에 걸리는지.
     *
     * <p>설정값은 사용자가 넣은 glob이다. 잘못된 패턴 하나로 수집 전체가 죽지 않도록,
     * 해석할 수 없는 패턴은 무시하고 넘어간다.
     */
    public boolean matchesConfiguredExclude(String relativePath, List<String> excludePaths) {
        return matchesAny(relativePath, excludePaths);
    }

    /** include 목록이 없으면 전부 포함하고, 있으면 하나 이상의 glob에 맞아야 한다. */
    public boolean matchesConfiguredInclude(String relativePath, List<String> includePaths) {
        return includePaths == null || includePaths.isEmpty()
                || matchesAny(relativePath, includePaths);
    }

    private static boolean matchesAny(String relativePath, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        Path candidate = Path.of(relativePath);
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            try {
                if (FileSystems.getDefault().getPathMatcher("glob:" + pattern)
                        .matches(candidate)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // 사용자가 넣은 값이라 깨질 수 있다(PatternSyntaxException도 여기로 온다).
                // 그 패턴만 버리고 나머지는 계속 본다.
                continue;
            }
        }
        return false;
    }

    static String fileNameOf(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }

    static String extensionOf(String relativePath) {
        String fileName = fileNameOf(relativePath);
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static List<PathMatcher> compile(List<String> globs) {
        return globs.stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                .toList();
    }
}
