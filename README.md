# Texto

Pastebin personnel : code, texte, Markdown (avec Mermaid) et plans Factorio.

- Comptes via Discord et/ou Twitch, liables entre eux (un seul compte).
- Dossiers imbriqués et tags, propres à chaque utilisateur.
- Historique des versions avec diff et restauration.
- Visibilité publique / non listée / privée, mot de passe, brûler après N vues, expiration.
- Recherche plein texte (PostgreSQL).
- Visionneuse Factorio : plans, livres, planificateurs ; 1.1, 2.0 et Space Age ; entités de mods inconnues
  affichées en blocs avec leur nom et le mod deviné ; liste des matériaux ; icônes du jeu si elles sont exportées.
- Aperçus de liens : OpenGraph et « component embeds » Discord, image générée.
- API REST (`/api/docs`) avec tokens, CLI (`/cli/texto.mjs`), panneau admin.

## Stack

Backend Kotlin + Ktor 3, Exposed, Flyway, PostgreSQL. Frontend React 19, Vite, Tailwind CSS 4 (`web/`),
compilé et embarqué dans le jar.

## Migration depuis la v1

Rien à faire à la main : au premier démarrage, si les tables `TBL_*` de la v1 sont présentes dans la base,
Texto importe les comptes, liens sociaux, clés API et posts (contenu lu dans `TEXTO_LEGACY_PAGES`,
par défaut `/app/pages`, le volume `texto_data` de la v1), puis renomme ces tables en `legacy_*`.

- Les clés et les URL `/{clé}`, `/raw/{clé}`, `/author/{id}` et `POST /new-texto/api` restent valides.
- Aucun email n'est demandé ni conservé : un compte (identifiant interne unique) est relié à ses connexions par leurs ID
  Discord et Twitch. Un ancien compte v1 est rattaché à la connexion Discord/Twitch qui correspond à son identité
  (l'ID Discord contenu dans son URL d'avatar, ou l'image de profil Twitch) ; sinon l'administration le rattache à la main
  (fusion de comptes).
- Les comptes qui n'avaient qu'un mot de passe peuvent être réassignés depuis l'admin.
- Les callbacks OAuth gardent les mêmes URL : `/login/discord/clb` et `/login/twitch/clb`.

## Données de la v1 conservées

L'import renomme les anciennes tables en `legacy_*` et ne les supprime pas (elles servent à restaurer les dates).
Elles contiennent encore les hashes d'email (`legacy_tbl_author_aut.user_email_hash`) et les mots de passe hachés
(`legacy_tbl_user_login_ulo`). Une fois la migration validée, pour ne plus rien garder :

```sql
DROP TABLE IF EXISTS legacy_tbl_user_login_ulo;
UPDATE legacy_tbl_author_aut SET user_email_hash = NULL;
```

## Configuration

| Variable | Rôle |
|---|---|
| `TEXTO_BASE_URL` | URL publique (ex. `https://texto.example.com`) |
| `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASS` | PostgreSQL (ou `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`) |
| `SESSION_SECRET`, `SESSION_ENCRYPT` | clés hex de signature et de chiffrement des cookies |
| `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET` | OAuth Discord |
| `TWITCH_CLIENT_ID`, `TWITCH_CLIENT_SECRET` | OAuth Twitch |
| `TEXTO_ADMINS` | admins, ex. `discord:1234,twitch:5678` |
| `ALLOW_NEW_USER` | `false` pour fermer les inscriptions |
| `TEXTO_MAX_LENGTH`, `TEXTO_KEY_LENGTH` | taille max d'un post, longueur des clés |
| `TEXTO_RATE_USER_LIMITE` | créations de posts par minute |
| `TEXTO_LEGACY_PAGES` | dossier des fichiers de la v1 |
| `TEXTO_FACTORIO_ASSETS` | dossier des assets Factorio exportés |
| `TEXTO_FBE_URL` | URL de l'instance Factorio Blueprint Editor (service `texto_fbe`) |

## Profils et pseudos

- Pseudo et avatar sont mis à jour depuis Discord/Twitch au plus une fois par jour, et seulement quand quelqu'un
  ouvre un post ou le profil de l'utilisateur. Texto utilise pour cela les jetons OAuth (accès et rafraîchissement)
  enregistrés à la connexion de l'utilisateur, chiffrés en base (AES-GCM, clé dérivée de `SESSION_SECRET` et
  `SESSION_ENCRYPT` : changer ces secrets oblige les utilisateurs à se reconnecter). Aucun bot n'est nécessaire.
  Pour Twitch, un compte importé de la v1 est aussi mis à jour sans jeton, via le jeton d'application.
- Si le pseudo change, l'identifiant d'URL suit : l'ancien reste actif et redirige (301) vers le nouveau.
- Dès qu'un utilisateur enregistre ses paramètres (identifiant, avatar, liens…), plus rien n'est modifié automatiquement pour lui.
- Profil public : les dossiers servent de navigation (fil d'Ariane, sous-dossiers) et les posts rangés n'apparaissent plus
  à la racine. Seuls les dossiers qui contiennent au moins un post public (directement ou dans un sous-dossier) sont
  visibles ; les autres et leur nom restent privés. La recherche porte sur tous les dossiers. Le chemin du dossier est dans l'URL (`/u/pseudo/Projets/Usines`) ; les anciens liens `?folder=ID` redirigent.
- Lier un compte Discord/Twitch qui a déjà son propre compte sur Texto fusionne les deux comptes en un seul.
- Liens sociaux : chaque icône n'accepte que ses propres liens (un lien Twitch ne passe pas sur l'icône Discord).
  Les aperçus Discord d'un profil n'affichent que Twitch, YouTube, GitHub et Discord (invitation de serveur).
- Aperçus Discord d'un post : statistiques (jamais un extrait du contenu) et, pour un plan Factorio vanilla,
  la capture FBE faite par l'auteur.
- Administration : lier à la main un ID Discord ou Twitch à un compte (bouton « Comptes liés » dans la liste des
  utilisateurs), par exemple pour un ancien compte v1 pas encore reconnecté. Seuls les ID numériques sont acceptés ; à la
  prochaine connexion avec cet ID, la personne arrive sur ce compte. Le pseudo/avatar Twitch est retrouvé automatiquement.
- Administration : « agir en tant que » un autre compte (bouton dans la liste des utilisateurs) pour trier ses dossiers,
  gérer ses tags ou ses posts. Une bannière permanente rappelle le mode et permet de revenir à son compte ; la session
  est liée à l'administrateur d'origine, limitée à 8 h, et journalisée. Pendant ce mode, les actions sur le compte
  lui-même (identité, jetons API, comptes liés, suppression) et l'administration sont interdites.
- Administration : suppression d'un compte sans le bannir, et ouverture/fermeture des inscriptions
  (`ALLOW_NEW_USER` fixe seulement la valeur de départ).
- Au démarrage, une fois, les dates des posts importés de la v1 sont corrigées à partir de la date des fichiers
  `pages/<md5>` et de l'ancienne table `legacy_*` (on ne retient que la date la plus ancienne). Relançable par
  l'admin : `POST /api/admin/restore-legacy-dates`.

## Assets Factorio

```bash
node tools/factorio-exporter/export.mjs
```

Le script trouve le jeu (Steam ou version autonome), le lance en mode dump avec les mods activés,
puis écrit `factorio-assets/` (manifest + icônes). Le jeu doit être fermé. Ce dossier est copié
dans l'image au build Docker (ou monté en volume). Les assets appartiennent à Wube :
ne les publiez pas.

## Factorio Blueprint Editor intégré

Les plans vanilla s'affichent avec le rendu du jeu dans [Factorio Blueprint Editor](https://github.com/FactoryGameFan/factorio-blueprint-editor)
(MIT), dans une iframe. Le site public interdit l'intégration : le dossier `fbe/` construit une instance
auto-hébergée, patchée (`fbe/texto-embed.patch`) pour :

- accepter d'être affichée en iframe par Texto (`FBE_FRAME_ANCESTORS`, en-tête CSP `frame-ancestors`) ;
- renvoyer chaque export (Ctrl+C) à Texto par `postMessage`, uniquement vers l'origine de Texto ;
- recharger un plan envoyé par Texto.

L'auteur d'un post peut ainsi éditer le plan en direct et l'enregistrer comme nouvelle version.
Voir le service `texto_fbe` du `docker-compose.yml` ; côté Texto, `TEXTO_FBE_URL` donne son URL.
Sans FBE, ou pour les plans avec mods, Texto utilise son propre rendu.

## Développement

```bash
docker run -d --name texto-pg -e POSTGRES_PASSWORD=texto -e POSTGRES_USER=texto -p 5432:5432 postgres:18-alpine
TEXTO_DEV=1 TEXTO_DEV_LOGIN=true DATABASE_URL=localhost:5432/texto DATABASE_USER=texto DATABASE_PASSWORD=texto ./gradlew run
cd web && npm install && npm run dev   # http://localhost:5173, connexion de test : /login/dev?name=moi
```

Tests : `./gradlew test` (PostgreSQL via Testcontainers, Docker requis).

## Build

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml build
docker compose -f docker-compose.yml -f docker-compose.build.yml push texto_website texto_fbe
```
