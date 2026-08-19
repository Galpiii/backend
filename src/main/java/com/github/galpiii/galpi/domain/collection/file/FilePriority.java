package com.github.galpiii.galpi.domain.collection.file;

import java.util.List;
import java.util.Locale;

/**
 * 용량 상한에 걸렸을 때 무엇을 남길지 정하는 순서 (Phase 1C §5).
 *
 * <p>기능이 "구현되어 있는가"를 판단하는 데 가장 직접적인 근거부터 남긴다. 진입점과 컨트롤러는
 * 어떤 기능이 노출되는지를 그대로 보여 주고, 테스트와 문서는 그 기능이 있다는 <i>주장</i>이라
 * 순서가 뒤다.
 *
 * <p>판정은 경로에 든 단어로 한다. 정확한 분류가 아니라 상한에 걸렸을 때의 우선순위이므로,
 * 애매한 파일이 한 칸 밀리는 것은 문제가 되지 않는다.
 */
public enum FilePriority {

    ENTRY_POINT,
    DOMAIN_SERVICE,
    MODEL,
    UTILITY,
    TEST,
    DOCUMENT;

    private static final List<String> TEST_MARKERS =
            List.of("/test/", "/tests/", "/spec/", "/__tests__/", "/testing/");

    private static final List<String> TEST_NAME_MARKERS =
            List.of("test", "spec", "_test.", ".test.", ".spec.");

    private static final List<String> DOCUMENT_EXTENSIONS =
            List.of("md", "mdx", "txt", "rst", "adoc");

    private static final List<String> ENTRY_MARKERS = List.of(
            "controller", "router", "route", "handler", "resource", "endpoint", "api",
            "main", "app", "application", "index", "page", "screen", "view");

    private static final List<String> SERVICE_MARKERS = List.of(
            "service", "usecase", "use_case", "domain", "manager", "facade", "workflow",
            "command", "query", "store", "hook", "reducer");

    private static final List<String> MODEL_MARKERS = List.of(
            "entity", "model", "schema", "dto", "vo", "type", "types", "repository", "dao",
            "mapper", "migration");

    private static final List<String> UTILITY_MARKERS = List.of(
            "util", "utils", "helper", "common", "config", "constant", "support");

    /**
     * @param relativePath 저장소 루트 기준 경로
     */
    public static FilePriority of(String relativePath) {
        String lower = ("/" + relativePath).toLowerCase(Locale.ROOT);
        String fileName = FileExclusionRules.fileNameOf(lower);

        if (DOCUMENT_EXTENSIONS.contains(FileExclusionRules.extensionOf(lower))) {
            return DOCUMENT;
        }
        if (containsAny(lower, TEST_MARKERS) || containsAny(fileName, TEST_NAME_MARKERS)) {
            return TEST;
        }
        // 진입점을 서비스보다 먼저 본다. UserController는 controller가 이기고 domain이 진다.
        if (containsAny(lower, ENTRY_MARKERS)) {
            return ENTRY_POINT;
        }
        if (containsAny(lower, SERVICE_MARKERS)) {
            return DOMAIN_SERVICE;
        }
        if (containsAny(lower, MODEL_MARKERS)) {
            return MODEL;
        }
        if (containsAny(lower, UTILITY_MARKERS)) {
            return UTILITY;
        }
        // 어디에도 안 걸리는 소스는 도메인 코드로 본다. 테스트나 문서보다는 앞이어야 한다.
        return DOMAIN_SERVICE;
    }

    private static boolean containsAny(String value, List<String> markers) {
        return markers.stream().anyMatch(value::contains);
    }
}
