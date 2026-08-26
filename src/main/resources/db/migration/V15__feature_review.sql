-- 사용자 검토 기능. LLM이 추출한 기능 목록을 사용자가 확인하고 고칠 수 있게 한다.

-- 검토 상태는 Feature 단위로 둔다. 한 기능에 특이사항이 여럿 붙어 있어도 사용자는 그것들을
-- 종합해 행동 하나를 고르고, 그 행동으로 그 기능의 검토가 끝난다. feature_issues에 상태를
-- 두면 병합·분리로 기능이 새로 생길 때 옮겨 담을 곳이 없다.
--
-- USER_CONFIRMED와 USER_MODIFIED를 서버가 다르게 취급하지는 않는다. 화면에서 "그대로 승인"과
-- "직접 수정"을 구분해 보여주기 위한 값이다.
ALTER TABLE features
    ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'UNREVIEWED';

-- 기존 행을 채우기 위한 기본값이라 채운 뒤에는 뗀다. 남겨 두면 엔티티가 값을 넣지 않는
-- 결함이 DB 기본값에 가려 조용히 통과한다.
ALTER TABLE features
    ALTER COLUMN review_status DROP DEFAULT;

ALTER TABLE features
    ADD CONSTRAINT ck_features_review_status
        CHECK (review_status IN ('UNREVIEWED', 'USER_CONFIRMED', 'USER_MODIFIED'));

-- V4는 "요구사항은 원문에서 뽑아낸 것이므로 근거 없는 요구사항은 성립하지 않는다"고 적었다.
-- 그 전제가 바뀐다. 검토 단계에서 사용자가 요구사항을 직접 입력할 수 있고, 사용자가 쓴 문장에는
-- 대응하는 원문이 없다.
--
-- LLM이 추출한 요구사항은 그대로 source_text를 가진다. 스키마의 originalText required도
-- 유지한다 -- 비워도 되는 것은 사용자 입력뿐이다. 사용자가 기존 요구사항의 content를 고쳐도
-- source_text는 건드리지 않는다. 근거는 그 문장이 어디서 왔는지에 대한 기록이라 편집 대상이 아니다.
ALTER TABLE feature_requirements
    ALTER COLUMN source_text DROP NOT NULL;

-- 문서 안에서 섹션명이 유일하다는 것은 지금까지 정규화 계층만 지키던 약속이었다. 검토가
-- 시작되면 병합·분리가 suggestedSection으로 섹션을 새로 만들기 시작하므로, 그 약속을 깰 수
-- 있는 주체가 애플리케이션 쪽에 생긴다. 같은 이름 섹션이 둘로 갈리면 화면에서 같은 분류가
-- 두 번 나온다.
--
-- 기존 데이터는 정규화 계층 덕분에 이미 이 제약을 만족한다.
ALTER TABLE feature_sections
    ADD CONSTRAINT uk_feature_sections_title UNIQUE (spec_document_id, title);
