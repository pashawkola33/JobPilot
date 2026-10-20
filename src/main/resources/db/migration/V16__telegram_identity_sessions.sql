CREATE TABLE telegram_identities (
    telegram_user_id BIGINT PRIMARY KEY CHECK (telegram_user_id > 0),
    candidate_id BIGINT NOT NULL UNIQUE REFERENCES candidates(id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT telegram_identity_owner_uk UNIQUE (telegram_user_id, candidate_id)
);

CREATE TABLE auth_sessions (
    token_hash VARCHAR(64) PRIMARY KEY CHECK (CHAR_LENGTH(token_hash) = 64),
    telegram_user_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    credential_scope VARCHAR(64) NOT NULL CHECK (CHAR_LENGTH(credential_scope) = 64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT auth_session_owner_fk FOREIGN KEY (telegram_user_id, candidate_id)
        REFERENCES telegram_identities(telegram_user_id, candidate_id) ON DELETE RESTRICT,
    CONSTRAINT auth_session_expiry_ck CHECK (expires_at > created_at)
);
CREATE INDEX auth_sessions_user_created_idx ON auth_sessions(telegram_user_id, created_at);
CREATE INDEX auth_sessions_expiry_idx ON auth_sessions(expires_at);

CREATE TABLE telegram_auth_exchanges (
    fingerprint VARCHAR(64) PRIMARY KEY CHECK (CHAR_LENGTH(fingerprint) = 64),
    telegram_user_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT telegram_exchange_owner_fk FOREIGN KEY (telegram_user_id, candidate_id)
        REFERENCES telegram_identities(telegram_user_id, candidate_id) ON DELETE RESTRICT,
    CONSTRAINT telegram_exchange_expiry_ck CHECK (expires_at >= consumed_at)
);
CREATE INDEX telegram_auth_exchanges_expiry_idx ON telegram_auth_exchanges(expires_at);
