-- 기존 행은 기동 시 보존된 완료 target.input_json에서 보충한다.
-- 입력이 남아 있지 않은 행은 다음 변경 조회에서 재대조 대상으로 표시한다.
ALTER TABLE feature_match_current_pull_requests ADD COLUMN feature_input_chars INTEGER;
ALTER TABLE feature_match_current_pull_requests ADD COLUMN analysis_input_chars INTEGER;
