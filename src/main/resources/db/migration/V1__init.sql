-- Texto v2 : schéma initial.
-- Les tables de la v1 (TBL_*) ne sont jamais modifiées ici : l'import legacy les renomme
-- en legacy_* après avoir copié leurs données (voir LegacyImporter).

CREATE TABLE t_users (
    id                BIGSERIAL PRIMARY KEY,
    handle            VARCHAR(40)  NOT NULL UNIQUE,
    display_name      VARCHAR(100) NOT NULL,
    avatar_url        VARCHAR(512),
    role              VARCHAR(16)  NOT NULL DEFAULT 'user',
    banned            BOOLEAN      NOT NULL DEFAULT FALSE,
    bio               TEXT         NOT NULL DEFAULT '',
    locale            VARCHAR(8),
    legacy_id         INTEGER UNIQUE,
    legacy_email_hash VARCHAR(128) UNIQUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at     TIMESTAMPTZ
);

CREATE TABLE t_linked_accounts (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES t_users (id) ON DELETE CASCADE,
    provider         VARCHAR(16)  NOT NULL,
    provider_user_id VARCHAR(64)  NOT NULL,
    username         VARCHAR(100) NOT NULL,
    avatar_url       VARCHAR(512),
    email_hash       VARCHAR(128),
    linked_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_user_id),
    UNIQUE (user_id, provider)
);

CREATE TABLE t_social_links (
    id       BIGSERIAL PRIMARY KEY,
    user_id  BIGINT       NOT NULL REFERENCES t_users (id) ON DELETE CASCADE,
    icon     VARCHAR(50)  NOT NULL,
    url      VARCHAR(512) NOT NULL,
    position INTEGER      NOT NULL DEFAULT 0
);

CREATE TABLE t_api_tokens (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES t_users (id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL UNIQUE,
    prefix       VARCHAR(16)  NOT NULL,
    scopes       VARCHAR(255) NOT NULL,
    legacy       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ
);

CREATE TABLE t_folders (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES t_users (id) ON DELETE CASCADE,
    parent_id  BIGINT REFERENCES t_folders (id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_folders_name ON t_folders (user_id, COALESCE(parent_id, 0), lower(name));

CREATE TABLE t_tags (
    id      BIGSERIAL PRIMARY KEY,
    user_id BIGINT      NOT NULL REFERENCES t_users (id) ON DELETE CASCADE,
    name    VARCHAR(50) NOT NULL,
    color   VARCHAR(16) NOT NULL DEFAULT 'slate'
);
CREATE UNIQUE INDEX ux_tags_name ON t_tags (user_id, lower(name));

CREATE TABLE t_posts (
    id               BIGSERIAL PRIMARY KEY,
    key              VARCHAR(32)  NOT NULL UNIQUE,
    user_id          BIGINT       NOT NULL REFERENCES t_users (id) ON DELETE CASCADE,
    folder_id        BIGINT REFERENCES t_folders (id) ON DELETE SET NULL,
    title            VARCHAR(200) NOT NULL,
    description      TEXT         NOT NULL DEFAULT '',
    kind             VARCHAR(16)  NOT NULL DEFAULT 'code',
    language         VARCHAR(40),
    content          TEXT         NOT NULL,
    visibility       VARCHAR(16)  NOT NULL DEFAULT 'unlisted',
    password_hash    VARCHAR(255),
    burn_after_views INTEGER,
    views            INTEGER      NOT NULL DEFAULT 0,
    revision         INTEGER      NOT NULL DEFAULT 1,
    has_thumbnail    BOOLEAN      NOT NULL DEFAULT FALSE,
    legacy           BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    search           TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(description, '')), 'B') ||
        setweight(to_tsvector('simple', left(content, 200000)), 'C')
    ) STORED
);
CREATE INDEX ix_posts_user ON t_posts (user_id, updated_at DESC);
CREATE INDEX ix_posts_folder ON t_posts (folder_id);
CREATE INDEX ix_posts_expires ON t_posts (expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX ix_posts_search ON t_posts USING GIN (search);

CREATE TABLE t_post_revisions (
    id          BIGSERIAL PRIMARY KEY,
    post_id     BIGINT       NOT NULL REFERENCES t_posts (id) ON DELETE CASCADE,
    number      INTEGER      NOT NULL,
    title       VARCHAR(200) NOT NULL,
    description TEXT         NOT NULL,
    kind        VARCHAR(16)  NOT NULL,
    language    VARCHAR(40),
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (post_id, number)
);

CREATE TABLE t_post_tags (
    post_id BIGINT NOT NULL REFERENCES t_posts (id) ON DELETE CASCADE,
    tag_id  BIGINT NOT NULL REFERENCES t_tags (id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, tag_id)
);

CREATE TABLE t_post_thumbnails (
    post_id BIGINT PRIMARY KEY REFERENCES t_posts (id) ON DELETE CASCADE,
    png     BYTEA       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_app_meta (
    key   VARCHAR(64) PRIMARY KEY,
    value TEXT        NOT NULL
);
