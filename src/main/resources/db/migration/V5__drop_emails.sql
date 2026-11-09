-- Plus aucun email (même haché) n'est conservé : un compte est relié à ses connexions par les ID Discord / Twitch
-- (t_linked_accounts.provider + provider_user_id), jamais par un email.
ALTER TABLE t_users DROP COLUMN IF EXISTS legacy_email_hash;
ALTER TABLE t_linked_accounts DROP COLUMN IF EXISTS email_hash;
