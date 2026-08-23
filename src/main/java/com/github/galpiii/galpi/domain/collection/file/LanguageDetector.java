package com.github.galpiii.galpi.domain.collection.file;

import java.util.Locale;
import java.util.Map;

/**
 * 확장자로 언어를 추정한다. 확정이 아니라 파이프라인에 주는 힌트다.
 *
 * <p>내용을 읽어 판별하지 않는 것은 의도다. 여기서 파일을 한 번 더 여는 비용을 쓸 만큼
 * 정확도가 중요한 값이 아니다.
 */
public final class LanguageDetector {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("java", "Java"),
            Map.entry("kt", "Kotlin"),
            Map.entry("kts", "Kotlin"),
            Map.entry("groovy", "Groovy"),
            Map.entry("scala", "Scala"),
            Map.entry("js", "JavaScript"),
            Map.entry("jsx", "JavaScript"),
            Map.entry("mjs", "JavaScript"),
            Map.entry("cjs", "JavaScript"),
            Map.entry("ts", "TypeScript"),
            Map.entry("tsx", "TypeScript"),
            Map.entry("py", "Python"),
            Map.entry("rb", "Ruby"),
            Map.entry("go", "Go"),
            Map.entry("rs", "Rust"),
            Map.entry("php", "PHP"),
            Map.entry("cs", "C#"),
            Map.entry("c", "C"),
            Map.entry("h", "C"),
            Map.entry("cpp", "C++"),
            Map.entry("cc", "C++"),
            Map.entry("hpp", "C++"),
            Map.entry("swift", "Swift"),
            Map.entry("m", "Objective-C"),
            Map.entry("dart", "Dart"),
            Map.entry("ex", "Elixir"),
            Map.entry("exs", "Elixir"),
            Map.entry("sql", "SQL"),
            Map.entry("sh", "Shell"),
            Map.entry("bash", "Shell"),
            Map.entry("zsh", "Shell"),
            Map.entry("html", "HTML"),
            Map.entry("css", "CSS"),
            Map.entry("scss", "SCSS"),
            Map.entry("vue", "Vue"),
            Map.entry("svelte", "Svelte"),
            Map.entry("json", "JSON"),
            Map.entry("yml", "YAML"),
            Map.entry("yaml", "YAML"),
            Map.entry("toml", "TOML"),
            Map.entry("xml", "XML"),
            Map.entry("gradle", "Gradle"),
            Map.entry("tf", "Terraform"),
            Map.entry("md", "Markdown"),
            Map.entry("mdx", "Markdown"));

    private static final Map<String, String> BY_FILE_NAME = Map.of(
            "dockerfile", "Dockerfile",
            "makefile", "Makefile",
            "jenkinsfile", "Groovy",
            "gemfile", "Ruby",
            "rakefile", "Ruby");

    private LanguageDetector() {
    }

    /** @return 추정한 언어. 모르면 {@code null} */
    public static String detect(String relativePath) {
        String byExtension = BY_EXTENSION.get(FileExclusionRules.extensionOf(relativePath));
        if (byExtension != null) {
            return byExtension;
        }
        String fileName = FileExclusionRules.fileNameOf(relativePath).toLowerCase(Locale.ROOT);
        return BY_FILE_NAME.get(fileName);
    }
}
