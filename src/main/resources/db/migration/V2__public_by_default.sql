-- Les nouveaux posts sont publics par défaut.
ALTER TABLE t_posts ALTER COLUMN visibility SET DEFAULT 'public';
