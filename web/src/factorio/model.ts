import { decodePlan, isBlueprint, isBlueprintBook, isDeconstructionPlanner, isUpgradePlanner, type Plan } from "@jensforstmann/factorio-blueprint-tools";
import { ENTITIES, LEGACY_RENAMES, guessMod, type Category } from "./data";
import type { AssetManifest } from "./assets";

export interface SignalRef { type?: string; name: string; quality?: string }

export interface RenderEntity {
  number: number;
  name: string;
  /** centre, en tuiles */
  x: number;
  y: number;
  /** taille après rotation */
  w: number;
  h: number;
  /** 0..15 (directions 2.0) */
  direction: number;
  cat: Category;
  mod: string | null;
  known: boolean;
  /** taille estimée (entité de mod inconnue) */
  guessed?: boolean;
  recipe?: string;
  quality?: string;
  type?: string; // input/output (souterrains, chargeurs)
  raw: Record<string, unknown>;
}

export interface RenderTile { name: string; x: number; y: number }
export interface Wire { from: number; fromConnector: number; to: number; toConnector: number }

export interface BlueprintModel {
  /** plan décodé d'origine (pour ré-encoder un plan d'un livre) */
  raw: unknown;
  kind: "blueprint";
  label: string;
  description: string;
  icons: SignalRef[];
  version: string;
  legacy: boolean;
  entities: RenderEntity[];
  tiles: RenderTile[];
  wires: Wire[];
  bounds: { minX: number; minY: number; maxX: number; maxY: number };
}

export interface BookModel {
  /** plan décodé d'origine (pour ré-encoder un plan d'un livre) */
  raw: unknown;
  kind: "book";
  label: string;
  description: string;
  icons: SignalRef[];
  version: string;
  children: { index: number; node: PlanModel }[];
  activeIndex: number;
}

export interface PlannerModel {
  /** plan décodé d'origine (pour ré-encoder un plan d'un livre) */
  raw: unknown;
  kind: "upgrade" | "deconstruction";
  label: string;
  description: string;
  icons: SignalRef[];
  version: string;
  lines: { from?: string; to?: string }[];
}

export type PlanModel = BlueprintModel | BookModel | PlannerModel;

/** Version encodée sur 64 bits : major.minor.patch.build (16 bits chacun). */
export function decodeVersion(v: number | undefined): { text: string; major: number } {
  if (!v) return { text: "?", major: 2 };
  const n = BigInt(Math.trunc(v));
  const part = (shift: bigint) => Number((n >> shift) & 0xffffn);
  const major = part(48n);
  return { text: `${major}.${part(32n)}.${part(16n)}`, major };
}

export function parsePlanString(str: string, manifest?: AssetManifest | null): PlanModel {
  const plan = decodePlan(str.trim());
  return toModel(plan, manifest ?? null);
}

function toModel(plan: Plan, manifest: AssetManifest | null): PlanModel {
  return { ...toModelInner(plan, manifest), raw: plan } as PlanModel;
}

type Bare<T> = Omit<T, "raw">;

function toModelInner(plan: Plan, manifest: AssetManifest | null): Bare<BlueprintModel> | Bare<BookModel> | Bare<PlannerModel> {
  if (isBlueprintBook(plan)) {
    const b = plan.blueprint_book;
    return {
      kind: "book",
      label: b.label ?? "",
      description: b.description ?? "",
      icons: (b.icons ?? []).map((i) => i.signal),
      version: decodeVersion(b.version).text,
      activeIndex: b.active_index ?? 0,
      children: (b.blueprints ?? [])
        .map((child) => ({ index: child.index, node: toModel(child, manifest) }))
        .sort((a, b) => a.index - b.index),
    };
  }
  if (isUpgradePlanner(plan)) {
    const u = plan.upgrade_planner;
    return {
      kind: "upgrade", label: u.label ?? "", description: u.settings?.description ?? u.description ?? "",
      icons: (u.settings?.icons ?? []).map((i) => i.signal), version: decodeVersion(u.version).text,
      lines: (u.settings?.mappers ?? []).sort((a, b) => a.index - b.index).map((m) => ({ from: m.from?.name, to: m.to?.name })),
    };
  }
  if (isDeconstructionPlanner(plan)) {
    const d = plan.deconstruction_planner;
    return {
      kind: "deconstruction", label: d.label ?? "", description: d.settings?.description ?? "",
      icons: (d.settings?.icons ?? []).map((i) => i.signal), version: decodeVersion(d.version).text,
      lines: [
        ...(d.settings?.entity_filters ?? []).map((f) => ({ from: f.name })),
        ...(d.settings?.tile_filters ?? []).map((f) => ({ from: f.name })),
      ],
    };
  }
  if (isBlueprint(plan)) return blueprintModel(plan.blueprint as unknown as RawBlueprint, manifest);
  throw new Error("unsupported_plan");
}

interface RawEntity {
  entity_number: number; name: string; position: { x: number; y: number }; direction?: number;
  recipe?: string; quality?: string; type?: string; connections?: Record<string, unknown>;
  [k: string]: unknown;
}
interface RawBlueprint {
  label?: string; description?: string; icons?: { signal: SignalRef }[]; version: number;
  entities?: RawEntity[]; tiles?: { name: string; position: { x: number; y: number } }[];
  wires?: [number, number, number, number][];
}

function blueprintModel(bp: RawBlueprint, manifest: AssetManifest | null): Omit<BlueprintModel, "raw"> {
  const { text, major } = decodeVersion(bp.version);
  const legacy = major < 2;
  const wires: Wire[] = (bp.wires ?? []).map(([a, ac, b, bc]) => ({ from: a, fromConnector: ac, to: b, toConnector: bc }));

  const entities: RenderEntity[] = (bp.entities ?? []).map((raw) => {
    const name = legacy ? (LEGACY_RENAMES[raw.name] ?? raw.name) : raw.name;
    // 1.1 : 8 directions (0..7) ; 2.0 : 16 directions.
    const direction = ((raw.direction ?? 0) * (legacy ? 2 : 1)) % 16;
    const proto = manifest?.prototypes[name] ?? manifest?.prototypes[raw.name];
    const vanilla = ENTITIES[name];
    const baseW = proto?.w ?? vanilla?.w ?? 1;
    const baseH = proto?.h ?? vanilla?.h ?? 1;
    const swap = baseW !== baseH && (direction === 4 || direction === 12);
    return {
      number: raw.entity_number,
      name,
      x: raw.position.x,
      y: raw.position.y,
      w: swap ? baseH : baseW,
      h: swap ? baseW : baseH,
      direction,
      cat: vanilla?.cat ?? (proto ? categoryFromType(proto.type) : "unknown"),
      mod: proto?.mod ?? vanilla?.mod ?? guessMod(name),
      known: Boolean(vanilla || proto),
      recipe: raw.recipe,
      quality: raw.quality,
      type: raw.type,
      raw,
    };
  });

  guessUnknownSizes(entities);

  // Fils 1.1 (objet `connections`) -> liste.
  if (legacy) {
    for (const ent of bp.entities ?? []) {
      const conns = ent.connections as Record<string, Record<string, { entity_id: number; circuit_id?: number }[]>> | undefined;
      if (!conns) continue;
      for (const [point, colors] of Object.entries(conns)) {
        if (typeof colors !== "object" || !colors) continue;
        for (const [color, targets] of Object.entries(colors)) {
          if (!Array.isArray(targets)) continue;
          const base = color === "red" ? 1 : 2;
          const fromC = base + (point === "2" ? 2 : 0);
          for (const t of targets) {
            if (t.entity_id > ent.entity_number) {
              wires.push({ from: ent.entity_number, fromConnector: fromC, to: t.entity_id, toConnector: base + ((t.circuit_id ?? 1) === 2 ? 2 : 0) });
            }
          }
        }
      }
    }
  }

  const tiles: RenderTile[] = (bp.tiles ?? []).map((t) => ({ name: t.name, x: t.position.x, y: t.position.y }));

  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const en of entities) {
    minX = Math.min(minX, en.x - en.w / 2); maxX = Math.max(maxX, en.x + en.w / 2);
    minY = Math.min(minY, en.y - en.h / 2); maxY = Math.max(maxY, en.y + en.h / 2);
  }
  for (const t of tiles) {
    minX = Math.min(minX, t.x); maxX = Math.max(maxX, t.x + 1);
    minY = Math.min(minY, t.y); maxY = Math.max(maxY, t.y + 1);
  }
  if (!isFinite(minX)) { minX = minY = 0; maxX = maxY = 1; }

  return {
    kind: "blueprint",
    label: bp.label ?? "",
    description: bp.description ?? "",
    icons: (bp.icons ?? []).map((i) => i.signal),
    version: text,
    legacy,
    entities,
    tiles,
    wires,
    bounds: { minX, minY, maxX, maxY },
  };
}

function categoryFromType(type: string): Category {
  if (/belt|splitter|loader/.test(type)) return "belt";
  if (type === "inserter") return "inserter";
  if (/container/.test(type)) return "chest";
  if (/assembling-machine|rocket-silo/.test(type)) return "assembler";
  if (type === "furnace") return "furnace";
  if (/mining-drill|offshore-pump/.test(type)) return "mining";
  if (/generator|boiler|solar|accumulator|reactor|burner-generator|fusion/.test(type)) return "power";
  if (type === "electric-pole") return "pole";
  if (/pipe|pump|storage-tank/.test(type)) return "pipe";
  if (/rail|train-stop/.test(type)) return "rail";
  if (/locomotive|wagon|car|spider/.test(type)) return "train";
  if (/roboport|beacon|radar/.test(type)) return "logistics";
  if (/turret|wall|gate|mine/.test(type)) return "combat";
  if (/combinator|lamp|speaker|power-switch|display/.test(type)) return "circuit";
  if (type === "lab") return "research";
  return "misc";
}

/** Liste des blueprints simples d'un plan (pour la navigation dans les livres). */
export function bomOf(model: BlueprintModel): { name: string; count: number; known: boolean; mod: string | null }[] {
  const counts = new Map<string, { name: string; count: number; known: boolean; mod: string | null }>();
  for (const e of model.entities) {
    const c = counts.get(e.name) ?? { name: e.name, count: 0, known: e.known, mod: e.mod };
    c.count++;
    counts.set(e.name, c);
  }
  for (const t of model.tiles) {
    const key = "tile:" + t.name;
    const c = counts.get(key) ?? { name: t.name, count: 0, known: true, mod: null };
    c.count++;
    counts.set(key, c);
  }
  return [...counts.values()].sort((a, b) => b.count - a.count);
}

/** Texte enrichi Factorio ([item=iron-plate], [color=red]...) -> segments. */
export function richText(s: string): ({ text: string } | { icon: string; type: string })[] {
  const out: ({ text: string } | { icon: string; type: string })[] = [];
  const re = /\[(item|entity|fluid|virtual-signal|recipe|technology|planet|quality|space-location|tile|img)=([^\],]+)(?:,[^\]]*)?\]|\[\/?(?:color|font)[^\]]*\]/g;
  let last = 0;
  for (const m of s.matchAll(re)) {
    if (m.index! > last) out.push({ text: s.slice(last, m.index) });
    if (m[1]) out.push({ type: m[1], icon: m[2] });
    last = m.index! + m[0].length;
  }
  if (last < s.length) out.push({ text: s.slice(last) });
  return out;
}

export function looksLikeBlueprint(s: string): boolean {
  const t = s.trim();
  return t.length > 20 && t.startsWith("0") && /^[A-Za-z0-9+/=]+$/.test(t);
}

// ------------------------------------------------------------------ tailles des entités inconnues

const SMALL_NAME = /chest|box|crate|inserter|belt|pole|pipe|lamp|combinator|wall|gate|signal|loader|valve|splitter|speaker|sensor|node|warehouse-small/;
const BIG_NAME = /machine|furnace|assembl|plant|refinery|smelter|factory|reactor|chamber|lab|drill|miner|crusher|centrifuge|boiler|generator|silo|electroly[sz]er|press|forge|foundry|kiln|mixer|tank|tower|station|facility|fabricator|hub|bay|extractor|pump|turret|beacon|roboport|accumulator|solar|engine|turbine|condenser|separator|electrolysis|greenhouse|incubator|vat|digester|sorter|grinder|washer|kiln|plant/;

/**
 * Un plan ne contient pas la taille des entités. Pour celles qu'on ne connaît pas (mods), on l'estime :
 * - parité : une position en x.5 implique une largeur impaire, une position entière une largeur paire ;
 * - espace libre : on agrandit tant que l'emprise ne chevauche pas d'autres entités (les bras et tapis
 *   autour d'une machine la bornent) ;
 * - plafond selon le nom (coffre, tuyau… petits ; machine, four… grands).
 */
function guessUnknownSizes(entities: RenderEntity[]) {
  // tuile -> numéro de l'entité qui l'occupe
  const occupied = new Map<string, number>();
  const footprint = (x: number, y: number, w: number, h: number) => {
    const out: string[] = [];
    const x0 = Math.round(x - w / 2), y0 = Math.round(y - h / 2);
    for (let dx = 0; dx < w; dx++) for (let dy = 0; dy < h; dy++) out.push(`${x0 + dx},${y0 + dy}`);
    return out;
  };
  const mark = (e: RenderEntity) => footprint(e.x, e.y, e.w, e.h).forEach((k) => occupied.set(k, e.number));
  const free = (e: RenderEntity, w: number, h: number) =>
    footprint(e.x, e.y, w, h).every((k) => { const o = occupied.get(k); return o === undefined || o === e.number; });
  const isRail = (e: RenderEntity) => e.cat === "rail" || e.cat === "train";

  const minimal = (e: RenderEntity): [number, number] => [Number.isInteger(e.x) ? 2 : 1, Number.isInteger(e.y) ? 2 : 1];
  for (const e of entities) {
    if (isRail(e)) continue;
    if (e.known) mark(e);
    else { const [w, h] = minimal(e); footprint(e.x, e.y, w, h).forEach((k) => occupied.set(k, e.number)); }
  }

  const unknown = entities.filter((e) => !e.known);
  // Les noms de machines d'abord : elles prennent la place avant les petites entités voisines.
  unknown.sort((a, b) => Number(BIG_NAME.test(b.name)) - Number(BIG_NAME.test(a.name)));
  for (const e of unknown) {
    const n = e.name.toLowerCase();
    const cap = SMALL_NAME.test(n) ? 2 : BIG_NAME.test(n) ? 6 : 3;
    let [w, h] = minimal(e);
    let grew = true;
    while (grew) {
      grew = false;
      if (w + 2 <= cap && h + 2 <= cap && free(e, w + 2, h + 2)) { w += 2; h += 2; grew = true; continue; }
      if (w + 2 <= cap && w < h + 2 && free(e, w + 2, h)) { w += 2; grew = true; continue; }
      if (h + 2 <= cap && h < w + 2 && free(e, w, h + 2)) { h += 2; grew = true; }
    }
    e.w = w; e.h = h; e.guessed = w > 1 || h > 1;
    mark(e);
  }
}
