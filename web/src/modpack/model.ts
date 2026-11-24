// Contenu d'un post « modpack » : un objet JSON (le titre et la description courte sont ceux du post).

export interface ModpackMod {
  name: string;
  /** Version exacte ("" : n'importe laquelle / la dernière). */
  version: string;
  /** Lien de téléchargement ou page, pour un mod hors du portail officiel. */
  url?: string;
  note?: string;
}

export interface Modpack {
  format: 1;
  factorioVersion: string;
  /** Image de présentation (URL). */
  image: string;
  /** Présentation détaillée en Markdown. */
  readme: string;
  mods: ModpackMod[];
}

export interface ModInfo {
  name: string; found: boolean; title?: string | null; summary?: string | null; owner?: string | null;
  category?: string | null; thumbnail?: string | null; latestVersion?: string | null; version?: string | null;
  factorioVersion?: string | null; dependencies: string[]; downloadUrl?: string | null; downloads?: number | null;
}

/** Mods livrés avec le jeu (pas sur le portail). */
export const BUILTIN = new Set(["base", "core", "space-age", "quality", "elevated-rails"]);
export const BUILTIN_TITLES: Record<string, string> = {
  base: "Factorio", core: "Core", "space-age": "Space Age", quality: "Quality", "elevated-rails": "Elevated Rails",
};

export const emptyModpack = (): Modpack => ({ format: 1, factorioVersion: "", image: "", readme: "", mods: [] });

export function parseModpack(content: string): Modpack {
  try {
    const j = JSON.parse(content) as Partial<Modpack>;
    return {
      format: 1,
      factorioVersion: typeof j.factorioVersion === "string" ? j.factorioVersion : "",
      image: typeof j.image === "string" ? j.image : "",
      readme: typeof j.readme === "string" ? j.readme : "",
      mods: Array.isArray(j.mods)
        ? j.mods.filter((m) => m && typeof m.name === "string" && m.name.trim()).map((m) => ({
          name: m.name.trim(), version: typeof m.version === "string" ? m.version : "",
          ...(m.url ? { url: String(m.url) } : {}), ...(m.note ? { note: String(m.note) } : {}),
        }))
        : [],
    };
  } catch {
    return emptyModpack();
  }
}

/** Seuls les liens http(s) sont affichés comme liens (pas de javascript:, data:…). Vérifié au rendu. */
export const safeUrl = (u: string) => /^https?:\/\/[^\s]+$/i.test(u.trim());

export const serializeModpack = (m: Modpack) => JSON.stringify(m, null, 2);

/** mod-list.json à déposer dans le dossier « mods » du jeu. */
export function modListJson(m: Modpack): string {
  const names = new Set(m.mods.map((x) => x.name));
  const mods = [{ name: "base", enabled: true }, ...m.mods.filter((x) => x.name !== "base").map((x) => ({ name: x.name, enabled: true }))];
  // Les extensions officielles absentes du pack sont désactivées explicitement.
  for (const b of ["space-age", "quality", "elevated-rails"]) if (!names.has(b)) mods.push({ name: b, enabled: false });
  return JSON.stringify({ mods }, null, 2);
}

// ---------------------------------------------------------------- dépendances

export type DepKind = "required" | "optional" | "hidden" | "incompatible" | "unordered" | "recommended";
export interface Dependency { name: string; kind: DepKind; constraint: string }

/**
 * Préfixes de info.json (lua-api.factorio.com, mod-structure) : aucun = obligatoire, « ~ » = obligatoire sans effet sur
 * l'ordre de chargement, « ? » = optionnel, « (?) » = optionnel caché, « + » = recommandé (pas obligatoire), « ! » = incompatible.
 */
export function parseDependency(s: string): Dependency | null {
  const m = /^\s*(\(\?\)|\?|!|~|\+)?\s*([A-Za-z0-9_\-. +]+?)\s*((?:<=|>=|=|<|>)\s*[\d.]+)?\s*$/.exec(s);
  if (!m) return null;
  const kind: DepKind = m[1] === "?" ? "optional" : m[1] === "(?)" ? "hidden" : m[1] === "!" ? "incompatible" : m[1] === "~" ? "unordered" : m[1] === "+" ? "recommended" : "required";
  return { name: m[2], kind, constraint: m[3]?.replace(/\s+/g, " ") ?? "" };
}

export const isModpack = (info?: ModInfo) => info?.category === "mod-packs";

export interface TreeNode {
  mod: ModpackMod;
  info?: ModInfo;
  children: TreeNode[];
  /** Enfants rattachés sans être requis (clé : nom) — le lien dépend du parent, un nœud étant partagé. */
  soft?: Map<string, "optional" | "recommended">;
  /** Nombre de mods contenus, récursivement. */
  total: number;
  /** Dépendance requise absente de la liste. */
  missing?: boolean;
}

/**
 * Arborescence d'affichage :
 *  - tout mod qui a des dépendances requises les contient (récursivement) ; un modpack contient en plus ses
 *    dépendances optionnelles / recommandées présentes dans la liste ; un mod ordinaire, celles qui ne seraient
 *    rangées nulle part ailleurs ; une dépendance partagée
 *    (flib…) apparaît sous chacun des mods qui l'exigent ;
 *  - une dépendance requise absente de la liste apparaît, marquée « absente », sous le mod qui l'exige ;
 *  - à la racine : les mods dont aucun autre mod de la liste n'a besoin.
 * Les nœuds sont partagés entre parents (calculés une fois par mod) ; une dépendance circulaire est coupée.
 */
export function buildTree(mods: ModpackMod[], infos: Map<string, ModInfo>): { roots: TreeNode[]; missing: string[] } {
  const byName = new Map(mods.map((m) => [m.name, m]));
  const deps = (name: string) => (infos.get(name)?.dependencies ?? [])
    .map(parseDependency)
    .filter((d): d is Dependency => !!d && d.kind !== "incompatible" && !BUILTIN.has(d.name) && d.name !== name);
  const isRequired = (d: Dependency) => d.kind === "required" || d.kind === "unordered";
  // Un mod range ses dépendances requises ; un modpack (catégorie « mod-packs ») range aussi ses dépendances
  // optionnelles / recommandées présentes dans la liste. Un mod ordinaire ne range un optionnel que s'il ne serait
  // sinon rangé nulle part (modpack non catégorisé) : sans ça les mods de compatibilité, qui déclarent beaucoup
  // d'optionnels, « contiendraient » des planètes entières.
  const anchored = new Set<string>();
  for (const m of mods) {
    const pack = isModpack(infos.get(m.name));
    for (const d of deps(m.name)) if (pack || isRequired(d)) anchored.add(d.name);
  }
  const subMods = (name: string) => {
    const pack = isModpack(infos.get(name));
    const out = new Map<string, "required" | "optional" | "recommended">();
    for (const d of deps(name)) {
      if (isRequired(d)) out.set(d.name, "required");
      else if (byName.has(d.name) && !out.has(d.name) && (pack || !anchored.has(d.name)))
        out.set(d.name, d.kind === "recommended" ? "recommended" : "optional");
    }
    return out;
  };

  const missing = new Set<string>();
  const placed = new Set<string>();
  const built = new Map<string, TreeNode>();
  const visiting = new Set<string>();

  const node = (mod: ModpackMod): TreeNode => {
    const done = built.get(mod.name);
    if (done) return done;
    visiting.add(mod.name);
    const children: TreeNode[] = [];
    const soft = new Map<string, "optional" | "recommended">();
    // Les dépendances viennent du portail pour la version listée ; si le portail ne l'a pas (repli sur la dernière),
    // elles peuvent différer : on range les mods présents mais on ne signale pas d'absent.
    const info = infos.get(mod.name);
    const exact = !mod.version || info?.version === mod.version;
    for (const [d, link] of subMods(mod.name)) {
      if (visiting.has(d)) continue; // boucle
      const m = byName.get(d);
      if (!m) {
        if (!exact || link !== "required") continue;
        missing.add(d);
        children.push({ mod: { name: d, version: "" }, children: [], total: 0, missing: true });
        continue;
      }
      placed.add(d);
      if (link !== "required") soft.set(d, link);
      children.push(node(m));
    }
    visiting.delete(mod.name);
    // Nombre de mods distincts contenus (une bibliothèque présente à plusieurs niveaux ne compte qu'une fois).
    const names = new Set<string>();
    const collect = (n: TreeNode) => n.children.forEach((c) => { if (!names.has(c.mod.name)) { names.add(c.mod.name); collect(c); } });
    const n: TreeNode = { mod, info: infos.get(mod.name), children, total: 0, ...(soft.size ? { soft } : {}) };
    collect(n);
    n.total = names.size;
    built.set(mod.name, n);
    return n;
  };

  for (const m of mods) node(m);
  const roots = mods.filter((m) => !placed.has(m.name)).map((m) => built.get(m.name)!);
  // Garde-fou (dépendances circulaires) : tout mod non atteignable depuis une racine devient une racine.
  const seen = new Set<string>();
  const visit = (n: TreeNode) => { if (seen.has(n.mod.name)) return; seen.add(n.mod.name); n.children.forEach(visit); };
  roots.forEach(visit);
  for (const m of mods) if (!seen.has(m.name)) { const n = built.get(m.name)!; roots.push(n); visit(n); }
  // Les modpacks d'abord (le plus gros en tête), puis les autres mods dans l'ordre de la liste.
  const rank = (n: TreeNode) => (isModpack(n.info) ? -1 - n.total : 0);
  roots.sort((a, b) => rank(a) - rank(b));
  return { roots, missing: [...missing] };
}

export const portalUrl = (name: string) => `https://mods.factorio.com/mod/${encodeURIComponent(name)}`;
