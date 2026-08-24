package com.github.galpiii.galpi.domain.consent;

import com.github.galpiii.galpi.global.util.Hashes;

import java.util.Map;

/**
 * 외부 AI 전송 고지. 버전과 문구를 한 곳에 묶어 둔다.
 *
 * <p>버전과 문구가 떨어져 있으면 서로 모르게 움직인다. 버전을 올리지 않고 문구만 고치거나,
 * 롤링 배포 중에 같은 버전으로 서로 다른 문구가 나가면, 같은 동의 행이 사람마다 다른 내용을
 * 뜻하게 된다. 그래서 이 클래스가 <b>버전 → 문구</b>를 유일한 사전으로 갖고, 동의 행에는
 * 버전과 함께 문구의 해시를 남긴다. 나중에 "그때 무엇에 동의했는지"는 이 사전과 해시를
 * 대조해 확인한다.
 *
 * <p>지난 버전의 문구도 지우지 않는다. 지우는 순간 과거 동의 행의 해시를 맞춰 볼 원본이
 * 사라져 이력이 근거로서 쓸모없어진다.
 *
 * <p>문구를 고칠 때는 새 상수와 새 버전을 함께 추가한다. 기존 상수는 건드리지 않는다.
 *
 * <p><b>완전한 보호를 약속하지 않는다.</b> 비밀정보 필터링은 정규식과 엔트로피 검사라 완전할
 * 수 없다. 문구를 고칠 때 이 한계 문장을 빼지 마라 — 빼는 순간 지킬 수 없는 약속이 된다.
 */
public final class AiDataNotice {

    /** 지금 동의를 받는 버전. 문구를 바꾸면 반드시 이 값도 함께 올린다. */
    public static final String CURRENT_VERSION = "2026-08-23";

    private static final String TEXT_2026_08_23 = """
            갈피는 선택한 저장소의 코드와 Pull Request 내용을 분석하기 위해 일시적으로 처리하며, \
            선별된 코드 일부를 외부 AI 서비스로 전송합니다. .env, 개인키 등 알려진 비밀정보 \
            파일과 패턴은 전송 전에 자동으로 제외하거나 마스킹하지만, 모든 민감정보 탐지를 \
            보장하지는 않습니다. 분석 전 저장소에 민감정보가 포함되어 있지 않은지 확인해주세요. \
            선택하지 않은 저장소에는 접근하지 않습니다.""";

    private static final Map<String, String> BY_VERSION = Map.of(
            "2026-08-23", TEXT_2026_08_23);

    private AiDataNotice() {
    }

    public static boolean hasVersion(String version) {
        return BY_VERSION.containsKey(version);
    }

    /** 그 버전에 사용자가 실제로 본 문구. 모르는 버전이면 설정이 잘못된 것이라 바로 터뜨린다. */
    public static String text(String version) {
        String text = BY_VERSION.get(version);
        if (text == null) {
            throw new IllegalArgumentException("등록되지 않은 고지 버전입니다: " + version);
        }
        return text;
    }

    /**
     * 동의 행에 함께 남길 문구 해시.
     *
     * <p>원문 전체를 행마다 복사하지 않는 이유는 같은 문구를 사용자 수만큼 중복 저장하게 되기
     * 때문이다. 원문은 이 클래스가 보관하고, 행은 "그 원문이었다"만 증명한다.
     */
    public static String hash(String version) {
        return Hashes.sha256Hex(text(version));
    }
}
