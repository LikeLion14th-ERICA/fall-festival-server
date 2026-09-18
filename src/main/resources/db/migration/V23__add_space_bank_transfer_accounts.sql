-- Booth bank transfer guidance (BOOTH-001). A booth's receiving account is an
-- operational account setting like TICKET and GOODS, kept outside the catalog
-- so catalog roles never read it and a change is served on the next request.
--
-- scope_id is the booth (space) API id for SPACE and '' for festival-wide
-- purposes. bank_code is the service's own bank identifier and
-- toss_link_enabled decides whether the frontend offers the Toss transfer
-- shortcut; both apply to SPACE only. No payment, order or deposit state is
-- stored: staff confirm deposits outside the web app.

ALTER TABLE operational_account_settings
    ADD COLUMN scope_id TEXT NOT NULL DEFAULT '',
    ADD COLUMN bank_code TEXT NULL,
    ADD COLUMN toss_link_enabled BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE operational_account_settings
    DROP CONSTRAINT pk_operational_account_settings,
    DROP CONSTRAINT ck_operational_account_purpose,
    ADD CONSTRAINT pk_operational_account_settings PRIMARY KEY (festival_id, purpose, scope_id),
    ADD CONSTRAINT ck_operational_account_purpose CHECK (purpose IN ('TICKET', 'GOODS', 'SPACE')),
    ADD CONSTRAINT ck_operational_account_scope CHECK (
        (purpose = 'SPACE' AND scope_id ~ '^[a-z0-9][a-z0-9-]{0,63}$')
        OR (purpose <> 'SPACE' AND scope_id = '')
    ),
    ADD CONSTRAINT ck_operational_account_space_values CHECK (
        (purpose = 'SPACE' AND transfer_link_url IS NULL AND (
            (state = 'CONFIGURED' AND bank_code ~ '^[a-z0-9][a-z0-9-]{0,31}$')
            OR (state = 'UNCONFIGURED' AND bank_code IS NULL AND toss_link_enabled = false)
        ))
        OR (purpose <> 'SPACE' AND bank_code IS NULL AND toss_link_enabled = false)
    );

ALTER TABLE operational_account_setting_history
    ADD COLUMN scope_id TEXT NOT NULL DEFAULT '',
    ADD COLUMN before_bank_code TEXT NULL,
    ADD COLUMN before_toss_link_enabled BOOLEAN NULL,
    ADD COLUMN after_bank_code TEXT NULL,
    ADD COLUMN after_toss_link_enabled BOOLEAN NULL;

ALTER TABLE operational_account_setting_history
    DROP CONSTRAINT ck_operational_account_history_purpose,
    ADD CONSTRAINT ck_operational_account_history_purpose CHECK (purpose IN ('TICKET', 'GOODS', 'SPACE'));

DROP INDEX ix_operational_account_history_lookup;
CREATE INDEX ix_operational_account_history_lookup
    ON operational_account_setting_history (festival_id, purpose, scope_id, version DESC, id DESC);

CREATE OR REPLACE FUNCTION validate_operational_account_setting()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    previous_version BIGINT;
BEGIN
    IF TG_OP = 'UPDATE'
        AND (NEW.festival_id IS DISTINCT FROM OLD.festival_id
            OR NEW.purpose IS DISTINCT FROM OLD.purpose
            OR NEW.scope_id IS DISTINCT FROM OLD.scope_id) THEN
        RAISE EXCEPTION 'operational account identity cannot change' USING ERRCODE = '23514';
    END IF;

    IF (NEW.state = 'CONFIGURED'
            AND (NEW.bank_name IS NULL OR length(btrim(NEW.bank_name)) = 0
                OR NEW.account_number IS NULL OR length(btrim(NEW.account_number)) = 0
                OR NEW.account_holder IS NULL OR length(btrim(NEW.account_holder)) = 0))
        OR (NEW.state = 'UNCONFIGURED'
            AND (NEW.bank_name IS NOT NULL OR NEW.account_number IS NOT NULL
                OR NEW.account_holder IS NOT NULL OR NEW.transfer_link_url IS NOT NULL
                OR NEW.bank_code IS NOT NULL OR NEW.toss_link_enabled)) THEN
        RAISE EXCEPTION 'operational account state is invalid' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        EXECUTE format(
            'SELECT coalesce(max(history.version), 0)'
            || ' FROM %I.operational_account_setting_history AS history'
            || ' WHERE history.festival_id = $1 AND history.purpose = $2 AND history.scope_id = $3',
            TG_TABLE_SCHEMA
        ) INTO previous_version USING NEW.festival_id, NEW.purpose, NEW.scope_id;
        IF NEW.version <> previous_version + 1 THEN
            RAISE EXCEPTION 'operational account version is invalid' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'operational account version is invalid' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION record_operational_account_setting_history()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    history_operation TEXT;
    history_actor TEXT;
    history_reason TEXT;
    history_evidence_id TEXT;
    history_session_user TEXT;
    history_insert TEXT;
BEGIN
    history_operation := coalesce(nullif(current_setting('festival.account.operation', true), ''), 'DIRECT_SQL');
    IF history_operation NOT IN ('SET', 'RESTORE', 'CLEAR') THEN
        history_operation := 'DIRECT_SQL';
    END IF;
    history_actor := nullif(left(current_setting('festival.account.actor', true), 100), '');
    history_reason := nullif(left(current_setting('festival.account.reason', true), 500), '');
    history_evidence_id := nullif(left(current_setting('festival.account.evidence_id', true), 128), '');
    history_session_user := session_user;
    history_insert := format(
        'INSERT INTO %I.operational_account_setting_history ('
        || 'festival_id, purpose, scope_id, version, operation, '
        || 'before_state, before_bank_name, before_account_number, before_account_holder, '
        || 'before_transfer_link_url, before_bank_code, before_toss_link_enabled, '
        || 'after_state, after_bank_name, after_account_number, after_account_holder, '
        || 'after_transfer_link_url, after_bank_code, after_toss_link_enabled, '
        || 'db_session_user, actor_display_name, reason, evidence_id, occurred_at'
        || ') VALUES ('
        || '$1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12::boolean, $13, $14, $15, $16, $17, $18, '
        || '$19::boolean, $20, $21, $22, $23, $24)',
        TG_TABLE_SCHEMA
    );

    IF TG_OP = 'INSERT' THEN
        EXECUTE history_insert USING
            NEW.festival_id, NEW.purpose, NEW.scope_id, NEW.version, history_operation,
            NULL, NULL, NULL, NULL, NULL, NULL, NULL,
            NEW.state, NEW.bank_name, NEW.account_number, NEW.account_holder,
            NEW.transfer_link_url, NEW.bank_code, NEW.toss_link_enabled,
            history_session_user, history_actor, history_reason, history_evidence_id, clock_timestamp();
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        EXECUTE history_insert USING
            NEW.festival_id, NEW.purpose, NEW.scope_id, NEW.version, history_operation,
            OLD.state, OLD.bank_name, OLD.account_number, OLD.account_holder,
            OLD.transfer_link_url, OLD.bank_code, OLD.toss_link_enabled,
            NEW.state, NEW.bank_name, NEW.account_number, NEW.account_holder,
            NEW.transfer_link_url, NEW.bank_code, NEW.toss_link_enabled,
            history_session_user, history_actor, history_reason, history_evidence_id, clock_timestamp();
        RETURN NEW;
    END IF;

    EXECUTE history_insert USING
        OLD.festival_id, OLD.purpose, OLD.scope_id, OLD.version, history_operation,
        OLD.state, OLD.bank_name, OLD.account_number, OLD.account_holder,
        OLD.transfer_link_url, OLD.bank_code, OLD.toss_link_enabled,
        NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        history_session_user, history_actor, history_reason, history_evidence_id, clock_timestamp();
    RETURN OLD;
END;
$$;

REVOKE ALL ON FUNCTION validate_operational_account_setting() FROM PUBLIC;
REVOKE ALL ON FUNCTION record_operational_account_setting_history() FROM PUBLIC;
