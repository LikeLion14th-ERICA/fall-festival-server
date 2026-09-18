-- Booth & Market uses the six category chips in the design. Existing rows
-- keep their category; the three new ones are only added.
ALTER TABLE spaces
    DROP CONSTRAINT ck_spaces_category;

ALTER TABLE spaces
    ADD CONSTRAINT ck_spaces_category
    CHECK (category IN (
        'PUB',
        'BOOTH',
        'FLEA_MARKET',
        'FOOD_TRUCK',
        'STUDENT_COUNCIL_BOOTH',
        'PROMOTION_BOOTH'
    ));
