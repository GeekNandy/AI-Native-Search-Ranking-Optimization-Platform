CREATE INDEX ads_search_idx ON ads USING gin
    (to_tsvector('simple', title || ' ' || category));

CREATE TABLE feature_snapshots (
    id UUID PRIMARY KEY,
    schema_version VARCHAR(32) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    cutoff TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    source_id VARCHAR(128) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    CHECK (window_start < cutoff)
);
CREATE INDEX feature_snapshots_latest_idx ON feature_snapshots (available_at DESC, id DESC);
CREATE TABLE ad_features (
    snapshot_id UUID NOT NULL REFERENCES feature_snapshots(id),
    ad_id UUID NOT NULL REFERENCES ads(id),
    impressions BIGINT NOT NULL CHECK (impressions >= 0 AND impressions <= 1000000000000),
    clicked_impressions BIGINT NOT NULL CHECK (clicked_impressions BETWEEN 0 AND impressions),
    PRIMARY KEY (snapshot_id, ad_id)
);
CREATE TABLE models (
    version VARCHAR(80) PRIMARY KEY,
    artifact JSONB NOT NULL,
    content_hash CHAR(64) NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE ranking_deployment (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    revision BIGINT NOT NULL DEFAULT 0,
    model_version VARCHAR(80) REFERENCES models(version),
    rollout_percent INT NOT NULL DEFAULT 0 CHECK (rollout_percent BETWEEN 0 AND 100)
);
INSERT INTO ranking_deployment(singleton) VALUES (TRUE);
CREATE TABLE deployment_history (
    revision BIGINT PRIMARY KEY,
    model_version VARCHAR(80) REFERENCES models(version),
    rollout_percent INT NOT NULL CHECK (rollout_percent BETWEEN 0 AND 100),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE experiments (
    id VARCHAR(80) PRIMARY KEY,
    salt VARCHAR(80) NOT NULL,
    model_version VARCHAR(80) NOT NULL REFERENCES models(version),
    treatment_percent INT NOT NULL CHECK (treatment_percent BETWEEN 10 AND 90),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE assignments (
    experiment_id VARCHAR(80) NOT NULL REFERENCES experiments(id),
    user_id VARCHAR(128) NOT NULL,
    arm VARCHAR(16) NOT NULL CHECK (arm IN ('control', 'treatment')),
    assigned_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (experiment_id, user_id)
);
CREATE INDEX assignments_cohort_idx ON assignments(experiment_id, assigned_at);
CREATE TABLE search_requests (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    query VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    policy VARCHAR(16) NOT NULL CHECK (policy IN ('baseline', 'model')),
    model_version VARCHAR(80),
    snapshot_id UUID REFERENCES feature_snapshots(id),
    experiment_id VARCHAR(80),
    arm VARCHAR(16),
    fallback_reason VARCHAR(48),
    FOREIGN KEY (experiment_id, user_id) REFERENCES assignments(experiment_id, user_id)
);
CREATE TABLE search_results (
    request_id UUID NOT NULL REFERENCES search_requests(id),
    ad_id UUID NOT NULL REFERENCES ads(id),
    position INT NOT NULL CHECK (position > 0),
    score DOUBLE PRECISION NOT NULL,
    text_relevance DOUBLE PRECISION NOT NULL,
    smoothed_ctr DOUBLE PRECISION NOT NULL,
    log_impressions DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (request_id, ad_id),
    UNIQUE (request_id, position)
);
CREATE TABLE impressions (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    ad_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (request_id, ad_id) REFERENCES search_results(request_id, ad_id),
    UNIQUE (request_id, ad_id),
    CHECK (received_at >= occurred_at)
);
CREATE INDEX impressions_cohort_idx ON impressions(occurred_at, id);
CREATE TABLE clicks (
    id UUID PRIMARY KEY,
    impression_id UUID NOT NULL REFERENCES impressions(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CHECK (received_at >= occurred_at)
);
CREATE INDEX clicks_attribution_idx ON clicks(impression_id, occurred_at);

CREATE TABLE training_exports (
    id UUID PRIMARY KEY,
    as_of TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE training_examples (
    export_id UUID NOT NULL REFERENCES training_exports(id),
    impression_id UUID NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY (export_id, impression_id)
);
