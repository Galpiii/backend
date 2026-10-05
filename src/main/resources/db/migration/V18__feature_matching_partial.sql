-- 배포 전에 V17 실행을 종료해야 한다. 진행 중 실행의 AI 연결을 옮기면 결과가 유실될 수 있다.
-- 뒤의 ALTER TABLE도 배타 잠금을 사용한다. 처음부터 같은 잠금을 잡아 확인과 변경 사이의 생성 경합을 막는다.
LOCK TABLE feature_match_runs IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
 IF EXISTS (SELECT 1 FROM feature_match_runs WHERE status IN ('QUEUED','RUNNING')) THEN
  RAISE EXCEPTION 'Finish all V17 feature match runs before applying V18';
 END IF;
END $$;

ALTER TABLE feature_match_runs ADD COLUMN run_type VARCHAR(10) NOT NULL DEFAULT 'FULL';
ALTER TABLE feature_match_runs ADD COLUMN base_run_id BIGINT REFERENCES feature_match_runs(id) ON DELETE SET NULL;
ALTER TABLE feature_match_runs ADD COLUMN feature_snapshot_json TEXT;
ALTER TABLE feature_match_runs ADD CONSTRAINT ck_feature_match_run_type CHECK (run_type IN ('FULL', 'PARTIAL'));
ALTER TABLE feature_match_runs DROP CONSTRAINT feature_match_runs_eligible_pr_count_check;
ALTER TABLE feature_match_runs ADD CONSTRAINT ck_feature_match_target_count CHECK (eligible_pr_count >= 0);
ALTER TABLE feature_match_runs DROP CONSTRAINT feature_match_runs_feature_count_check;
ALTER TABLE feature_match_runs ADD CONSTRAINT ck_feature_match_feature_count CHECK (feature_count >= 0);
ALTER TABLE feature_match_targets ADD COLUMN input_json TEXT;
ALTER TABLE feature_match_targets ADD COLUMN source_snapshot_hash VARCHAR(64);

-- 현재 표시할 AI 연결은 실행 이력을 정리해도 남아야 한다.
ALTER TABLE feature_pr_matches DROP CONSTRAINT feature_pr_matches_check;
DELETE FROM feature_pr_matches match WHERE match.source = 'AI' AND match.feature_match_run_id NOT IN (
 SELECT DISTINCT ON (project_id) id FROM feature_match_runs
 WHERE status IN ('COMPLETED','PARTIALLY_COMPLETED') ORDER BY project_id,id DESC);
DROP INDEX uk_feature_pr_matches_ai;
CREATE UNIQUE INDEX uk_feature_pr_matches_ai ON feature_pr_matches(feature_id,pull_request_id) WHERE source = 'AI';
UPDATE feature_pr_matches SET feature_match_target_id = NULL, feature_match_run_id = NULL WHERE source = 'AI';
ALTER TABLE feature_pr_matches ADD CONSTRAINT ck_feature_pr_matches_source CHECK (
 (source = 'AI' AND feature_match_run_id IS NULL AND feature_match_target_id IS NULL
     AND reason IS NOT NULL AND user_id IS NULL)
 OR (source = 'USER' AND feature_match_run_id IS NULL AND feature_match_target_id IS NULL
     AND reason IS NULL AND user_id IS NOT NULL));

CREATE TABLE feature_match_current_states (
 project_id BIGINT PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
 spec_document_id BIGINT NOT NULL REFERENCES spec_documents(id) ON DELETE CASCADE,
 base_run_id BIGINT REFERENCES feature_match_runs(id) ON DELETE SET NULL,
 feature_snapshot_hash VARCHAR(64) NOT NULL
);
CREATE TABLE feature_match_current_features (
 project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
 feature_id BIGINT PRIMARY KEY,
 snapshot_hash VARCHAR(64) NOT NULL,
 UNIQUE(project_id,feature_id)
);
CREATE TABLE feature_match_current_pull_requests (
 project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
 pull_request_id BIGINT PRIMARY KEY,
 analysis_snapshot_hash VARCHAR(64) NOT NULL,
 source_snapshot_hash VARCHAR(64),
 UNIQUE(project_id,pull_request_id)
);

INSERT INTO feature_match_current_states(project_id,spec_document_id,base_run_id,feature_snapshot_hash)
SELECT run.project_id,run.spec_document_id,run.id,run.feature_snapshot_hash
FROM feature_match_runs run
WHERE run.id IN (SELECT DISTINCT ON (project_id) id FROM feature_match_runs
                 WHERE status IN ('COMPLETED','PARTIALLY_COMPLETED') ORDER BY project_id,id DESC);

INSERT INTO feature_match_current_pull_requests(project_id,pull_request_id,analysis_snapshot_hash)
SELECT run.project_id,analysis.pull_request_id,target.analysis_snapshot_hash
FROM feature_match_targets target
JOIN feature_match_runs run ON run.id = target.feature_match_run_id
JOIN pull_request_analyses analysis ON analysis.id = target.pull_request_analysis_id
JOIN feature_match_current_states state ON state.base_run_id = run.id
WHERE target.status = 'COMPLETED';
