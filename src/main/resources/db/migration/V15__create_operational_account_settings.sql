CREATE TABLE operational_account_settings (
    festival_id UUID NOT NULL REFERENCES festivals(id) ON DELETE RESTRICT,
    purpose VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    bank_name TEXT NULL,
    account_number TEXT NULL,
    account_holder TEXT NULL,
    transfer_link_url TEXT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_operational_account_settings PRIMARY KEY (festival_id, purpose),
    CONSTRAINT ck_operational_account_purpose CHECK (purpose IN ('TICKET', 'GOODS')),
    CONSTRAINT ck_operational_account_state CHECK (state IN ('CONFIGURED', 'UNCONFIGURED')),
    CONSTRAINT ck_operational_account_version CHECK (version > 0),
    CONSTRAINT ck_operational_account_values CHECK (
        (state = 'CONFIGURED'
            AND bank_name IS NOT NULL AND length(btrim(bank_name)) > 0
            AND account_number IS NOT NULL AND length(btrim(account_number)) > 0
            AND account_holder IS NOT NULL AND length(btrim(account_holder)) > 0)
        OR
        (state = 'UNCONFIGURED'
            AND bank_name IS NULL AND account_number IS NULL AND account_holder IS NULL
            AND transfer_link_url IS NULL)
    )
);

CREATE TABLE operational_account_setting_history (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    festival_id UUID NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    before_state VARCHAR(16) NULL,
    before_bank_name TEXT NULL,
    before_account_number TEXT NULL,
    before_account_holder TEXT NULL,
    before_transfer_link_url TEXT NULL,
    after_state VARCHAR(16) NULL,
    after_bank_name TEXT NULL,
    after_account_number TEXT NULL,
    after_account_holder TEXT NULL,
    after_transfer_link_url TEXT NULL,
    db_session_user TEXT NOT NULL,
    actor_display_name TEXT NULL,
    reason TEXT NULL,
    evidence_id TEXT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_operational_account_history_purpose CHECK (purpose IN ('TICKET', 'GOODS')),
    CONSTRAINT ck_operational_account_history_version CHECK (version > 0),
    CONSTRAINT ck_operational_account_history_operation
        CHECK (operation IN ('SET', 'RESTORE', 'CLEAR', 'DIRECT_SQL')),
    CONSTRAINT ck_operational_account_history_before_state
        CHECK (before_state IS NULL OR before_state IN ('CONFIGURED', 'UNCONFIGURED')),
    CONSTRAINT ck_operational_account_history_after_state
        CHECK (after_state IS NULL OR after_state IN ('CONFIGURED', 'UNCONFIGURED'))
);

CREATE INDEX ix_operational_account_history_lookup
    ON operational_account_setting_history (festival_id, purpose, version DESC, id DESC);

CREATE INDEX ix_operational_account_history_occurred_at
    ON operational_account_setting_history (occurred_at);

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
        AND (NEW.festival_id IS DISTINCT FROM OLD.festival_id OR NEW.purpose IS DISTINCT FROM OLD.purpose) THEN
        RAISE EXCEPTION 'operational account identity cannot change' USING ERRCODE = '23514';
    END IF;

    IF (NEW.state = 'CONFIGURED'
            AND (NEW.bank_name IS NULL OR length(btrim(NEW.bank_name)) = 0
                OR NEW.account_number IS NULL OR length(btrim(NEW.account_number)) = 0
                OR NEW.account_holder IS NULL OR length(btrim(NEW.account_holder)) = 0))
        OR (NEW.state = 'UNCONFIGURED'
            AND (NEW.bank_name IS NOT NULL OR NEW.account_number IS NOT NULL
                OR NEW.account_holder IS NOT NULL OR NEW.transfer_link_url IS NOT NULL)) THEN
        RAISE EXCEPTION 'operational account state is invalid' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'INSERT' THEN
        EXECUTE format(
            'SELECT coalesce(max(history.version), 0)'
            || ' FROM %I.operational_account_setting_history AS history'
            || ' WHERE history.festival_id = $1 AND history.purpose = $2',
            TG_TABLE_SCHEMA
        ) INTO previous_version USING NEW.festival_id, NEW.purpose;
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
BEGIN
    history_operation := coalesce(nullif(current_setting('festival.account.operation', true), ''), 'DIRECT_SQL');
    IF history_operation NOT IN ('SET', 'RESTORE', 'CLEAR') THEN
        history_operation := 'DIRECT_SQL';
    END IF;
    history_actor := nullif(left(current_setting('festival.account.actor', true), 100), '');
    history_reason := nullif(left(current_setting('festival.account.reason', true), 500), '');
    history_evidence_id := nullif(left(current_setting('festival.account.evidence_id', true), 128), '');
    history_session_user := session_user;

    IF TG_OP = 'INSERT' THEN
        EXECUTE format(
            'INSERT INTO %I.operational_account_setting_history ('
            || 'festival_id, purpose, version, operation, '
            || 'before_state, before_bank_name, before_account_number, before_account_holder, before_transfer_link_url, '
            || 'after_state, after_bank_name, after_account_number, after_account_holder, after_transfer_link_url, '
            || 'db_session_user, actor_display_name, reason, evidence_id, occurred_at'
            || ') VALUES ('
            || '$1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19)',
            TG_TABLE_SCHEMA
        ) USING
            NEW.festival_id, NEW.purpose, NEW.version, history_operation,
            NULL, NULL, NULL, NULL, NULL,
            NEW.state, NEW.bank_name, NEW.account_number, NEW.account_holder, NEW.transfer_link_url,
            history_session_user, history_actor, history_reason, history_evidence_id, clock_timestamp();
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        EXECUTE format(
            'INSERT INTO %I.operational_account_setting_history ('
            || 'festival_id, purpose, version, operation, '
            || 'before_state, before_bank_name, before_account_number, before_account_holder, before_transfer_link_url, '
            || 'after_state, after_bank_name, after_account_number, after_account_holder, after_transfer_link_url, '
            || 'db_session_user, actor_display_name, reason, evidence_id, occurred_at'
            || ') VALUES ('
            || '$1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19)',
            TG_TABLE_SCHEMA
        ) USING
            NEW.festival_id, NEW.purpose, NEW.version, history_operation,
            OLD.state, OLD.bank_name, OLD.account_number, OLD.account_holder, OLD.transfer_link_url,
            NEW.state, NEW.bank_name, NEW.account_number, NEW.account_holder, NEW.transfer_link_url,
            history_session_user, history_actor, history_reason, history_evidence_id, clock_timestamp();
        RETURN NEW;
    END IF;

    EXECUTE format(
        'INSERT INTO %I.operational_account_setting_history ('
        || 'festival_id, purpose, version, operation, '
        || 'before_state, before_bank_name, before_account_number, before_account_holder, before_transfer_link_url, '
        || 'after_state, after_bank_name, after_account_number, after_account_holder, after_transfer_link_url, '
        || 'db_session_user, actor_display_name, reason, evidence_id, occurred_at'
        || ') VALUES ('
        || '$1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19)',
        TG_TABLE_SCHEMA
    ) USING
        OLD.festival_id, OLD.purpose, OLD.version, history_operation,
        OLD.state, OLD.bank_name, OLD.account_number, OLD.account_holder, OLD.transfer_link_url,
        NULL, NULL, NULL, NULL, NULL,
        history_session_user, history_actor, history_reason, history_evidence_id, clock_timestamp();
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_operational_account_settings_validate
BEFORE INSERT OR UPDATE ON operational_account_settings
FOR EACH ROW EXECUTE FUNCTION validate_operational_account_setting();

CREATE TRIGGER trg_operational_account_settings_history
AFTER INSERT OR UPDATE OR DELETE ON operational_account_settings
FOR EACH ROW EXECUTE FUNCTION record_operational_account_setting_history();

REVOKE ALL ON FUNCTION validate_operational_account_setting() FROM PUBLIC;
REVOKE ALL ON FUNCTION record_operational_account_setting_history() FROM PUBLIC;
