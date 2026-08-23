-- 프로젝트 자체의 생명주기. V3는 저장소 연결에 필요한 최소 컬럼만 만들었고, 프로젝트를
-- 만들고 지우고 위저드를 이어서 진행하는 데 필요한 것들이 빠져 있었다.

-- ERD와 이후 Phase 문서가 모두 owner_id로 부른다. 소유자 판정이 이 컬럼 하나에 걸려 있어
-- 이름이 어긋난 채로 두면 읽는 쪽이 매번 대조해야 한다.
ALTER TABLE projects RENAME COLUMN user_id TO owner_id;
ALTER TABLE projects RENAME CONSTRAINT fk_projects_user TO fk_projects_owner;

-- 이름은 1~100자로 검증한다. 컬럼을 그 폭에 맞춰 두면 검증을 우회한 경로가 있어도 DB에서
-- 걸린다. 기존 행이 100자를 넘으면 이 문장이 실패하는데, 그게 조용한 잘림보다 낫다.
ALTER TABLE projects ALTER COLUMN name TYPE VARCHAR(100);

ALTER TABLE projects
    -- DRAFT는 "만들어졌지만 연결된 저장소가 없음"이다. 저장소가 0개인 프로젝트를 금지하는
    -- 대신 상태로 구분한다 — 위저드 ① 단계에서 프로젝트를 먼저 만들어야 ② 단계의 GitHub App
    -- 설치 리다이렉트에서 돌아올 자리가 생긴다.
    ADD COLUMN status                  VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    -- 화면 라우팅 전용이다. 위저드를 중단했다 다시 들어왔을 때 어느 단계로 보낼지만 정하고,
    -- 권한 판단에는 쓰지 않는다.
    ADD COLUMN onboarding_step         VARCHAR(20) NOT NULL DEFAULT 'SPEC',
    ADD COLUMN active_spec_document_id BIGINT,
    ADD COLUMN last_analysis_run_id    BIGINT,
    ADD COLUMN deleted_at              TIMESTAMPTZ,
    -- 문서나 작업이 사라져도 프로젝트는 남는다. 둘 다 "지금 무엇을 보고 있는지"를 가리키는
    -- 포인터라 대상이 없어지면 비우는 것이 맞다.
    ADD CONSTRAINT fk_projects_active_spec_document
        FOREIGN KEY (active_spec_document_id) REFERENCES spec_documents (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_projects_last_analysis_run
        FOREIGN KEY (last_analysis_run_id) REFERENCES analysis_runs (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_projects_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    ADD CONSTRAINT ck_projects_onboarding_step
        CHECK (onboarding_step IN ('SPEC', 'REPOSITORIES', 'ANALYSIS', 'DONE'));

-- 기존 행은 전부 DEFAULT로 DRAFT/SPEC이 된다. 저장소나 명세서가 이미 붙어 있는 프로젝트까지
-- "아직 아무것도 안 한 상태"로 보이면 목록에서 위저드 ① 단계로 되돌려 보내게 된다.
UPDATE projects p
   SET status = 'ACTIVE',
       onboarding_step = 'ANALYSIS'
 WHERE EXISTS (SELECT 1 FROM repositories r WHERE r.project_id = p.id);

UPDATE projects p
   SET active_spec_document_id = (SELECT s.id
                                    FROM spec_documents s
                                   WHERE s.project_id = p.id
                                   ORDER BY s.created_at DESC, s.id DESC
                                   LIMIT 1);

UPDATE projects p
   SET onboarding_step = 'REPOSITORIES'
 WHERE p.active_spec_document_id IS NOT NULL
   AND p.onboarding_step = 'SPEC';

UPDATE projects p
   SET last_analysis_run_id = (SELECT a.id
                                 FROM analysis_runs a
                                WHERE a.project_id = p.id
                                ORDER BY a.created_at DESC, a.id DESC
                                LIMIT 1);

-- 목록 조회가 타는 유일한 인덱스다. 소유자로 좁히고, 삭제된 것을 걷어내고, 수정 시각 역순으로
-- 정렬하는 순서가 그대로 컬럼 순서다. V3의 (user_id) 단일 인덱스는 이것의 접두사라 지운다.
DROP INDEX idx_projects_user;
CREATE INDEX idx_projects_owner_updated ON projects (owner_id, deleted_at, updated_at DESC);
