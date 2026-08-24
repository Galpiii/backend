-- 동의 행이 "어떤 문구에 동의했는지"까지 증명하게 한다.
--
-- 버전만 남기면 이름만 남는 것과 같다. 버전을 올리지 않고 문구를 고치거나, 롤링 배포 중
-- 같은 버전으로 서로 다른 문구가 나가면 같은 행이 서로 다른 내용을 뜻한다. 사용자가 실제로
-- 본 문구의 SHA-256을 함께 남겨 AiDataNotice가 보관한 원문과 대조할 수 있게 한다.
--
-- 기존 행은 버전 2026-08-23 하나뿐이고 그 문구도 하나뿐이라 해시를 소급 계산할 수 있다.
-- 값은 AiDataNoticeTest가 코드와 대조한다.
ALTER TABLE ai_data_consents
    ADD COLUMN notice_hash VARCHAR(64);

UPDATE ai_data_consents
SET notice_hash = '51f7fc300fab2b1b1dae6f64c357c1d53565ca2e01ce315873ff21df047690b7'
WHERE consent_version = '2026-08-23';

-- 소급할 수 없는 버전의 행은 존재할 수 없다. 남아 있다면 배포 전제가 깨진 것이므로 막는다.
ALTER TABLE ai_data_consents
    ALTER COLUMN notice_hash SET NOT NULL;
