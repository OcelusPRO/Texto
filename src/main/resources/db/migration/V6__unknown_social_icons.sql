-- Une icône hors catalogue est traitée comme un lien web classique.
UPDATE t_social_links
SET icon = 'website'
WHERE icon NOT IN ('website', 'twitch', 'x', 'github', 'discord', 'youtube', 'spotify', 'steam', 'instagram', 'tiktok', 'reddit', 'bluesky', 'mastodon');
