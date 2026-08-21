-- V4가 만든 기능명세서 스키마를 LLM 자동 추출에 맞게 조정한다.
-- V4는 이미 적용된 환경이 있어 내용을 고치면 checksum이 깨지므로 손대지 않는다.

-- 분석 실패 사유. 같은 PDF로 다시 시도할 수 있는 실패인지 사용자에게 알리려면 구분이 필요하다.
-- 실패가 아닌 상태에 사유가 남아 있으면 상태 조회가 끝난 분석을 실패로 보여주므로 함께 막는다.
ALTER TABLE spec_documents
    ADD COLUMN failure_code VARCHAR(30);

ALTER TABLE spec_documents
    ADD CONSTRAINT ck_spec_documents_failure_code
        CHECK (failure_code IN ('NO_FEATURE_EXTRACTED', 'ANALYSIS_FAILED'));

ALTER TABLE spec_documents
    ADD CONSTRAINT ck_spec_documents_failure_code_only_when_failed
        CHECK (failure_code IS NULL OR extraction_status = 'FAILED');

-- 프로젝트당 기능명세서는 하나다. 애플리케이션의 존재 확인과 INSERT 사이에는 경계가 없어
-- 두 요청이 동시에 확인하면 둘 다 통과한다. 같은 프로젝트를 두 번 분석하면 결과가 갈리고
-- OpenAI 비용도 두 배가 되므로 DB가 막는다.
ALTER TABLE spec_documents
    ADD CONSTRAINT uk_spec_documents_project UNIQUE (project_id);

-- 위 UNIQUE가 project_id 인덱스를 만들므로 V4의 인덱스는 같은 것을 하나 더 들고 있는 셈이다.
DROP INDEX idx_spec_documents_project;

-- source_title은 PDF에 적혀 있던 제목 그대로다. 길이를 통제할 수 없고, 잘리면 원문 대조의
-- 근거가 사라진다.
ALTER TABLE feature_sections
    ALTER COLUMN source_title TYPE TEXT;

-- 원본 PDF를 보관하지 않는다. 검증에 쓴 임시 파일을 분석까지 재사용하고 끝나면 지우므로
-- 채울 값이 없다. 스토리지 저장이 실제로 붙을 때 그 변경과 함께 다시 만든다.
ALTER TABLE spec_documents
    DROP COLUMN storage_key;
