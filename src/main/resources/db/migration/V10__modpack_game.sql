-- Les modpacks ont une sous-catégorie (le jeu), rangée dans « language » comme le langage du code.
UPDATE t_posts SET language = 'factorio' WHERE kind = 'modpack' AND language IS NULL;
UPDATE t_post_revisions SET language = 'factorio' WHERE kind = 'modpack' AND language IS NULL;
