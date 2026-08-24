-- 폐기 대기 큐에서 종류 구분을 없앤다. 큐는 이제 토큰 하나짜리 폐기만 표현한다.
--
-- V10은 큐가 두 종류를 구분하게 해서 "밀려난 토큰을 지우려다 authorization 전체를 폐기하는"
-- 사고를 막으려 했다. 그러나 grant 폐기가 큐에 들어갈 수 있는 한, 적재와 재연결 사이의 경합은
-- 남는다 — 배치가 "아직 끊긴 사용자"임을 확인한 직후 사용자가 재연결하면, 이미 나간 호출이
-- 방금 승인한 authorization을 폐기한다. 검사 시점과 호출 시점이 벌어지는 이상 검사로는 못 막는다.
--
-- 그래서 위험한 선택지 자체를 지운다. grant 폐기는 해제 요청을 처리하는 그 순간 한 번만
-- 시도하고, 실패하면 GitHub 설정에서 직접 해제하도록 안내한다. 큐에는 토큰 폐기만 남는데,
-- 이쪽은 그 토큰 하나만 죽이므로 나중에 실행돼도 새 authorization에 영향이 없다.
--
-- 기존 GRANT 행은 남기지 않는다. 지금 코드로는 어차피 토큰 폐기로 실행되고, 그것이 이 행이
-- 원래 회수하려던 자격증명이기도 하다.
ALTER TABLE github_token_revocations
    DROP CONSTRAINT ck_github_token_revocations_type;

ALTER TABLE github_token_revocations
    DROP COLUMN revocation_type;
