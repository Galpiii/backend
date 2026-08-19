package com.github.galpiii.galpi.domain.collection.secret;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 내용 기반 비밀정보 스캔 (공통 문서 §5.2(b)).
 *
 * <p>{@link com.github.galpiii.galpi.global.util.TokenMasker}와 목적이 다르다. 그쪽은 우리가
 * 만든 로그 문자열에서 우리가 다루는 토큰을 지우는 것이고, 여기는 남의 저장소에서 온 임의의
 * 텍스트를 검사한다. 그래서 AWS 키·대입 형태·엔트로피처럼 로그에는 나올 일 없는 패턴이 더 있다.
 *
 * <p>정규식과 엔트로피 검사는 완전하지 않다(§5.2(d)). 이 클래스가 통과시킨 내용이 안전하다고
 * 사용자에게 약속해서는 안 된다.
 *
 * <p>정규식은 모두 수량자 중첩 없이 서로소 문자 클래스로만 썼다. 검사 대상이 외부에서 온
 * 텍스트라 backtracking이 터지면 그대로 수집 워커가 멈춘다.
 */
@Slf4j
@Component
public class SecretContentScanner {

    private static final String MASK = "***";

    /** 이 길이 아래는 엔트로피가 높아도 우연히 그럴 수 있어 후보로 보지 않는다. */
    private static final int ENTROPY_MIN_LENGTH = 40;

    /**
     * 40자 hex(커밋 SHA)의 최대 엔트로피가 4.0이라 그 위에 둔다. 무작위 base64 40자는 5.3
     * 근처라 넉넉히 걸린다.
     */
    private static final double ENTROPY_THRESHOLD = 4.5;

    private static final Pattern GITHUB_TOKEN =
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{16,255}|github_pat_[A-Za-z0-9_]{20,255})\\b");

    private static final Pattern AWS_ACCESS_KEY =
            Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b");

    private static final Pattern PRIVATE_KEY_BEGIN =
            Pattern.compile("-----BEGIN[A-Z ]{0,40}PRIVATE KEY-----");

    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN[A-Z ]{0,40}PRIVATE KEY-----[^-]{0,100000}-----END[A-Z ]{0,40}PRIVATE KEY-----");

    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{5,2000}\\.[A-Za-z0-9_-]{5,2000}\\.[A-Za-z0-9_-]{5,2000}\\b");

    /**
     * {@code api_key = "..."} 형태. 값 그룹(1)만 마스킹해 어떤 키가 문제였는지는 남긴다.
     *
     * <p>값에 {@code $}·{@code <}·{@code {}}를 허용하지 않아 {@code ${DB_PASSWORD}} 같은
     * 환경변수 참조는 애초에 매치되지 않는다.
     */
    private static final Pattern ASSIGNED_CREDENTIAL = Pattern.compile(
            "(?i)\\b(?:api[_-]?keys?|secret(?:[_-]?key)?|password|passwd|pwd|token"
                    + "|access[_-]?key|private[_-]?key|client[_-]?secret|credentials?)\\b"
                    + "\\s*[:=]\\s*[\"']?([A-Za-z0-9+/=_.~-]{16,512})[\"']?");

    private static final Pattern ENTROPY_CANDIDATE =
            Pattern.compile("[A-Za-z0-9+/=_-]{" + ENTROPY_MIN_LENGTH + ",4096}");

    /** 자리표시자를 자격증명으로 오탐하면 멀쩡한 설정 파일이 통째로 빠진다. */
    private static final Pattern PLACEHOLDER_VALUE = Pattern.compile(
            "(?i).*(?:example|placeholder|changeme|change[_-]me|your[_-]|dummy|sample|redacted"
                    + "|xxxxx).*");

    /**
     * {@code passwordEncoder.encode} 같은 코드 참조. 대입 패턴이 그대로 걸린다.
     *
     * <p>{@code String password = passwordEncoder.encode(raw);}는 자바 코드에서 흔한 한 줄인데,
     * 이걸 자격증명으로 보면 인증을 다루는 파일이 통째로 전송 대상에서 빠진다. 근거 파일이
     * 사라지는 쪽이 기능 대조에는 더 아프다.
     */
    private static final Pattern CODE_REFERENCE_VALUE =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    /** 숫자도 기호도 없이 글자만 이어진 값. 변수명일 가능성이 높다. */
    private static final Pattern ALPHABETIC_ONLY_VALUE = Pattern.compile("[A-Za-z]+");

    private static final List<Rule> LINE_RULES = List.of(
            new Rule(SecretPatternKind.GITHUB_TOKEN, GITHUB_TOKEN, 0, value -> true),
            new Rule(SecretPatternKind.AWS_ACCESS_KEY, AWS_ACCESS_KEY, 0, value -> true),
            new Rule(SecretPatternKind.PRIVATE_KEY_BLOCK, PRIVATE_KEY_BEGIN, 0, value -> true),
            new Rule(SecretPatternKind.JWT, JWT, 0, value -> true),
            new Rule(SecretPatternKind.ASSIGNED_CREDENTIAL, ASSIGNED_CREDENTIAL, 1,
                    SecretContentScanner::isCredentialLikeValue),
            new Rule(SecretPatternKind.HIGH_ENTROPY_STRING, ENTROPY_CANDIDATE, 0,
                    SecretContentScanner::isHighEntropy));

    /**
     * 마스킹은 여러 줄에 걸친 PEM 블록을 통째로 지워야 해서 규칙이 하나 다르다. 시작 표시만
     * 지우고 base64 본문을 남기면 마스킹한 의미가 없다.
     */
    private static final List<Rule> TEXT_RULES = LINE_RULES.stream()
            .map(rule -> rule.kind() == SecretPatternKind.PRIVATE_KEY_BLOCK
                    ? new Rule(rule.kind(), PRIVATE_KEY_BLOCK, 0, value -> true)
                    : rule)
            .toList();

    /**
     * 파일에 비밀정보가 있는지 본다. 첫 탐지에서 멈춘다 — 전체 목록이 필요한 곳이 없고,
     * 세는 동안 읽는 내용이 늘어날 이유도 없다.
     *
     * <p>읽지 못한 파일은 "탐지 없음"이 아니라 호출부가 판단하도록 빈 값과 함께 경고를 남긴다.
     * 실제로는 바이너리 판별에서 이미 걸러진 텍스트 파일만 들어온다.
     */
    public Optional<SecretFinding> firstFinding(Path file) {
        try (Reader reader = newReader(file)) {
            return firstFinding(reader);
        } catch (IOException e) {
            log.warn("[수집] 비밀정보 스캔 중 파일을 읽지 못했다 cause={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public Optional<SecretFinding> firstFinding(String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        try (Reader reader = new StringReader(text)) {
            return firstFinding(reader);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<SecretFinding> firstFinding(Reader reader) throws IOException {
        BufferedReader lines = new BufferedReader(reader);
        String line;
        int lineNumber = 0;

        while ((line = lines.readLine()) != null) {
            lineNumber++;
            for (Rule rule : LINE_RULES) {
                if (rule.matches(line)) {
                    return Optional.of(SecretFinding.at(rule.kind(), lineNumber));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * PR 본문·커밋 메시지처럼 저장은 해야 하는 텍스트에서 값만 지운다.
     *
     * <p>파일과 달리 통째로 버릴 수 없다. PR 설명은 기능 대조의 근거고, 자격증명 한 줄 때문에
     * 설명 전체를 버리면 근거가 사라진다.
     */
    public MaskedText mask(String text) {
        if (text == null || text.isEmpty()) {
            return new MaskedText(text, List.of());
        }

        String masked = text;
        Map<SecretPatternKind, Integer> firstLineByKind = new HashMap<>();
        for (Rule rule : TEXT_RULES) {
            masked = rule.mask(masked, firstLineByKind);
        }

        List<SecretFinding> findings = firstLineByKind.entrySet().stream()
                .map(entry -> SecretFinding.at(entry.getKey(), entry.getValue()))
                .sorted((left, right) -> Integer.compare(left.line(), right.line()))
                .toList();
        return new MaskedText(masked, findings);
    }

    /**
     * 깨진 바이트를 예외 대신 대체 문자로 바꾼다. 남의 저장소 파일이 UTF-8이라는 보장이 없고,
     * 인코딩 하나 때문에 스캔을 건너뛰면 그 파일이 검사 없이 나간다.
     */
    private static Reader newReader(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new InputStreamReader(Files.newInputStream(file), decoder);
    }

    /**
     * 대입 오른쪽 값이 진짜 자격증명처럼 생겼는지 본다.
     *
     * <p>여기서 걸러내는 것들은 전부 "자격증명이 아닌 것이 확실한" 쪽이다. 반대로 놓치는
     * 자격증명은 남는데, 그건 §5.2(d)가 말하는 한계이고 사용자 고지에서 그렇게 다뤄야 한다.
     */
    private static boolean isCredentialLikeValue(String value) {
        return !PLACEHOLDER_VALUE.matcher(value).matches()
                && !CODE_REFERENCE_VALUE.matcher(value).matches()
                && !ALPHABETIC_ONLY_VALUE.matcher(value).matches();
    }

    /**
     * 무작위성이 높고 문자 종류가 섞여 있어야 후보로 본다.
     *
     * <p>종류 조건이 없으면 긴 식별자나 소문자 hex 덤프가 같이 걸린다. 조건이 있어도
     * 오탐은 남지만, 방향은 "의심스러우면 뺀다"가 맞다.
     */
    private static boolean isHighEntropy(String value) {
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            lower |= c >= 'a' && c <= 'z';
            upper |= c >= 'A' && c <= 'Z';
            digit |= c >= '0' && c <= '9';
        }
        return lower && upper && digit && shannonEntropy(value) >= ENTROPY_THRESHOLD;
    }

    private static double shannonEntropy(String value) {
        int[] counts = new int[128];
        int considered = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 128) {
                counts[c]++;
                considered++;
            }
        }
        if (considered == 0) {
            return 0;
        }

        double entropy = 0;
        for (int count : counts) {
            if (count == 0) {
                continue;
            }
            double probability = (double) count / considered;
            entropy -= probability * (Math.log(probability) / Math.log(2));
        }
        return entropy;
    }

    /**
     * 마스킹 결과.
     *
     * @param findings 탐지된 종류와 그 종류가 처음 나온 줄. 값은 담지 않는다
     */
    public record MaskedText(String text, List<SecretFinding> findings) {

        public boolean masked() {
            return !findings.isEmpty();
        }

        /** 로그에 남겨도 되는 요약. 종류만 나온다. */
        public String kindsForLog() {
            return findings.stream()
                    .map(finding -> finding.kind().name())
                    .distinct()
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
        }
    }

    /**
     * @param valueGroup 마스킹할 캡처 그룹. 0이면 매치 전체
     */
    private record Rule(SecretPatternKind kind,
                        Pattern pattern,
                        int valueGroup,
                        Predicate<String> accepts) {

        boolean matches(String line) {
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                if (accepts.test(matcher.group(valueGroup))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 값 그룹만 잘라내고 나머지는 그대로 둔다.
         *
         * <p>{@code appendReplacement}를 쓰지 않는 이유는 대체 문자열이 아니라 원본 쪽이
         * 문제이기 때문이다. 원문에 {@code $1}이나 역슬래시가 있어도 여기서는 그냥 복사된다.
         */
        String mask(String input, Map<SecretPatternKind, Integer> firstLineByKind) {
            Matcher matcher = pattern.matcher(input);
            StringBuilder result = null;
            int copiedUpTo = 0;

            while (matcher.find()) {
                if (!accepts.test(matcher.group(valueGroup))) {
                    continue;
                }
                if (result == null) {
                    result = new StringBuilder(input.length());
                }
                int start = matcher.start(valueGroup);
                firstLineByKind.merge(kind, lineNumberAt(input, start), Math::min);
                result.append(input, copiedUpTo, start).append(MASK);
                copiedUpTo = matcher.end(valueGroup);
            }

            if (result == null) {
                return input;
            }
            return result.append(input, copiedUpTo, input.length()).toString();
        }

        private static int lineNumberAt(String input, int index) {
            int line = 1;
            for (int i = 0; i < index; i++) {
                if (input.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }
    }

}
