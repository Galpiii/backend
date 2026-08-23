-- Git commit author는 GitHub 계정과 연결되지 않으면 login(최대 39자)이 아니라
-- git author name이 들어오므로 더 긴 이름도 저장할 수 있게 한다.
ALTER TABLE pull_request_commits
    ALTER COLUMN author_login TYPE VARCHAR(255);

-- 애플리케이션의 exists 검사는 빠른 실패용일 뿐이다. 권한 재검증 외부 호출 사이에 요청 두 개가
-- 겹치는 경쟁 조건은 DB가 최종적으로 막아야 한다.
CREATE UNIQUE INDEX uk_analysis_runs_project_in_flight
    ON analysis_runs (project_id) WHERE status IN ('QUEUED', 'RUNNING');
