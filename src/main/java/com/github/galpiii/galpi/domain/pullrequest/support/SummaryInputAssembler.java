package com.github.galpiii.galpi.domain.pullrequest.support;

import com.github.galpiii.galpi.domain.collection.entity.ChangeStatus;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.entity.PullRequestCommit;
import com.github.galpiii.galpi.domain.collection.file.FilePriority;
import com.github.galpiii.galpi.domain.collection.secret.SecretContentScanner;
import com.github.galpiii.galpi.domain.collection.secret.SecretPathRules;
import com.github.galpiii.galpi.domain.github.client.dto.GithubPullRequestFileResponse;
import com.github.galpiii.galpi.domain.pullrequest.config.SummaryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 요약 한 건의 LLM 입력을 만든다.
 *
 * <p>여기서 두 가지 일이 일어난다. <b>마스킹</b>과 <b>절단</b>이다.
 *
 * <p>마스킹을 다시 하는 이유는 patch의 출처 때문이다. 제목·본문·커밋 메시지는 수집 시점에
 * 이미 걸러 DB에 들어갔지만, diff는 저장하지 않으므로 요약 직전에 GitHub에서 새로 받는다.
 * 그 값은 아무것도 거치지 않은 원문이라 여기서 반드시 검사해야 한다 -- 이 한 줄이 빠지면
 * 비공개 저장소의 자격증명이 그대로 외부로 나간다.
 *
 * <p>절단은 파일 사이의 배분 문제다. 총량만 제한하면 거대한 lock 파일이나 생성된 코드 하나가
 * 예산을 다 먹고 정작 구현 파일의 diff가 한 줄도 들어가지 못한다. 그래서 파일당 상한을 따로
 * 두고, 어떤 파일을 먼저 담을지는 {@link FilePriority}가 정한다 -- 수집기가 저장소 파일을
 * 고를 때 쓰는 것과 같은 기준이다. 진입점·컨트롤러가 무엇이 바뀌었는지를 가장 직접적으로
 * 보여 주고, 테스트와 문서는 그 주장이라 순서가 뒤다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryInputAssembler {

    private static final String TRUNCATED_MARK = "... (이하 생략)";
    private static final String INPUT_TRUNCATED_MARK = "... (입력 상한으로 이하 생략)\n";
    private static final String PATCH_OMITTED_MARK = "  (diff 생략됨)";

    private final SecretContentScanner secretContentScanner;
    private final SecretPathRules secretPathRules;
    private final SummaryProperties properties;

    public String assemble(String fullName, PullRequest pullRequest,
                           List<PullRequestCommit> commits,
                           List<GithubPullRequestFileResponse> files) {
        List<GithubPullRequestFileResponse> safeFiles = files.stream()
                .filter(file -> !secretPathRules.isSecretPath(file.filename()))
                .toList();
        int excludedSecretPaths = files.size() - safeFiles.size();
        if (excludedSecretPaths > 0) {
            // 어떤 경로인지는 로그에 남기지 않는다. 이름 자체에 비밀 값이 들어 있을 수 있다.
            log.info("[요약] 비밀정보 의심 경로를 입력에서 제외했다 number={} files={}",
                    pullRequest.getNumber(), excludedSecretPaths);
        }

        int maxInputChars = properties.maxInputChars();
        BoundedInput input = new BoundedInput(maxInputChars);
        input.append("저장소: " + boundedText(fullName, Math.max(1, maxInputChars / 20)) + '\n');
        input.append("PR #" + pullRequest.getNumber() + ": "
                + boundedText(pullRequest.getTitle(), Math.max(1, maxInputChars / 10)) + '\n');
        input.append("본문: " + boundedText(blankToDash(pullRequest.getBody()),
                Math.max(1, maxInputChars / 4)) + '\n');

        StringBuilder commitSection = new StringBuilder("커밋:\n");
        if (commits.isEmpty()) {
            commitSection.append("  (없음)\n");
        }
        for (PullRequestCommit commit : commits) {
            if (!appendWithin(commitSection,
                    "  - " + abbreviate(commit.getSha()) + ' '
                            + firstLine(commit.getMessage()) + '\n',
                    Math.max(1, maxInputChars / 4))) {
                break;
            }
        }
        input.append(commitSection.toString());

        StringBuilder fileSection = new StringBuilder("변경 파일:\n");
        if (safeFiles.isEmpty()) {
            fileSection.append("  (없음)\n");
        }
        for (GithubPullRequestFileResponse file : safeFiles) {
            String line = "  - " + file.filename()
                    + " (+" + nullSafe(file.additions())
                    + " −" + nullSafe(file.deletions())
                    + ", " + ChangeStatus.from(file.status()) + ")\n";
            if (!appendWithin(fileSection, line, Math.max(1, maxInputChars / 6))) {
                break;
            }
        }
        if (excludedSecretPaths > 0) {
            appendWithin(fileSection, "  (비밀정보 의심 경로 " + excludedSecretPaths
                    + "개 제외됨)\n", Math.max(1, maxInputChars / 6));
        }
        input.append(fileSection.toString());

        input.append("diff (일부 생략될 수 있음):\n");
        input.append(diffSection(safeFiles, pullRequest.getNumber()));

        return input.value();
    }

    /**
     * diff 부분.
     *
     * <p>우선순위 순으로 담다가 총 상한에 닿으면 나머지는 patch 없이 이름만 남긴다. 이름만
     * 남기는 것에도 뜻이 있다 -- 파일이 존재했다는 사실은 요약에 필요하고, 프롬프트가 "보이지
     * 않는 부분을 추측하지 마라"고 지시하므로 모델은 그것을 지어내지 않는다.
     */
    private String diffSection(List<GithubPullRequestFileResponse> files, Integer number) {
        List<GithubPullRequestFileResponse> ordered = new ArrayList<>(files);
        ordered.sort(Comparator
                .comparing((GithubPullRequestFileResponse file) ->
                        FilePriority.of(file.filename()).ordinal())
                .thenComparing(file -> -nullSafe(file.changes())));

        StringBuilder section = new StringBuilder();
        int remaining = properties.maxPatchChars();
        int included = 0;
        int maskedFiles = 0;

        for (GithubPullRequestFileResponse file : ordered) {
            section.append("--- ").append(file.filename()).append('\n');
            if (file.isPatchOmitted()) {
                section.append(PATCH_OMITTED_MARK).append('\n');
                continue;
            }
            if (remaining <= 0) {
                section.append(PATCH_OMITTED_MARK).append('\n');
                continue;
            }

            // 마스킹을 절단보다 먼저 한다. 잘라 낸 뒤에 검사하면, 잘린 자리에 걸쳐 있던
            // 자격증명의 앞부분만 남아 패턴에 걸리지 않고 통과한다.
            SecretContentScanner.MaskedText masked = secretContentScanner.mask(file.patch());
            if (masked.masked()) {
                maskedFiles++;
            }

            int limit = Math.min(remaining, properties.maxPatchCharsPerFile());
            String patch = truncateAtHunk(masked.text(), limit);
            section.append(patch).append('\n');
            if (patch.length() < masked.text().length()) {
                section.append(TRUNCATED_MARK).append('\n');
            }
            remaining -= patch.length();
            included++;
        }

        if (maskedFiles > 0) {
            // 경로도 값도 남기지 않는다. 개수만으로 "걸러졌다"는 사실을 확인할 수 있으면 된다.
            log.info("[요약] diff에서 비밀정보를 마스킹했다 number={} files={}", number, maskedFiles);
        }
        if (included < ordered.size()) {
            section.append("(파일 ").append(ordered.size() - included)
                    .append("개의 diff는 상한 때문에 담지 않았다)\n");
        }
        return section.toString();
    }

    /**
     * hunk 경계에서 자른다.
     *
     * <p>줄 중간에서 자르면 모델이 잘린 조각을 완전한 변경으로 읽는다. 상한 안쪽의 마지막
     * {@code @@} 앞까지만 남기면 적어도 담긴 부분은 온전한 변경 단위가 된다. 첫 hunk 자체가
     * 상한보다 크면 줄 경계에서 자른다 -- 그때는 온전한 단위를 만들 방법이 없다.
     */
    private static String truncateAtHunk(String patch, int limit) {
        if (patch.length() <= limit) {
            return patch;
        }
        String head = patch.substring(0, limit);
        int lastHunk = head.lastIndexOf("\n@@");
        if (lastHunk > 0) {
            return head.substring(0, lastHunk);
        }
        int lastLine = head.lastIndexOf('\n');
        return lastLine > 0 ? head.substring(0, lastLine) : head;
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "(없음)" : value;
    }

    /** 커밋 메시지는 첫 줄만 쓴다. 본문까지 넣으면 커밋 하나가 diff 예산만큼 자리를 차지한다. */
    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "(없음)";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static String abbreviate(String sha) {
        return sha == null || sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private static String boundedText(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        if (limit <= TRUNCATED_MARK.length()) {
            return TRUNCATED_MARK.substring(0, limit);
        }
        return value.substring(0, limit - TRUNCATED_MARK.length()) + TRUNCATED_MARK;
    }

    private static boolean appendWithin(StringBuilder section, String value, int limit) {
        if (section.length() + value.length() <= limit) {
            section.append(value);
            return true;
        }
        if (section.length() < limit) {
            String marker = "  " + TRUNCATED_MARK + '\n';
            int remaining = limit - section.length();
            section.append(marker, 0, Math.min(marker.length(), remaining));
        }
        return false;
    }

    /** 최종 프롬프트가 어떤 입력 조합에서도 설정 상한을 넘지 않게 하는 마지막 방어선. */
    private static final class BoundedInput {

        private final StringBuilder value;
        private final int limit;
        private boolean exhausted;

        private BoundedInput(int limit) {
            this.value = new StringBuilder(Math.min(limit, 16_384));
            this.limit = limit;
        }

        private void append(String text) {
            if (exhausted || text == null || text.isEmpty()) {
                return;
            }
            int remaining = limit - value.length();
            if (text.length() <= remaining) {
                value.append(text);
                return;
            }

            int contentLength = Math.max(0, remaining - INPUT_TRUNCATED_MARK.length());
            if (contentLength > 0) {
                String head = text.substring(0, Math.min(contentLength, text.length()));
                int lastLine = head.lastIndexOf('\n');
                value.append(lastLine > 0 ? head.substring(0, lastLine + 1) : head);
            }
            remaining = limit - value.length();
            if (remaining > 0) {
                value.append(INPUT_TRUNCATED_MARK, 0,
                        Math.min(INPUT_TRUNCATED_MARK.length(), remaining));
            }
            exhausted = true;
        }

        private String value() {
            return value.toString();
        }
    }
}
