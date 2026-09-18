-- Notice is festival_id-scoped dynamic operational data (not revision-scoped),
-- matching crowding (V16) and operational account settings (V15). Automatic
-- translation is out of scope: ko/en are entered manually and required at the
-- service layer, zh-Hans/ja are optional. Deleted notices are soft-deleted and
-- swept by a later cleanup job, not queried back in.
CREATE TABLE notices (
    id UUID NOT NULL,
    festival_id UUID NOT NULL REFERENCES festivals(id) ON DELETE RESTRICT,
    category VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT pk_notices PRIMARY KEY (id),
    CONSTRAINT ck_notices_category CHECK (category IN ('GENERAL', 'LOST_FOUND'))
);

-- Partial index for the hot path: visible notices for a festival, newest first.
CREATE INDEX ix_notices_festival_visible
    ON notices (festival_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- Partial index for the cleanup job sweeping soft-deleted rows past retention.
CREATE INDEX ix_notices_soft_deleted
    ON notices (festival_id, deleted_at)
    WHERE deleted_at IS NOT NULL;

CREATE TABLE notice_translations (
    notice_id UUID NOT NULL REFERENCES notices(id) ON DELETE CASCADE,
    locale VARCHAR(16) NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    CONSTRAINT pk_notice_translations PRIMARY KEY (notice_id, locale),
    CONSTRAINT ck_notice_translations_locale CHECK (locale IN ('ko', 'en', 'zh-Hans', 'ja')),
    CONSTRAINT ck_notice_translations_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_notice_translations_body_not_blank CHECK (btrim(body) <> '')
);

CREATE TABLE notice_links (
    id UUID NOT NULL,
    notice_id UUID NOT NULL REFERENCES notices(id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    sort_order INT NOT NULL,
    CONSTRAINT pk_notice_links PRIMARY KEY (id),
    CONSTRAINT uq_notice_links_sort_order UNIQUE (notice_id, sort_order),
    CONSTRAINT ck_notice_links_url_https CHECK (url ~ '^https://[^[:space:]]+$')
);

-- One row per locale that has a notice_translations body; label is required
-- there and never stored for a locale without a translation (service layer
-- enforces the pairing, matching the per-locale-row convention used by
-- space/place/map translations in V8).
CREATE TABLE notice_link_translations (
    link_id UUID NOT NULL REFERENCES notice_links(id) ON DELETE CASCADE,
    locale VARCHAR(16) NOT NULL,
    label TEXT NOT NULL,
    CONSTRAINT pk_notice_link_translations PRIMARY KEY (link_id, locale),
    CONSTRAINT ck_notice_link_translations_locale CHECK (locale IN ('ko', 'en', 'zh-Hans', 'ja')),
    CONSTRAINT ck_notice_link_translations_label_not_blank CHECK (btrim(label) <> '')
);
