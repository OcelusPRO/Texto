-- Les liens sociaux importés de la v1 portaient des classes FontAwesome ("fa-brands fa-twitch") :
-- on ne garde que le nom ("twitch").
UPDATE t_social_links
SET icon = lower(regexp_replace(regexp_replace(icon, '^((fa-(brands|solid|regular))|fab|fas|far)\s+', ''), '^fa-', ''))
WHERE icon ~* '^(fa|fab|fas|far)[ -]';

-- Pseudo (identifiant d'URL) : l'ancien reste actif et redirige vers le nouveau.
CREATE TABLE t_handle_aliases (
    handle     VARCHAR(40)  PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES t_users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_handle_aliases_user ON t_handle_aliases (user_id);

-- profile_edited : l'utilisateur a enregistré ses paramètres (identifiant, avatar, liens…).
-- Tant qu'il est faux, pseudo, avatar et identifiant suivent Discord/Twitch ; ensuite on n'y touche plus.
ALTER TABLE t_users ADD COLUMN profile_edited BOOLEAN NOT NULL DEFAULT FALSE;
-- last_synced_at : dernière mise à jour automatique depuis Discord/Twitch (au plus une fois par jour).
ALTER TABLE t_users ADD COLUMN last_synced_at TIMESTAMPTZ;
