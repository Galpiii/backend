-- 저장소 연결 해제를 물리 삭제에서 소프트 삭제로 바꾼다.
--
-- V6은 analysis_run_repositories와 pull_requests의 FK를 ON DELETE CASCADE로 두면서
-- "연결이 끊긴 저장소의 분석 이력만 남겨 둘 방법이 없다"고 적었다. 이 컬럼이 그 방법이다.
-- 행을 지우지 않으므로 CASCADE가 발동하지 않고, 수집한 PR과 과거 분석 결과가 그대로 남는다.
--
-- 지금 구조는 큰 행동이 보존하고 작은 행동이 파괴한다. 프로젝트 삭제는 soft delete라 저장소도
-- PR도 남기는데, 그보다 작은 저장소 하나 연결 해제가 그 저장소의 이력을 전부 지운다. 뒤집힌
-- 쪽은 연결 해제다.
ALTER TABLE repositories
    ADD COLUMN unlinked_at TIMESTAMPTZ;

-- FK의 ON DELETE CASCADE는 그대로 둔다. 소프트 삭제와 충돌하지 않고, 진짜 물리 삭제 경로가
-- 생길 때 필요한 안전망이다.

-- uk_repositories_project_github_repository(UNIQUE)도 그대로 둔다. 부분 인덱스로 바꾸지
-- 않는 이유는 다시 연결할 때 새 행을 만들지 않고 끊긴 행을 되살리기 때문이다 — 한 쌍에 행은
-- 늘 하나뿐이라 제약이 그대로 성립한다. 덕분에 실수로 뺐다 다시 넣은 저장소의 PR 이력이
-- 새 행으로 갈라지지 않고 그대로 이어진다.

-- 목록 조회는 프로젝트로 좁힌 뒤 살아 있는 것만 남긴다. UNIQUE 제약의 (project_id, ...)
-- 접두사가 앞쪽을 이미 처리하므로, 죽은 행을 걸러내는 부분 인덱스만 더한다.
CREATE INDEX idx_repositories_project_live
    ON repositories (project_id) WHERE unlinked_at IS NULL;
