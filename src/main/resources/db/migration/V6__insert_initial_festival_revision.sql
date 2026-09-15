INSERT INTO festivals (
    id,
    title,
    timezone,
    created_at,
    updated_at
) VALUES (
    'ec00912b-763f-4f8f-8f57-4bdfc389ccbf'::UUID,
    '한양문화제 동심',
    'Asia/Seoul',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO festival_revisions (
    id,
    festival_id,
    revision_number,
    state,
    approved_at,
    scheduled_at,
    published_at,
    created_at,
    updated_at
) VALUES (
    'f109dca2-8b28-4e09-8114-beebc2bd3ea2'::UUID,
    'ec00912b-763f-4f8f-8f57-4bdfc389ccbf'::UUID,
    1,
    'published',
    CURRENT_TIMESTAMP,
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);
