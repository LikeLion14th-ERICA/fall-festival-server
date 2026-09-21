-- DBeaver용: 이미 정식 import한 draft revision의 스탬프 QR만 검토한다.
-- published/scheduled revision, legacy stamp_guide, revision 상태 변경은 이 파일의 대상이 아니다.
-- 정상 운영은 완전 manifest를 수정해 import -> validate -> publish한다.
-- 이 스크립트는 catalog 책임자의 명시적 예외 승인 아래 draft 값을 점검할 때만 사용한다.
--
-- 1. 아래 UUID 세 개를 DBeaver의 읽기 전용 조회 결과로 바꾼다.
-- 2. 기본 ROLLBACK 결과와 마지막 SELECT를 검토한다.
-- 3. 예외 승인을 받았고 결과가 정확할 때만 마지막 ROLLBACK을 COMMIT으로 바꾼다.
-- 4. COMMIT 뒤에는 Catalog CLI/Workbench로 validate -> publish하고 backend를 재시작한다.

BEGIN;
SET LOCAL lock_timeout = '5s';

CREATE TEMP TABLE stamp_qr_patch_input (
    festival_id UUID NOT NULL,
    draft_revision_id UUID NOT NULL,
    expected_published_revision_id UUID NOT NULL,
    new_qr_value TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO stamp_qr_patch_input (
    festival_id,
    draft_revision_id,
    expected_published_revision_id,
    new_qr_value
) VALUES (
    '<FESTIVAL_UUID>'::UUID,
    '<DRAFT_REVISION_UUID>'::UUID,
    '<CURRENT_PUBLISHED_REVISION_UUID>'::UUID,
    'https://festival.likelionerica.com/stamps'
);

DO $$
DECLARE
    input stamp_qr_patch_input%ROWTYPE;
    current_published_revision_id UUID;
    draft_festival_id UUID;
    draft_state TEXT;
    draft_base_revision_id UUID;
    old_qr_value TEXT;
    updated_rows INTEGER;
BEGIN
    SELECT * INTO input FROM stamp_qr_patch_input;

    IF input.new_qr_value !~ '^https://[^[:space:]]+$' THEN
        RAISE EXCEPTION 'new_qr_value must be a non-blank HTTPS URL';
    END IF;

    -- Catalog publish uses the same festival-row lock to serialize a revision change.
    PERFORM 1
    FROM festivals
    WHERE id = input.festival_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Festival % does not exist', input.festival_id;
    END IF;

    SELECT id INTO current_published_revision_id
    FROM festival_revisions
    WHERE festival_id = input.festival_id
      AND state = 'published'
    FOR UPDATE;
    IF current_published_revision_id IS DISTINCT FROM input.expected_published_revision_id THEN
        RAISE EXCEPTION
            'Published baseline changed: expected %, found %',
            input.expected_published_revision_id,
            current_published_revision_id;
    END IF;

    SELECT festival_id, state, base_revision_id
    INTO draft_festival_id, draft_state, draft_base_revision_id
    FROM festival_revisions
    WHERE id = input.draft_revision_id
      AND festival_id = input.festival_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Draft revision % does not exist', input.draft_revision_id;
    END IF;
    IF draft_festival_id IS DISTINCT FROM input.festival_id THEN
        RAISE EXCEPTION 'Draft revision belongs to a different festival';
    END IF;
    IF draft_state <> 'draft' THEN
        RAISE EXCEPTION 'Only a draft revision can be changed; found %', draft_state;
    END IF;
    IF draft_base_revision_id IS DISTINCT FROM input.expected_published_revision_id THEN
        RAISE EXCEPTION
            'Draft baseline mismatch: expected %, found %',
            input.expected_published_revision_id,
            draft_base_revision_id;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM catalog_revision_audit
        WHERE festival_id = input.festival_id
          AND revision_id = input.draft_revision_id
          AND action = 'IMPORT'
    ) THEN
        RAISE EXCEPTION 'Draft revision has no IMPORT audit record';
    END IF;

    SELECT qr_value INTO old_qr_value
    FROM stamp_guide_revisions
    WHERE festival_revision_id = input.draft_revision_id
      AND id = 1
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Draft revision has no stamp guide row';
    END IF;
    IF old_qr_value IS NOT DISTINCT FROM input.new_qr_value THEN
        RAISE EXCEPTION 'Draft already has this QR value; nothing to change';
    END IF;

    UPDATE stamp_guide_revisions
    SET qr_value = input.new_qr_value,
        updated_at = CURRENT_TIMESTAMP
    WHERE festival_revision_id = input.draft_revision_id
      AND id = 1;
    GET DIAGNOSTICS updated_rows = ROW_COUNT;
    IF updated_rows <> 1 THEN
        RAISE EXCEPTION 'Expected one stamp guide update, got %', updated_rows;
    END IF;
END $$;

SELECT
    r.id AS draft_revision_id,
    r.revision_number,
    r.state,
    r.base_revision_id,
    g.qr_value,
    g.updated_at
FROM festival_revisions AS r
JOIN stamp_guide_revisions AS g
  ON g.festival_revision_id = r.id
 AND g.id = 1
JOIN stamp_qr_patch_input AS input
  ON input.draft_revision_id = r.id
WHERE r.festival_id = input.festival_id;

-- Keep this as ROLLBACK for the initial review. Do not change a published revision.
ROLLBACK;
