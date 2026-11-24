// Cache local (localStorage) des fiches du portail des mods : un rechargement de page affiche tout de suite les
// fiches déjà vues (pas d'arbre « à plat » le temps de la requête) ; elles sont rafraîchies en arrière-plan.
import type { ModInfo } from "./model";

const KEY = "texto-mod-infos";
/** Au-delà, une fiche n'est plus affichée depuis le cache (le serveur garde les siennes 12 h). */
const MAX_AGE = 7 * 24 * 3600_000;
/** Plafond du nombre de fiches gardées (les plus anciennes partent d'abord). */
const MAX_ENTRIES = 1500;

interface Entry { at: number; info: ModInfo }
type Store = Record<string, Entry>;

const refKey = (name: string, version?: string) => `${name}@${version ?? ""}`;

function load(): Store {
  try {
    const s = JSON.parse(localStorage.getItem(KEY) ?? "{}");
    return s && typeof s === "object" ? s : {};
  } catch {
    return {};
  }
}

/** Fiches en cache pour ces mods ; `at` = date de la plus ancienne (pour savoir si un rafraîchissement s'impose). */
export function cachedInfos(refs: { name: string; version?: string }[]): { infos: ModInfo[]; at: number; complete: boolean } {
  const store = load();
  const now = Date.now();
  const infos: ModInfo[] = [];
  let at = now;
  for (const r of refs) {
    const e = store[refKey(r.name, r.version)];
    if (!e || now - e.at > MAX_AGE) continue;
    infos.push(e.info);
    at = Math.min(at, e.at);
  }
  return { infos, at, complete: infos.length === refs.length };
}

export function storeInfos(refs: { name: string; version?: string }[], infos: ModInfo[]) {
  try {
    const store = load();
    const byName = new Map(infos.map((i) => [i.name, i]));
    const now = Date.now();
    for (const r of refs) {
      const i = byName.get(r.name);
      if (i) store[refKey(r.name, r.version)] = { at: now, info: i };
    }
    const entries = Object.entries(store).filter(([, e]) => now - e.at <= MAX_AGE).sort((a, b) => b[1].at - a[1].at);
    localStorage.setItem(KEY, JSON.stringify(Object.fromEntries(entries.slice(0, MAX_ENTRIES))));
  } catch {
    /* stockage plein ou indisponible : le cache est facultatif */
  }
}
