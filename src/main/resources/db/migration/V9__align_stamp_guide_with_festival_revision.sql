UPDATE stamp_guide
SET festival_revision_id = 'f109dca2-8b28-4e09-8114-beebc2bd3ea2'::UUID
WHERE id = 1;

ALTER TABLE stamp_guide
    ALTER COLUMN festival_revision_id SET NOT NULL;
