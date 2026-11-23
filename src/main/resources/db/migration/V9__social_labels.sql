-- Nom affiché d'un lien social (ex. nom d'une chaîne YouTube), renseigné par le serveur.
ALTER TABLE t_social_links ADD COLUMN label VARCHAR(100);
