CREATE TABLE admin_accounts (
    id UUID NOT NULL,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    authority VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT pk_admin_accounts PRIMARY KEY (id),
    CONSTRAINT uq_admin_accounts_username UNIQUE (username),
    CONSTRAINT ck_admin_accounts_username_not_blank CHECK (btrim(username) <> ''),
    CONSTRAINT ck_admin_accounts_authority CHECK (authority = 'ADMIN')
);

CREATE TABLE admin_refresh_sessions (
    id UUID NOT NULL,
    admin_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_admin_refresh_sessions PRIMARY KEY (id),
    CONSTRAINT fk_admin_refresh_sessions_admin
        FOREIGN KEY (admin_id) REFERENCES admin_accounts(id) ON DELETE RESTRICT,
    CONSTRAINT uq_admin_refresh_sessions_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_admin_refresh_sessions_active_admin
    ON admin_refresh_sessions (admin_id)
    WHERE revoked_at IS NULL;
