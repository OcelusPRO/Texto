CREATE TABLE t_passkeys (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES t_users (id) ON DELETE CASCADE,
    credential_id   BYTEA        NOT NULL UNIQUE,
    user_handle     BYTEA        NOT NULL,
    public_key_cose BYTEA        NOT NULL,
    sign_count      BIGINT       NOT NULL DEFAULT 0,
    name            VARCHAR(100) NOT NULL,
    transports      VARCHAR(255) NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ
);

CREATE INDEX t_passkeys_user_idx ON t_passkeys (user_id);
CREATE INDEX t_passkeys_handle_idx ON t_passkeys (user_handle);
