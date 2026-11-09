// Données intégrées des entités vanilla (Factorio 2.0, Space Age, Elevated Rails, Quality)
// pour le rendu schématique sans assets du jeu : taille en tuiles et catégorie (couleur).

export type Category =
  | "belt" | "inserter" | "chest" | "assembler" | "furnace" | "mining" | "power" | "pole" | "pipe"
  | "rail" | "train" | "logistics" | "combat" | "circuit" | "research" | "space" | "agri" | "misc" | "unknown";

export interface EntityInfo { w: number; h: number; cat: Category; mod: string }

export const CATEGORY_COLORS: Record<Category, string> = {
  belt: "#d4a72c", inserter: "#5b8def", chest: "#a07a4b", assembler: "#6b7f99", furnace: "#c2593b",
  mining: "#8a6d3b", power: "#3fae6a", pole: "#9a8660", pipe: "#4c8ca8", rail: "#7d7d7d", train: "#b0413e",
  logistics: "#8b5cf6", combat: "#b91c1c", circuit: "#2fa39c", research: "#c084fc", space: "#64748b",
  agri: "#65a30d", misc: "#94a3b8", unknown: "#6b7280",
};

const B = "base", SA = "space-age", ER = "elevated-rails";
const e = (w: number, h: number, cat: Category, mod = B): EntityInfo => ({ w, h, cat, mod });

export const ENTITIES: Record<string, EntityInfo> = {
  // Convoyeurs
  "transport-belt": e(1, 1, "belt"), "fast-transport-belt": e(1, 1, "belt"), "express-transport-belt": e(1, 1, "belt"),
  "turbo-transport-belt": e(1, 1, "belt", SA),
  "underground-belt": e(1, 1, "belt"), "fast-underground-belt": e(1, 1, "belt"), "express-underground-belt": e(1, 1, "belt"),
  "turbo-underground-belt": e(1, 1, "belt", SA),
  "splitter": e(2, 1, "belt"), "fast-splitter": e(2, 1, "belt"), "express-splitter": e(2, 1, "belt"), "turbo-splitter": e(2, 1, "belt", SA),
  "loader": e(1, 2, "belt"), "fast-loader": e(1, 2, "belt"), "express-loader": e(1, 2, "belt"), "turbo-loader": e(1, 2, "belt", SA),
  "lane-splitter": e(1, 1, "belt"), "linked-belt": e(1, 1, "belt"),
  // Bras
  "burner-inserter": e(1, 1, "inserter"), "inserter": e(1, 1, "inserter"), "long-handed-inserter": e(1, 1, "inserter"),
  "fast-inserter": e(1, 1, "inserter"), "bulk-inserter": e(1, 1, "inserter"), "stack-inserter": e(1, 1, "inserter", SA),
  // Coffres
  "wooden-chest": e(1, 1, "chest"), "iron-chest": e(1, 1, "chest"), "steel-chest": e(1, 1, "chest"),
  "active-provider-chest": e(1, 1, "logistics"), "passive-provider-chest": e(1, 1, "logistics"), "storage-chest": e(1, 1, "logistics"),
  "buffer-chest": e(1, 1, "logistics"), "requester-chest": e(1, 1, "logistics"), "infinity-chest": e(1, 1, "chest"),
  "infinity-pipe": e(1, 1, "pipe"), "linked-chest": e(1, 1, "chest"),
  // Production
  "assembling-machine-1": e(3, 3, "assembler"), "assembling-machine-2": e(3, 3, "assembler"), "assembling-machine-3": e(3, 3, "assembler"),
  "chemical-plant": e(3, 3, "assembler"), "oil-refinery": e(5, 5, "assembler"), "centrifuge": e(3, 3, "assembler"),
  "stone-furnace": e(2, 2, "furnace"), "steel-furnace": e(2, 2, "furnace"), "electric-furnace": e(3, 3, "furnace"),
  "foundry": e(5, 5, "furnace", SA), "electromagnetic-plant": e(4, 4, "assembler", SA), "cryogenic-plant": e(5, 5, "assembler", SA),
  "recycler": e(2, 4, "assembler", "quality"), "crusher": e(2, 3, "assembler", SA), "biochamber": e(3, 3, "agri", SA),
  "captive-biter-spawner": e(5, 5, "agri", SA), "agricultural-tower": e(3, 3, "agri", SA), "heating-tower": e(3, 3, "power", SA),
  "lab": e(3, 3, "research"), "biolab": e(5, 5, "research", SA),
  // Extraction
  "burner-mining-drill": e(2, 2, "mining"), "electric-mining-drill": e(3, 3, "mining"), "big-mining-drill": e(5, 5, "mining", SA),
  "pumpjack": e(3, 3, "mining"), "offshore-pump": e(1, 2, "pipe"), "asteroid-collector": e(3, 3, "space", SA),
  // Énergie
  "boiler": e(3, 2, "power"), "steam-engine": e(3, 5, "power"), "steam-turbine": e(3, 5, "power"), "solar-panel": e(3, 3, "power"),
  "accumulator": e(2, 2, "power"), "nuclear-reactor": e(5, 5, "power"), "heat-exchanger": e(3, 2, "power"), "heat-pipe": e(1, 1, "power"),
  "fusion-reactor": e(6, 6, "power", SA), "fusion-generator": e(3, 5, "power", SA), "lightning-rod": e(1, 1, "power", SA),
  "lightning-collector": e(2, 2, "power", SA), "burner-generator": e(3, 5, "power"), "electric-energy-interface": e(2, 2, "power"),
  // Poteaux
  "small-electric-pole": e(1, 1, "pole"), "medium-electric-pole": e(1, 1, "pole"), "big-electric-pole": e(2, 2, "pole"),
  "substation": e(2, 2, "pole"), "power-switch": e(2, 2, "circuit"),
  // Fluides
  "pipe": e(1, 1, "pipe"), "pipe-to-ground": e(1, 1, "pipe"), "pump": e(1, 2, "pipe"), "storage-tank": e(3, 3, "pipe"),
  // Rails (boîtes englobantes approximatives)
  "straight-rail": e(2, 2, "rail"), "half-diagonal-rail": e(2, 4, "rail"), "curved-rail-a": e(2, 5, "rail"), "curved-rail-b": e(3, 4, "rail"),
  "legacy-straight-rail": e(2, 2, "rail"), "legacy-curved-rail": e(4, 8, "rail"),
  "elevated-straight-rail": e(2, 2, "rail", ER), "elevated-half-diagonal-rail": e(2, 4, "rail", ER),
  "elevated-curved-rail-a": e(2, 5, "rail", ER), "elevated-curved-rail-b": e(3, 4, "rail", ER),
  "rail-ramp": e(4, 16, "rail", ER), "rail-support": e(4, 4, "rail", ER),
  "rail-signal": e(1, 1, "rail"), "rail-chain-signal": e(1, 1, "rail"), "train-stop": e(2, 2, "rail"),
  "locomotive": e(2, 6, "train"), "cargo-wagon": e(2, 6, "train"), "fluid-wagon": e(2, 6, "train"), "artillery-wagon": e(2, 6, "train"),
  "car": e(2, 3, "train"), "tank": e(3, 4, "train"), "spidertron": e(3, 3, "train"),
  // Logistique & infrastructures
  "roboport": e(4, 4, "logistics"), "radar": e(3, 3, "logistics"), "beacon": e(3, 3, "logistics"), "rocket-silo": e(9, 9, "logistics"),
  "cargo-landing-pad": e(8, 8, "space", SA), "space-platform-hub": e(8, 8, "space", SA), "cargo-bay": e(4, 4, "space", SA),
  "thruster": e(4, 5, "space", SA), "small-lamp": e(1, 1, "circuit"),
  // Circuits
  "constant-combinator": e(1, 1, "circuit"), "arithmetic-combinator": e(1, 2, "circuit"), "decider-combinator": e(1, 2, "circuit"),
  "selector-combinator": e(1, 2, "circuit"), "programmable-speaker": e(1, 1, "circuit"), "display-panel": e(1, 1, "circuit"),
  // Défense
  "stone-wall": e(1, 1, "combat"), "gate": e(1, 1, "combat"), "land-mine": e(1, 1, "combat"),
  "gun-turret": e(2, 2, "combat"), "laser-turret": e(2, 2, "combat"), "flamethrower-turret": e(2, 3, "combat"),
  "artillery-turret": e(3, 3, "combat"), "railgun-turret": e(4, 4, "combat", SA), "tesla-turret": e(3, 3, "combat", SA),
  "rocket-turret": e(3, 3, "combat", SA),
};

/** Renommages 1.1 -> 2.0. */
export const LEGACY_RENAMES: Record<string, string> = {
  "logistic-chest-active-provider": "active-provider-chest",
  "logistic-chest-passive-provider": "passive-provider-chest",
  "logistic-chest-storage": "storage-chest",
  "logistic-chest-buffer": "buffer-chest",
  "logistic-chest-requester": "requester-chest",
  "filter-inserter": "fast-inserter",
  "stack-inserter": "bulk-inserter",
  "stack-filter-inserter": "bulk-inserter",
  "curved-rail": "legacy-curved-rail",
  "straight-rail": "legacy-straight-rail",
};

export const TILE_COLORS: Record<string, string> = {
  "stone-path": "#6b6458", "concrete": "#77797a", "hazard-concrete-left": "#8a7a3a", "hazard-concrete-right": "#8a7a3a",
  "refined-concrete": "#5d6266", "refined-hazard-concrete-left": "#7a6a2a", "refined-hazard-concrete-right": "#7a6a2a",
  "landfill": "#6d5a3e", "space-platform-foundation": "#4a5160", "foundation": "#3f4652", "artificial-yumako-soil": "#4d6b2f",
  "artificial-jellynut-soil": "#2f5a6b", "overgrowth-yumako-soil": "#5b7c34", "overgrowth-jellynut-soil": "#346b7c",
  "ice-platform": "#9fc4d6",
};

/** Préfixes de noms connus -> mod. Sert de repli quand le dump d'assets ne connaît pas l'entité. */
const MOD_PREFIXES: [RegExp, string][] = [
  [/^bob/, "Bob's mods"], [/^angels?-/, "Angel's mods"], [/^kr-/, "Krastorio 2"], [/^se-/, "Space Exploration"],
  [/^(py|pypp)/, "Pyanodons"], [/^nullius-/, "Nullius"], [/^ee-/, "Editor Extensions"], [/^aai-/, "AAI"],
  [/^ltn-|^logistic-train-stop/, "LTN"], [/miniloader/, "Miniloader"], [/^factory-/, "Factorissimo"],
  [/^ir-|^ir3-/, "Industrial Revolution"], [/^5d-/, "5Dim's"], [/cybersyn/, "Project Cybersyn"], [/^bz/, "Brevven's mods"],
  [/^248k-|^fi_|^fu_|^el_|^gr_/, "248k"], [/^sp-/, "Space Age+"], [/^dect-/, "Dectorio"], [/^ab-|^aop-/, "Age of Production"],
  [/^vtk-/, "VortiK's"], [/^fp-|^factory-planner/, "Factory Planner"], [/^yi-|^y-/, "Yuoki"], [/^maraxsis-/, "Maraxsis"],
  [/^cerys-/, "Cerys"], [/^muluna-/, "Muluna"], [/^tenebris-/, "Tenebris"], [/^moshine-/, "Moshine"],
];

export function guessMod(name: string): string | null {
  for (const [re, mod] of MOD_PREFIXES) if (re.test(name)) return mod;
  const head = name.split(/[-_]/)[0];
  return head && head.length >= 2 && head.length <= 12 && name.includes("-") ? `${head}?` : null;
}

export const ENTITY_ABBREVIATIONS: Record<string, string> = {
  "assembling-machine-1": "AM1", "assembling-machine-2": "AM2", "assembling-machine-3": "AM3", "chemical-plant": "Chem",
  "oil-refinery": "Refinery", "electric-mining-drill": "Drill", "big-mining-drill": "Big drill", "electric-furnace": "Furn",
  "stone-furnace": "Furn", "steel-furnace": "Furn", "beacon": "Bcn", "roboport": "Robo", "substation": "Sub",
  "medium-electric-pole": "", "small-electric-pole": "", "big-electric-pole": "", "solar-panel": "Solar",
  "accumulator": "Acc", "storage-tank": "Tank", "electromagnetic-plant": "EM", "cryogenic-plant": "Cryo",
  "foundry": "Foundry", "recycler": "Recy", "biochamber": "Bio", "lab": "Lab", "biolab": "Biolab",
  "nuclear-reactor": "Reactor", "heat-exchanger": "HX", "steam-turbine": "Turbine", "steam-engine": "Engine",
  "rocket-silo": "Silo", "train-stop": "Stop", "radar": "Radar", "pumpjack": "Jack", "centrifuge": "Cent",
  "arithmetic-combinator": "A", "decider-combinator": "D", "constant-combinator": "C", "selector-combinator": "S",
};
