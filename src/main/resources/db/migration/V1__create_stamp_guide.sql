-- Single-row content table backing GET /api/v2/stamp-guide. No participation
-- state lives server-side: start/count/redeemed status is browser-local per
-- api-v2/README.md and docs/wiki/engineering/data-model.md (2026-09-14 decision).
CREATE TABLE stamp_guide (
    id SMALLINT PRIMARY KEY,
    title TEXT NOT NULL,
    dates DATE[] NOT NULL DEFAULT '{}',
    instructions TEXT[] NOT NULL DEFAULT '{}',
    reward_name TEXT NOT NULL,
    reward_location_text TEXT NULL,
    reward_hours_text TEXT NULL,
    reward_notice TEXT NOT NULL,
    qr_value TEXT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT stamp_guide_singleton CHECK (id = 1)
);

-- Placeholder content until 총학생회 confirms actual dates/QR distribution
-- (see api-v2/DECISIONS.md, question 6). dates and qr_value stay empty/null
-- rather than fabricating operational data.
INSERT INTO stamp_guide (
    id, title, dates, instructions, reward_name, reward_location_text,
    reward_hours_text, reward_notice, qr_value, updated_at
) VALUES (
    1,
    '스탬프투어',
    ARRAY[]::DATE[],
    ARRAY[
        '멋사 부스에서 QR을 스캔해 시작 스탬프 1개를 적립합니다.',
        '다른 부스를 체험한 뒤 운영자가 보여주는 QR을 스캔합니다.',
        '총 4개를 적립하면 멋사 부스에서 몬스터를 수령합니다.'
    ]::TEXT[],
    '몬스터',
    NULL,
    NULL,
    '상품은 하루 1회 수령 가능합니다. 준비 수량 소진 시 지급이 종료됩니다.',
    NULL,
    now()
);
