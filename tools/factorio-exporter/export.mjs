#!/usr/bin/env node
// Exporteur d'assets Factorio pour Texto (Node 18+, aucune dépendance).
//
// Lance le jeu installé localement en mode « dump » (sans ouvrir de fenêtre) avec les mods
// actuellement activés, puis produit un dossier prêt à être servi par Texto :
//
//   factorio-assets/
//     manifest.json          prototypes (taille, type, mod d'origine), icônes, liste des mods
//     icons/<type>/<nom>.png icônes rendues par le jeu
//
// Usage :
//   node tools/factorio-exporter/export.mjs [--factorio <exe>] [--data-dir <dossier>] [--out <dossier>] [--skip-dump]
//
// Les assets du jeu appartiennent à Wube Software : ne les publiez pas dans un dépôt public.
// Ils sont destinés à votre propre instance (copiés dans l'image Docker au build, ou montés en volume).
import { execFileSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { homedir, platform } from "node:os";
import { join, resolve } from "node:path";

const args = process.argv.slice(2);
const opt = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const flag = (name) => args.includes(name);

function firstExisting(paths) { return paths.find((p) => p && existsSync(p)); }

function findFactorio() {
  const explicit = opt("--factorio") ?? process.env.FACTORIO_BIN;
  if (explicit) return explicit;
  const home = homedir();
  const steamLibs = ["C:/Program Files (x86)/Steam", "C:/Program Files/Steam", "D:/SteamLibrary", "E:/SteamLibrary", "F:/SteamLibrary", "D:/Steam", "E:/Steam",
    join(home, ".steam/steam"), join(home, ".local/share/Steam"), join(home, "Library/Application Support/Steam")];
  // Bibliothèques Steam supplémentaires déclarées dans libraryfolders.vdf.
  for (const lib of [...steamLibs]) {
    const vdf = join(lib, "steamapps/libraryfolders.vdf");
    if (existsSync(vdf)) for (const m of readFileSync(vdf, "utf8").matchAll(/"path"\s+"([^"]+)"/g)) steamLibs.push(m[1].replace(/\\\\/g, "/"));
  }
  const rel = platform() === "win32" ? "steamapps/common/Factorio/bin/x64/factorio.exe"
    : platform() === "darwin" ? "steamapps/common/Factorio/factorio.app/Contents/MacOS/factorio"
    : "steamapps/common/Factorio/bin/x64/factorio";
  return firstExisting([
    ...steamLibs.map((l) => join(l, rel)),
    "C:/Program Files/Factorio/bin/x64/factorio.exe",
    join(home, "factorio/bin/x64/factorio"),
    "/Applications/factorio.app/Contents/MacOS/factorio",
  ]);
}

function findDataDir() {
  const explicit = opt("--data-dir") ?? process.env.FACTORIO_DATA;
  if (explicit) return explicit;
  const home = homedir();
  return firstExisting([
    process.env.APPDATA && join(process.env.APPDATA, "Factorio"),
    join(home, ".factorio"),
    join(home, "Library/Application Support/factorio"),
  ]);
}

const exe = findFactorio();
const dataDir = findDataDir();
const out = resolve(opt("--out") ?? "factorio-assets");
if (!dataDir) throw new Error("Dossier de données Factorio introuvable : utilisez --data-dir.");
const scriptOutput = join(dataDir, "script-output");

if (!flag("--skip-dump")) {
  if (!exe) throw new Error("Exécutable Factorio introuvable : utilisez --factorio <chemin>.");
  console.log(`Factorio : ${exe}`);
  console.log("Le jeu doit être fermé pendant l'export.");
  for (const mode of ["--dump-data", "--dump-icon-sprites"]) {
    console.log(`→ factorio ${mode} (peut prendre une minute)…`);
    // SteamAppId évite que la version Steam se relance via Steam (ce qui détacherait le processus).
    execFileSync(exe, [mode], { stdio: ["ignore", "ignore", "inherit"], env: { ...process.env, SteamAppId: "427520", SteamGameId: "427520" } });
    await waitForExit();
  }
}

/** Attend qu'aucun processus Factorio ne tourne encore (cas où Steam a quand même relancé le jeu). */
async function waitForExit() {
  const running = () => {
    try {
      const outp = platform() === "win32"
        ? execFileSync("tasklist", ["/FI", "IMAGENAME eq factorio.exe"], { encoding: "utf8" })
        : execFileSync("pgrep", ["-x", "factorio"], { encoding: "utf8" });
      return /factorio/i.test(outp) || /^\d+/m.test(outp);
    } catch {
      return false;
    }
  };
  for (let i = 0; i < 300 && running(); i++) await new Promise((r) => setTimeout(r, 1000));
}

const dumpFile = join(scriptOutput, "data-raw-dump.json");
if (!existsSync(dumpFile)) throw new Error(`${dumpFile} introuvable : le dump a échoué.`);
const raw = JSON.parse(readFileSync(dumpFile, "utf8"));

// ------------------------------------------------------------------ mods
const mods = {};
const modList = join(dataDir, "mods", "mod-list.json");
if (existsSync(modList)) {
  for (const m of JSON.parse(readFileSync(modList, "utf8")).mods) if (m.enabled) mods[m.name] = "";
}
const modsDir = join(dataDir, "mods");
if (existsSync(modsDir)) {
  for (const f of readdirSync(modsDir)) {
    const m = /^(.+)_(\d+\.\d+\.\d+)(\.zip)?$/.exec(f);
    if (m && m[1] in mods) mods[m[1]] = m[2];
  }
}

// ------------------------------------------------------------------ prototypes
// Types de prototypes plaçables (entités) dont on veut la taille.
const ENTITY_TYPES = new Set([
  "accumulator", "agricultural-tower", "ammo-turret", "arithmetic-combinator", "artillery-turret", "artillery-wagon",
  "assembling-machine", "asteroid-collector", "beacon", "boiler", "burner-generator", "car", "cargo-bay", "cargo-landing-pad",
  "cargo-wagon", "constant-combinator", "container", "curved-rail-a", "curved-rail-b", "decider-combinator", "display-panel",
  "electric-energy-interface", "electric-pole", "electric-turret", "elevated-curved-rail-a", "elevated-curved-rail-b",
  "elevated-half-diagonal-rail", "elevated-straight-rail", "fluid-turret", "fluid-wagon", "furnace", "fusion-generator",
  "fusion-reactor", "gate", "generator", "half-diagonal-rail", "heat-interface", "heat-pipe", "infinity-container",
  "infinity-pipe", "inserter", "lab", "lamp", "land-mine", "lane-splitter", "legacy-curved-rail", "legacy-straight-rail",
  "lightning-attractor", "linked-belt", "linked-container", "loader", "loader-1x1", "locomotive", "logistic-container",
  "mining-drill", "offshore-pump", "pipe", "pipe-to-ground", "power-switch", "programmable-speaker", "pump", "radar",
  "rail-chain-signal", "rail-ramp", "rail-signal", "rail-support", "reactor", "roboport", "rocket-silo", "selector-combinator",
  "simple-entity-with-force", "simple-entity-with-owner", "solar-panel", "space-platform-hub", "spider-vehicle", "splitter",
  "storage-tank", "straight-rail", "thruster", "train-stop", "transport-belt", "turret", "underground-belt", "wall",
]);

function modOf(proto) {
  const paths = [proto.icon, ...(proto.icons ?? []).map((i) => i.icon)].filter(Boolean);
  for (const p of paths) {
    const m = /^__([^_][^/]*?)__\//.exec(p);
    if (m && m[1] !== "core") return m[1];
  }
  return null;
}

function sizeOf(proto) {
  if (proto.tile_width && proto.tile_height) return [proto.tile_width, proto.tile_height];
  const box = proto.collision_box ?? proto.selection_box;
  if (Array.isArray(box) && box.length === 2) {
    const a = Array.isArray(box[0]) ? box[0] : [box[0].x, box[0].y];
    const b = Array.isArray(box[1]) ? box[1] : [box[1].x, box[1].y];
    return [Math.max(1, Math.ceil(b[0] - a[0] - 0.01)), Math.max(1, Math.ceil(b[1] - a[1] - 0.01))];
  }
  return [1, 1];
}

// ------------------------------------------------------------------ icônes
rmSync(join(out, "icons"), { recursive: true, force: true });
mkdirSync(join(out, "icons"), { recursive: true });
const icons = {};
let iconCount = 0;
for (const dir of readdirSync(scriptOutput)) {
  const full = join(scriptOutput, dir);
  if (!statSync(full).isDirectory() || ["factoricord-logs", "timelapse"].includes(dir)) continue;
  const pngs = readdirSync(full).filter((f) => f.endsWith(".png"));
  if (!pngs.length) continue;
  mkdirSync(join(out, "icons", dir), { recursive: true });
  for (const f of pngs) {
    const name = f.slice(0, -4);
    copyFileSync(join(full, f), join(out, "icons", dir, f));
    icons[`${dir}/${name}`] = `icons/${dir}/${encodeURIComponent(f)}`;
    iconCount++;
  }
}

const prototypes = {};
for (const [type, entries] of Object.entries(raw)) {
  if (!ENTITY_TYPES.has(type)) continue;
  for (const [name, proto] of Object.entries(entries)) {
    if (proto.hidden && !proto.placeable_by && !proto.minable) continue;
    const [w, h] = sizeOf(proto);
    const placeItem = proto.placeable_by?.item ?? proto.minable?.result ?? name;
    prototypes[name] = {
      type,
      mod: modOf(proto) ?? "base",
      w, h,
      icon: icons[`entity/${name}`] ?? icons[`item/${placeItem}`] ?? icons[`item/${name}`],
    };
  }
}

// Les recettes sans icône propre reprennent celle de leur produit principal.
for (const [name, recipe] of Object.entries(raw.recipe ?? {})) {
  if (icons[`recipe/${name}`]) continue;
  const main = recipe.main_product ?? recipe.results?.[0]?.name;
  const p = main && (icons[`item/${main}`] ?? icons[`fluid/${main}`]);
  if (p) icons[`recipe/${name}`] = p;
}

const log = existsSync(join(dataDir, "factorio-current.log")) ? readFileSync(join(dataDir, "factorio-current.log"), "utf8") : "";
const version = /Factorio (\d+\.\d+\.\d+)/.exec(log)?.[1] ?? "?";
const manifest = { factorioVersion: version, generatedAt: new Date().toISOString(), mods, prototypes, icons };
mkdirSync(out, { recursive: true });
writeFileSync(join(out, "manifest.json"), JSON.stringify(manifest));
console.log(`OK : ${Object.keys(prototypes).length} entités, ${iconCount} icônes, ${Object.keys(mods).length} mods → ${out}`);
