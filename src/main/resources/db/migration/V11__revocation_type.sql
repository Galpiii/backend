-- 폐기 대기 큐가 두 종류의 폐기를 구분하게 한다.
--
-- 연결 해제는 App authorization 전체를 폐기해야 하고(DELETE /applications/{id}/grant),
-- 재로그인으로 밀려난 이전 토큰은 그 토큰 하나만 폐기해야 한다
-- (DELETE /applications/{id}/token). 후자에 grant 폐기를 쓰면 방금 발급받은 새 토큰까지
-- 함께 죽어 로그인 직후 재인증을 요구받는다. 기존 행은 전부 재시도 큐에 남은 토큰 폐기다.
ALTER TABLE github_token_revocations
    ADD COLUMN revocation_type VARCHAR(20) NOT NULL DEFAULT 'TOKEN',
    ADD CONSTRAINT ck_github_token_revocations_type
        CHECK (revocation_type IN ('TOKEN', 'GRANT'));
