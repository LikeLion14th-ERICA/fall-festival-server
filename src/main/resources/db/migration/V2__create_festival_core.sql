CREATE TABLE festivals (
    id UUID NOT NULL,
    title TEXT NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_festivals PRIMARY KEY (id),
    CONSTRAINT ck_festivals_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_festivals_timezone CHECK (timezone = 'Asia/Seoul')
);

CREATE TABLE festival_revisions (
    id UUID NOT NULL,
    festival_id UUID NOT NULL,
    revision_number BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE NULL,
    scheduled_at TIMESTAMP WITH TIME ZONE NULL,
    published_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_festival_revisions PRIMARY KEY (id),
    CONSTRAINT fk_festival_revisions_festival
        FOREIGN KEY (festival_id) REFERENCES festivals(id) ON DELETE RESTRICT,
    CONSTRAINT uq_festival_revisions_festival_revision_number
        UNIQUE (festival_id, revision_number),
    CONSTRAINT ck_festival_revisions_revision_number CHECK (revision_number >= 1),
    CONSTRAINT ck_festival_revisions_state
        CHECK (state IN ('draft', 'scheduled', 'published', 'archived'))
);

CREATE UNIQUE INDEX ux_festival_revisions_one_published
    ON festival_revisions (festival_id)
    WHERE state = 'published';

CREATE TABLE festival_days (
    id UUID NOT NULL,
    festival_revision_id UUID NOT NULL,
    festival_date DATE NOT NULL,
    opens_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closes_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_festival_days PRIMARY KEY (id),
    CONSTRAINT fk_festival_days_revision
        FOREIGN KEY (festival_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT uq_festival_days_revision_date
        UNIQUE (festival_revision_id, festival_date),
    CONSTRAINT ck_festival_days_operating_time CHECK (opens_at < closes_at)
);
