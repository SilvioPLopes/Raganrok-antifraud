-- V1__init_schema.sql

CREATE TABLE fraud_audit_log (
    id                BIGSERIAL PRIMARY KEY,
    event_id          VARCHAR(64)  NOT NULL,
    event_type        VARCHAR(32)  NOT NULL,
    player_id         BIGINT       NOT NULL,
    verdict           VARCHAR(16)  NOT NULL,
    required_action   VARCHAR(32),
    risk_level        VARCHAR(16),
    triggered_rules   VARCHAR(512),
    reason            VARCHAR(1024),
    processing_time_ms BIGINT      NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_player_id  ON fraud_audit_log(player_id);
CREATE INDEX idx_audit_created_at ON fraud_audit_log(created_at);
CREATE INDEX idx_audit_verdict    ON fraud_audit_log(verdict);

CREATE TABLE market_prices (
    item_id      BIGINT PRIMARY KEY,
    item_name    VARCHAR(128),
    median_price BIGINT NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE player_instance_cooldown (
    id         BIGSERIAL PRIMARY KEY,
    player_id  BIGINT      NOT NULL,
    map_id     VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(player_id, map_id)
);

CREATE INDEX idx_instance_player_map ON player_instance_cooldown(player_id, map_id);
