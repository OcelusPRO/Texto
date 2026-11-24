// Sous-catégories des modpacks : le jeu. Rangé dans le champ « language » du post, comme le langage du code.
// Nouveau jeu : l'ajouter ici et dans PostService.MODPACK_GAMES (backend), déclarer son lecteur de sauvegardes
// dans importers.ts (le jeu est alors reconnu au glisser-déposer), et sa vue détaillée dans ModpackView si besoin.

export const MODPACK_GAMES = [{ key: "factorio", label: "Factorio" }] as const;
export type ModpackGame = (typeof MODPACK_GAMES)[number]["key"];

export const DEFAULT_GAME: ModpackGame = "factorio";

export const gameOf = (language: string | null | undefined): ModpackGame =>
  MODPACK_GAMES.find((g) => g.key === language?.toLowerCase())?.key ?? DEFAULT_GAME;

export const gameLabel = (language: string | null | undefined) =>
  MODPACK_GAMES.find((g) => g.key === gameOf(language))!.label;
