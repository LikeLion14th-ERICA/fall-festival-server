-- Notice templates (ADM-NOTICE-004): prepared notice text an administrator
-- loads into the notice form. They are shared operational content, not part
-- of a festival revision, and are replaced as a whole set with the
-- notice-template CLI; the API only reads them. A notice keeps the id of the
-- template it started from, and loses it when that template is removed.

CREATE TABLE notice_templates (
    id TEXT NOT NULL,
    name TEXT NOT NULL,
    sort_order INT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_notice_templates PRIMARY KEY (id),
    -- Deferred so a replacement can reorder kept templates within one transaction.
    CONSTRAINT uq_notice_templates_sort_order UNIQUE (sort_order) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_notice_templates_id CHECK (id ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    CONSTRAINT ck_notice_templates_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_notice_templates_sort_order_positive CHECK (sort_order > 0)
);

CREATE TABLE notice_template_translations (
    template_id TEXT NOT NULL REFERENCES notice_templates(id) ON DELETE CASCADE,
    locale VARCHAR(16) NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    CONSTRAINT pk_notice_template_translations PRIMARY KEY (template_id, locale),
    CONSTRAINT ck_notice_template_translations_locale CHECK (locale IN ('ko', 'en', 'zh-Hans', 'ja')),
    CONSTRAINT ck_notice_template_translations_title
        CHECK (btrim(title) <> '' AND char_length(title) <= 200),
    CONSTRAINT ck_notice_template_translations_body
        CHECK (btrim(body) <> '' AND char_length(body) <= 10000)
);

ALTER TABLE notices
    ADD COLUMN template_id TEXT NULL REFERENCES notice_templates(id) ON DELETE SET NULL;

-- Placeholder until the operators hand over the real templates. It tells an
-- administrator who opens it that templates still have to be registered.
INSERT INTO notice_templates (id, name, sort_order)
VALUES ('template-registration-required', '템플릿 등록 필요', 1);

INSERT INTO notice_template_translations (template_id, locale, title, body) VALUES
    ('template-registration-required', 'ko', '템플릿 등록 필요', '템플릿 등록 필요'),
    ('template-registration-required', 'en', 'Template registration required', 'Template registration required'),
    ('template-registration-required', 'zh-Hans', '需要登记模板', '需要登记模板'),
    ('template-registration-required', 'ja', 'テンプレート登録が必要', 'テンプレート登録が必要');
