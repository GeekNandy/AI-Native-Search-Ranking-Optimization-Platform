CREATE TABLE ads (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    category VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ads_title_valid CHECK (
        char_length(btrim(title)) > 0
        AND title = btrim(title)
        AND title !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ads_category_valid CHECK (
        char_length(btrim(category)) > 0
        AND category = btrim(category)
        AND category !~ '[[:cntrl:]]'
    )
);
