// Import de la liste des mods depuis des sauvegardes de jeux, en local dans le navigateur.
// Chaque jeu fournit son importeur ; on essaie ceux qui reconnaissent les fichiers déposés, et le jeu
// du modpack suit le format trouvé. Pour un nouveau jeu : l'ajouter dans games.ts puis ici.

import { gameLabel, type ModpackGame } from "./games";
import * as factorio from "./saveMods";
import type { ParsedSave } from "./saveMods";

export interface GameSave extends ParsedSave { game: ModpackGame }
export interface ImportResult { saves: GameSave[]; failed: string[] }

interface Importer {
  game: ModpackGame;
  /** Fichiers que cet importeur sait lire (filtre avant lecture : le reste n'est jamais ouvert). */
  relevant(f: File): boolean;
  parse(files: File[]): Promise<{ saves: ParsedSave[]; failed: string[] }>;
}

export const IMPORTERS: Importer[] = [
  { game: "factorio", relevant: factorio.relevant, parse: factorio.parseSelection },
];

export const relevantAny = (f: File) => IMPORTERS.some((i) => i.relevant(f));

/** Jeux qu'on sait importer, pour les textes d'aide (« Factorio, … »). */
export const importableGames = () => IMPORTERS.map((i) => gameLabel(i.game)).join(", ");

/** Passe les fichiers à chaque importeur concerné. Un fichier n'est « illisible » que si aucun jeu ne l'a reconnu. */
export async function parseSaves(files: File[]): Promise<ImportResult> {
  const saves: GameSave[] = [];
  const failedBy = new Map<string, number>();
  let tried = 0;
  for (const imp of IMPORTERS) {
    const mine = files.filter(imp.relevant);
    if (!mine.length) continue;
    tried++;
    const r = await imp.parse(mine);
    saves.push(...r.saves.map((s) => ({ ...s, game: imp.game })));
    for (const f of r.failed) failedBy.set(f, (failedBy.get(f) ?? 0) + 1);
  }
  const failed = saves.length ? [...failedBy].filter(([, n]) => n === tried).map(([f]) => f) : [...failedBy.keys()];
  return { saves, failed };
}

/** Jeu majoritaire parmi les sauvegardes trouvées. */
export function mainGame(saves: GameSave[]): ModpackGame | null {
  const n = new Map<ModpackGame, number>();
  for (const s of saves) n.set(s.game, (n.get(s.game) ?? 0) + 1);
  return [...n].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;
}

// ---------------------------------------------------------------- glisser-déposer / coller

/**
 * Vrai si ce qui est glissé / collé peut contenir une sauvegarde : un dossier, ou un fichier qu'un importeur sait lire.
 * À appeler pendant l'événement (les entrées ne sont plus lisibles ensuite).
 */
export function maybeSaves(dt: DataTransfer | null): boolean {
  if (!dt) return false;
  for (const item of Array.from(dt.items ?? [])) {
    if (item.kind !== "file") continue;
    if (item.webkitGetAsEntry?.()?.isDirectory) return true;
    const f = item.getAsFile();
    if (f && relevantAny(f)) return true;
  }
  return Array.from(dt.files ?? []).some(relevantAny);
}

/** Fichiers utiles d'un glisser-déposer ou d'un collage, dossiers compris (parcourus sur 3 niveaux au plus). */
export async function filesFromDataTransfer(dt: DataTransfer): Promise<File[]> {
  // Les entrées doivent être récupérées avant le premier await.
  const entries = Array.from(dt.items ?? []).filter((i) => i.kind === "file").map((i) => i.webkitGetAsEntry?.()).filter((x): x is FileSystemEntry => !!x);
  const plain = Array.from(dt.files ?? []);
  if (!entries.length) return plain.filter(relevantAny);
  const files: File[] = [];
  const walk = async (entry: FileSystemEntry, prefix: string): Promise<void> => {
    if (entry.isFile) {
      const f = await new Promise<File>((res, rej) => (entry as FileSystemFileEntry).file(res, rej));
      Object.defineProperty(f, "webkitRelativePath", { value: prefix + entry.name });
      if (relevantAny(f)) files.push(f);
    } else if (entry.isDirectory) {
      const reader = (entry as FileSystemDirectoryEntry).createReader();
      for (;;) {
        const batch = await new Promise<FileSystemEntry[]>((res, rej) => reader.readEntries(res, rej));
        if (!batch.length) break;
        for (const c of batch) if (c.isFile || prefix.split("/").length < 3) await walk(c, prefix + entry.name + "/");
      }
    }
  };
  for (const e of entries) await walk(e, "");
  return files;
}
