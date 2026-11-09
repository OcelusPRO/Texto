-- Catalogue d'icônes sociales : « link/globe » -> website, « x-twitter/twitter » -> x.
UPDATE t_social_links SET icon = 'website' WHERE icon IN ('link', 'globe', 'web');
UPDATE t_social_links SET icon = 'x' WHERE icon IN ('x-twitter', 'twitter', 'twitter-x');

-- Jetons OAuth des comptes liés, chiffrés côté application (AES-GCM) : permettent de rafraîchir pseudo et
-- avatar Discord/Twitch sans bot ni action de l'utilisateur.
ALTER TABLE t_linked_accounts ADD COLUMN access_token TEXT;
ALTER TABLE t_linked_accounts ADD COLUMN refresh_token TEXT;
ALTER TABLE t_linked_accounts ADD COLUMN token_expires_at TIMESTAMPTZ;
