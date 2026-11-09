import { CATEGORY_COLORS, ENTITY_ABBREVIATIONS, TILE_COLORS } from "./data";
import { iconPath, image, type AssetManifest } from "./assets";
import type { BlueprintModel, RenderEntity } from "./model";

export interface View { scale: number; ox: number; oy: number }

export interface RenderOptions {
  grid: boolean;
  labels: boolean;
  wires: boolean;
  icons: boolean;
  manifest: AssetManifest | null;
  hover: number | null;
  dark: boolean;
  /** appelé quand une image d'asset finit de charger (pour redessiner) */
  onAsset: () => void;
}

const WIRE_COLORS: Record<number, string> = { 1: "#ef4444", 2: "#22c55e", 3: "#ef4444", 4: "#22c55e", 5: "#d97706", 6: "#d97706" };

// Qualités : couleur et nombre de pastilles, comme les icônes du jeu.
const QUALITIES: Record<string, { color: string; pips: number }> = {
  normal: { color: "#b3b3b3", pips: 1 },
  uncommon: { color: "#3ee55a", pips: 2 },
  rare: { color: "#2a96ff", pips: 3 },
  epic: { color: "#bf00ff", pips: 4 },
  legendary: { color: "#ff9a00", pips: 5 },
};

const BELT_TIERS: [RegExp, string][] = [
  [/^turbo-/, "#7ab648"], [/^express-/, "#4a90d9"], [/^fast-/, "#d9473f"], [/^(transport-belt|underground-belt|splitter|loader|lane-splitter)$/, "#e3b23c"],
];
const INSERTER_COLORS: Record<string, string> = {
  "burner-inserter": "#8a6a4a", "inserter": "#e3b23c", "long-handed-inserter": "#d9473f", "fast-inserter": "#4a90d9",
  "bulk-inserter": "#7ab648", "stack-inserter": "#d8d8d8", "filter-inserter": "#9b59b6", "stack-filter-inserter": "#d8d8d8",
};

function beltColor(name: string): string {
  for (const [re, c] of BELT_TIERS) if (re.test(name)) return c;
  return "#b8a06a";
}

// ------------------------------------------------------------------ classification

type Kind = "belt" | "underground" | "splitter" | "loader" | "inserter" | "pipe" | "pipe-to-ground" | "heat-pipe" | "wall" | "other";

function kindOf(e: RenderEntity, manifest: AssetManifest | null): Kind {
  const t = manifest?.prototypes[e.name]?.type ?? "";
  const n = e.name;
  if (t === "transport-belt" || /transport-belt$/.test(n) || n === "lane-splitter" || t === "lane-splitter" || t === "linked-belt") return "belt";
  if (t === "underground-belt" || /underground-belt$/.test(n)) return "underground";
  if (t === "splitter" || /splitter$/.test(n)) return "splitter";
  if (t === "loader" || t === "loader-1x1" || /(^|-)loader$/.test(n)) return "loader";
  if (t === "inserter" || e.cat === "inserter") return "inserter";
  if (t === "pipe-to-ground" || /pipe-to-ground$/.test(n)) return "pipe-to-ground";
  if (t === "heat-pipe" || n === "heat-pipe") return "heat-pipe";
  if (t === "pipe" || t === "infinity-pipe" || n === "pipe" || n === "infinity-pipe") return "pipe";
  if (t === "wall" || t === "gate" || n === "stone-wall" || n === "gate") return "wall";
  return "other";
}

/** Vecteur unitaire d'une direction cardinale (0, 4, 8, 12 sur 16). */
function cardinal(direction: number): [number, number] {
  switch (((Math.round(direction / 4) % 4) + 4) % 4) {
    case 0: return [0, -1];
    case 1: return [1, 0];
    case 2: return [0, 1];
    default: return [-1, 0];
  }
}

const key = (x: number, y: number) => `${x},${y}`;

interface Layout {
  kinds: Map<number, Kind>;
  /** tuile -> entité */
  grid: Map<string, RenderEntity>;
  /** forme des convoyeurs : 0 droit, -1 virage depuis la gauche, 1 virage depuis la droite */
  curve: Map<number, -1 | 0 | 1>;
  /** connexions des tuyaux / caloducs / murs : bits N=1 E=2 S=4 W=8 */
  links: Map<number, number>;
}

const layouts = new WeakMap<BlueprintModel, Map<AssetManifest | null, Layout>>();

function tilesOf(e: RenderEntity): [number, number][] {
  const out: [number, number][] = [];
  const x0 = Math.round(e.x - e.w / 2), y0 = Math.round(e.y - e.h / 2);
  for (let dx = 0; dx < e.w; dx++) for (let dy = 0; dy < e.h; dy++) out.push([x0 + dx, y0 + dy]);
  return out;
}

function layoutOf(model: BlueprintModel, manifest: AssetManifest | null): Layout {
  let perManifest = layouts.get(model);
  if (!perManifest) layouts.set(model, (perManifest = new Map()));
  const cached = perManifest.get(manifest);
  if (cached) return cached;

  const kinds = new Map<number, Kind>();
  const grid = new Map<string, RenderEntity>();
  for (const e of model.entities) {
    kinds.set(e.number, kindOf(e, manifest));
    if (e.cat !== "rail" && e.cat !== "train") for (const [x, y] of tilesOf(e)) grid.set(key(x, y), e);
  }
  const at = (x: number, y: number) => grid.get(key(x, y));

  // Tuiles vers lesquelles une entité de convoyage envoie des objets.
  const outputsTo = (n: RenderEntity, tx: number, ty: number): boolean => {
    const k = kinds.get(n.number);
    const [vx, vy] = cardinal(n.direction);
    if (k === "belt" || (k === "underground" && n.type === "output") || k === "splitter" || (k === "loader" && n.type !== "input")) {
      return tilesOf(n).some(([x, y]) => x + vx === tx && y + vy === ty);
    }
    return false;
  };

  const curve = new Map<number, -1 | 0 | 1>();
  const links = new Map<number, number>();
  for (const e of model.entities) {
    const k = kinds.get(e.number)!;
    const tx = Math.floor(e.x), ty = Math.floor(e.y);
    if (k === "belt") {
      const [vx, vy] = cardinal(e.direction);
      const back = at(tx - vx, ty - vy);
      if (back && outputsTo(back, tx, ty)) { curve.set(e.number, 0); continue; }
      // gauche / droite relatives au sens du tapis
      const left = at(tx + vy, ty - vx), right = at(tx - vy, ty + vx);
      const fromLeft = Boolean(left && outputsTo(left, tx, ty));
      const fromRight = Boolean(right && outputsTo(right, tx, ty));
      curve.set(e.number, fromLeft === fromRight ? 0 : fromLeft ? -1 : 1);
    } else if (k === "pipe" || k === "heat-pipe" || k === "wall") {
      let bits = 0;
      [[0, -1, 1], [1, 0, 2], [0, 1, 4], [-1, 0, 8]].forEach(([dx, dy, bit]) => {
        const n = at(tx + dx, ty + dy);
        if (n && connects(k, n, kinds.get(n.number)!, -dx, -dy)) bits |= bit;
      });
      links.set(e.number, bits);
    }
  }
  const layout = { kinds, grid, curve, links };
  perManifest.set(manifest, layout);
  return layout;
}

/** Le voisin [n] accepte-t-il une connexion venant du côté (dx, dy) (vu depuis n) ? */
function connects(from: Kind, n: RenderEntity, nk: Kind, dx: number, dy: number): boolean {
  if (from === "wall") return nk === "wall";
  if (from === "heat-pipe") return nk === "heat-pipe" || /reactor|heat-exchanger|heating-tower|heat-interface/.test(n.name);
  if (nk === "pipe") return true;
  if (nk === "pipe-to-ground") { const [vx, vy] = cardinal(n.direction); return vx === dx && vy === dy; }
  if (nk === "heat-pipe" || nk === "belt" || nk === "underground" || nk === "splitter" || nk === "inserter" || nk === "wall" || nk === "loader") return false;
  if (n.name === "pump" || n.name === "offshore-pump") { const [vx, vy] = cardinal(n.direction); return vx === dx || vy === dy ? (vx !== 0) === (dx !== 0) : false; }
  // Machines à fluides : approximation (on relie dès qu'elles sont adjacentes).
  return ["pipe", "assembler", "power", "mining", "furnace", "agri", "space"].includes(n.cat) && !/electric-pole|substation|beacon|radar|roboport|lab$/.test(n.name);
}

// ------------------------------------------------------------------ rendu

export function fitView(model: BlueprintModel, width: number, height: number, padding = 16): View {
  const { minX, minY, maxX, maxY } = model.bounds;
  const bw = Math.max(1, maxX - minX), bh = Math.max(1, maxY - minY);
  const scale = Math.max(1, Math.min(64, Math.min((width - padding * 2) / bw, (height - padding * 2) / bh)));
  return { scale, ox: width / 2 - ((minX + maxX) / 2) * scale, oy: height / 2 - ((minY + maxY) / 2) * scale };
}

export function render(ctx: CanvasRenderingContext2D, model: BlueprintModel, view: View, w: number, h: number, o: RenderOptions) {
  const { scale, ox, oy } = view;
  const X = (x: number) => x * scale + ox;
  const Y = (y: number) => y * scale + oy;
  const layout = layoutOf(model, o.manifest);

  ctx.save();
  ctx.fillStyle = o.dark ? "#17181a" : "#26272b";
  ctx.fillRect(0, 0, w, h);

  if (o.grid && scale >= 6) {
    const x0 = Math.floor(-ox / scale), x1 = Math.ceil((w - ox) / scale);
    const y0 = Math.floor(-oy / scale), y1 = Math.ceil((h - oy) / scale);
    ctx.lineWidth = 1;
    for (let x = x0; x <= x1; x++) {
      ctx.strokeStyle = x % 32 === 0 ? "rgba(255,255,255,0.12)" : "rgba(255,255,255,0.04)";
      ctx.beginPath(); ctx.moveTo(Math.round(X(x)) + 0.5, 0); ctx.lineTo(Math.round(X(x)) + 0.5, h); ctx.stroke();
    }
    for (let y = y0; y <= y1; y++) {
      ctx.strokeStyle = y % 32 === 0 ? "rgba(255,255,255,0.12)" : "rgba(255,255,255,0.04)";
      ctx.beginPath(); ctx.moveTo(0, Math.round(Y(y)) + 0.5); ctx.lineTo(w, Math.round(Y(y)) + 0.5); ctx.stroke();
    }
  }

  for (const t of model.tiles) {
    ctx.fillStyle = TILE_COLORS[t.name] ?? "#555b66";
    ctx.fillRect(X(t.x), Y(t.y), Math.ceil(scale), Math.ceil(scale));
  }

  // Hors écran : on ne dessine pas (grands plans).
  const visible = (e: RenderEntity) => {
    const px = X(e.x), py = Y(e.y), r = (Math.max(e.w, e.h) * scale) / 2 + 2;
    return px + r >= 0 && px - r <= w && py + r >= 0 && py - r <= h;
  };
  const ground: RenderEntity[] = [], rest: RenderEntity[] = [];
  for (const e of model.entities) {
    if (!visible(e)) continue;
    const k = layout.kinds.get(e.number)!;
    (k === "belt" || k === "underground" || k === "splitter" || k === "loader" || k === "pipe" || k === "pipe-to-ground" || k === "heat-pipe" || k === "wall" ? ground : rest).push(e);
  }
  rest.sort((a, b) => b.w * b.h - a.w * a.h);
  for (const e of ground) drawEntity(ctx, e, layout, X, Y, scale, o);
  for (const e of rest) drawEntity(ctx, e, layout, X, Y, scale, o);

  if (o.wires && model.wires.length) {
    const byNumber = new Map(model.entities.map((e) => [e.number, e]));
    ctx.lineWidth = Math.max(1, scale / 16);
    ctx.globalAlpha = 0.85;
    for (const wire of model.wires) {
      const a = byNumber.get(wire.from), b = byNumber.get(wire.to);
      if (!a || !b) continue;
      ctx.strokeStyle = WIRE_COLORS[wire.fromConnector] ?? "#eab308";
      const off = wire.fromConnector % 2 === 0 ? 0.12 : -0.12;
      ctx.beginPath();
      ctx.moveTo(X(a.x + off), Y(a.y + off));
      ctx.lineTo(X(b.x + off), Y(b.y + off));
      ctx.stroke();
    }
    ctx.globalAlpha = 1;
  }

  if (o.hover != null) {
    const e = model.entities.find((x) => x.number === o.hover);
    if (e) {
      ctx.strokeStyle = "#fbbf24";
      ctx.lineWidth = 2;
      ctx.strokeRect(X(e.x - e.w / 2), Y(e.y - e.h / 2), e.w * scale, e.h * scale);
    }
  }
  ctx.restore();
}

/** Repère local d'une tuile : origine au centre, unité = 1 tuile, « haut » = direction donnée. */
function local(ctx: CanvasRenderingContext2D, cx: number, cy: number, scale: number, angle: number, fn: () => void) {
  ctx.save();
  ctx.translate(cx, cy);
  ctx.rotate(angle);
  ctx.scale(scale, scale);
  fn();
  ctx.restore();
}

const angleOf = (direction: number) => (direction * Math.PI) / 8;

function chevron(ctx: CanvasRenderingContext2D, y: number, color: string) {
  ctx.strokeStyle = color;
  ctx.lineWidth = 0.09;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.beginPath();
  ctx.moveTo(-0.22, y + 0.11);
  ctx.lineTo(0, y - 0.09);
  ctx.lineTo(0.22, y + 0.11);
  ctx.stroke();
}

/** Tronçon droit de tapis, de y0 (amont) à y1 (aval), dans le repère local (flux vers le haut). */
function straightBelt(ctx: CanvasRenderingContext2D, color: string, y0 = 0.5, y1 = -0.5, x = 0, detail = true) {
  ctx.fillStyle = "#2e2c29";
  ctx.fillRect(x - 0.46, y1, 0.92, y0 - y1);
  ctx.fillStyle = color;
  ctx.fillRect(x - 0.46, y1, 0.08, y0 - y1);
  ctx.fillRect(x + 0.38, y1, 0.08, y0 - y1);
  if (!detail) return;
  ctx.save();
  ctx.translate(x, 0);
  for (let y = y0 - 0.25; y > y1; y -= 0.5) chevron(ctx, y, color);
  ctx.restore();
}

function curvedBelt(ctx: CanvasRenderingContext2D, color: string, side: -1 | 1, detail: boolean) {
  // Entrée par la gauche (side -1) : arc centré sur le coin haut-gauche ; la droite est le miroir.
  ctx.save();
  if (side === 1) ctx.scale(-1, 1);
  const cx = -0.5, cy = -0.5;
  const ring = (r0: number, r1: number, fill: string) => {
    ctx.fillStyle = fill;
    ctx.beginPath();
    ctx.arc(cx, cy, r1, 0, Math.PI / 2);
    ctx.arc(cx, cy, r0, Math.PI / 2, 0, true);
    ctx.closePath();
    ctx.fill();
  };
  ring(0.04, 0.96, "#2e2c29");
  ring(0.04, 0.12, color);
  ring(0.88, 0.96, color);
  if (detail) {
    for (const a of [Math.PI / 8, (3 * Math.PI) / 8]) {
      ctx.save();
      ctx.translate(cx + 0.5 * Math.cos(a), cy + 0.5 * Math.sin(a));
      ctx.rotate(a - Math.PI / 2 + Math.PI); // tangente orientée vers la sortie (haut)
      chevron(ctx, 0, color);
      ctx.restore();
    }
  }
  ctx.restore();
}

function tunnel(ctx: CanvasRenderingContext2D, color: string, y0: number, y1: number) {
  ctx.fillStyle = "#1a1917";
  ctx.fillRect(-0.46, Math.min(y0, y1), 0.92, Math.abs(y1 - y0));
  ctx.strokeStyle = color;
  ctx.lineWidth = 0.1;
  ctx.strokeRect(-0.41, Math.min(y0, y1) + 0.05, 0.82, Math.abs(y1 - y0) - 0.1);
}

function drawEntity(
  ctx: CanvasRenderingContext2D, e: RenderEntity, layout: Layout, X: (x: number) => number, Y: (y: number) => number, scale: number, o: RenderOptions,
) {
  const kind = layout.kinds.get(e.number)!;
  const px = X(e.x - e.w / 2), py = Y(e.y - e.h / 2);
  const pw = e.w * scale, ph = e.h * scale;
  const cx = X(e.x), cy = Y(e.y);
  const detail = scale >= 7;
  const angle = angleOf(e.direction);

  switch (kind) {
    case "belt": {
      const color = beltColor(e.name);
      if (scale < 3) { ctx.fillStyle = color; ctx.fillRect(px, py, pw, ph); break; }
      const c = layout.curve.get(e.number) ?? 0;
      local(ctx, cx, cy, scale, angle, () => (c === 0 ? straightBelt(ctx, color, 0.5, -0.5, 0, detail) : curvedBelt(ctx, color, c, detail)));
      break;
    }
    case "underground": {
      const color = beltColor(e.name.replace("underground-belt", "transport-belt"));
      local(ctx, cx, cy, scale, angle, () => {
        if (e.type === "input") { straightBelt(ctx, color, 0.5, 0, 0, detail); tunnel(ctx, color, 0, -0.46); }
        else { tunnel(ctx, color, 0.46, 0); straightBelt(ctx, color, 0, -0.5, 0, detail); }
      });
      break;
    }
    case "splitter": {
      const color = beltColor(e.name.replace("splitter", "transport-belt"));
      local(ctx, cx, cy, scale, angle, () => {
        straightBelt(ctx, color, 0.5, -0.5, -0.5, detail);
        straightBelt(ctx, color, 0.5, -0.5, 0.5, detail);
        ctx.fillStyle = "#3a3733";
        ctx.fillRect(-0.95, -0.17, 1.9, 0.34);
        ctx.fillStyle = color;
        ctx.fillRect(-0.9, -0.08, 1.8, 0.16);
      });
      break;
    }
    case "loader": {
      const color = beltColor(e.name.replace("loader", "transport-belt"));
      local(ctx, cx, cy, scale, angle, () => {
        const half = e.w * e.h > 1 ? 1 : 0.5;
        straightBelt(ctx, color, half, -half, 0, detail);
        ctx.fillStyle = "#3a3733";
        ctx.fillRect(-0.48, e.type === "input" ? -half : 0, 0.96, half);
      });
      break;
    }
    case "inserter": {
      // Un bras « regarde » vers sa prise : il dépose du côté opposé à sa direction.
      const color = INSERTER_COLORS[e.name] ?? "#b0b0b0";
      const iconFile = o.icons ? iconPath(o.manifest, e.name, "entity") : undefined;
      const img = iconFile ? image(iconFile, o.onAsset) : null;
      local(ctx, cx, cy, scale, angle, () => {
        if (img) {
          ctx.save(); ctx.rotate(Math.PI); ctx.drawImage(img, -0.42, -0.42, 0.84, 0.84); ctx.restore();
        } else {
          ctx.fillStyle = "#3a3733";
          ctx.fillRect(-0.3, -0.12, 0.6, 0.42);
          ctx.fillStyle = color;
          ctx.fillRect(-0.22, -0.05, 0.44, 0.28);
        }
        if (detail) {
          ctx.strokeStyle = img ? "rgba(255,255,255,0.85)" : color;
          ctx.lineWidth = 0.09;
          ctx.lineCap = "round";
          ctx.beginPath();
          ctx.moveTo(0, -0.42); ctx.lineTo(0, 0.4);
          ctx.moveTo(-0.16, 0.24); ctx.lineTo(0, 0.42); ctx.lineTo(0.16, 0.24);
          ctx.stroke();
        }
      });
      break;
    }
    case "pipe":
    case "heat-pipe":
    case "wall": {
      const bits = layout.links.get(e.number) ?? 0;
      const color = kind === "pipe" ? "#5f8aa3" : kind === "heat-pipe" ? "#c96a3a" : e.name === "gate" ? "#8f8f8f" : "#a8a29a";
      const t = kind === "wall" ? 0.5 : 0.34;
      local(ctx, cx, cy, scale, 0, () => {
        ctx.fillStyle = color;
        ctx.fillRect(-t / 2, -t / 2, t, t);
        if (bits & 1) ctx.fillRect(-t / 2, -0.5, t, 0.5);
        if (bits & 2) ctx.fillRect(0, -t / 2, 0.5, t);
        if (bits & 4) ctx.fillRect(-t / 2, 0, t, 0.5);
        if (bits & 8) ctx.fillRect(-0.5, -t / 2, 0.5, t);
        if (detail && kind !== "wall") {
          ctx.fillStyle = "rgba(255,255,255,0.18)";
          ctx.fillRect(-t / 2, -t / 2, t, t * 0.3);
        }
      });
      break;
    }
    case "pipe-to-ground": {
      local(ctx, cx, cy, scale, angle, () => {
        ctx.fillStyle = "#5f8aa3";
        ctx.fillRect(-0.17, -0.5, 0.34, 0.6);
        ctx.fillStyle = "#1a1917";
        ctx.beginPath(); ctx.arc(0, 0.18, 0.3, 0, Math.PI * 2); ctx.fill();
        ctx.strokeStyle = "#5f8aa3"; ctx.lineWidth = 0.08; ctx.stroke();
      });
      break;
    }
    default:
      drawBlock(ctx, e, px, py, pw, ph, cx, cy, scale, angle, o);
  }

  if (e.quality && scale >= 8) drawQuality(ctx, e.quality, px, py + ph, Math.min(pw, ph), o);
}

function drawBlock(
  ctx: CanvasRenderingContext2D, e: RenderEntity, px: number, py: number, pw: number, ph: number, cx: number, cy: number,
  scale: number, angle: number, o: RenderOptions,
) {
  const inset = Math.min(2, scale * 0.06);
  const color = CATEGORY_COLORS[e.cat];
  const iconFile = o.icons ? iconPath(o.manifest, e.name, "entity") : undefined;
  const img = iconFile ? image(iconFile, o.onAsset) : null;

  if (e.cat === "rail") {
    ctx.fillStyle = "rgba(160,160,160,0.35)";
    ctx.fillRect(px + inset, py + inset, pw - inset * 2, ph - inset * 2);
  } else {
    ctx.fillStyle = img ? "rgba(40,40,44,0.9)" : e.known ? color : "#4b5563";
    ctx.fillRect(px + inset, py + inset, pw - inset * 2, ph - inset * 2);
    ctx.strokeStyle = e.known ? "rgba(0,0,0,0.45)" : "#e5e7eb";
    ctx.lineWidth = Math.max(1, scale / 24);
    if (!e.known) ctx.setLineDash([Math.max(2, scale / 6), Math.max(2, scale / 8)]);
    ctx.strokeRect(px + inset, py + inset, pw - inset * 2, ph - inset * 2);
    ctx.setLineDash([]);
  }

  // Icône orientée selon la direction de l'entité.
  if (img) {
    const s = Math.min(pw, ph) * (e.w * e.h > 1 ? 0.72 : 0.86);
    ctx.save();
    ctx.translate(cx, cy);
    if (e.direction) ctx.rotate(angle);
    ctx.drawImage(img, -s / 2, -s / 2, s, s);
    ctx.restore();
  } else if (e.direction && scale >= 6 && e.cat !== "rail") {
    // Sans icône : flèche indiquant l'orientation.
    local(ctx, cx, cy, Math.min(pw, ph), angle, () => {
      ctx.strokeStyle = "rgba(15,15,15,0.7)";
      ctx.lineWidth = 0.08;
      ctx.lineCap = "round";
      ctx.beginPath();
      ctx.moveTo(0, 0.25); ctx.lineTo(0, -0.3);
      ctx.moveTo(-0.15, -0.15); ctx.lineTo(0, -0.3); ctx.lineTo(0.15, -0.15);
      ctx.stroke();
    });
  }

  if (e.recipe && Math.min(pw, ph) >= 24) {
    const recipeIcon = o.icons ? iconPath(o.manifest, e.recipe, "recipe") : undefined;
    const rImg = recipeIcon ? image(recipeIcon, o.onAsset) : null;
    if (rImg) {
      const s = Math.min(pw, ph) * 0.42;
      ctx.fillStyle = "rgba(0,0,0,0.6)";
      ctx.beginPath(); ctx.arc(cx, cy, s * 0.62, 0, Math.PI * 2); ctx.fill();
      ctx.drawImage(rImg, cx - s / 2, cy - s / 2, s, s);
    } else if (!img && o.labels && scale >= 12) {
      label(ctx, e.recipe, cx, cy + Math.max(8, scale * 0.35), pw - 4, Math.max(8, scale * 0.28), "#f4f4f5");
    }
  }

  if (o.labels && !img) {
    if (!e.known && scale >= 10) {
      const fs = Math.max(7, Math.min(14, scale * 0.24));
      label(ctx, e.name, cx, cy - (e.mod ? fs * 0.45 : 0), pw - 2, fs, "#fafafa");
      if (e.mod) label(ctx, e.mod, cx, cy + fs * 0.8, pw - 2, fs * 0.85, "#d4d4d8");
    } else if (e.known && e.w * e.h >= 4 && scale >= 10) {
      const ab = ENTITY_ABBREVIATIONS[e.name] ?? e.name.split("-").map((s) => s[0]).join("").toUpperCase();
      if (ab) label(ctx, ab, cx, cy - (e.recipe ? scale * 0.3 : 0), pw - 4, Math.max(8, Math.min(16, scale * 0.42)), "rgba(255,255,255,0.92)");
    }
  }
}

/** Badge de qualité (coin bas-gauche) : icône du jeu si exportée, sinon 1 à 5 pastilles. */
function drawQuality(ctx: CanvasRenderingContext2D, quality: string, x: number, bottom: number, size: number, o: RenderOptions) {
  const s = Math.max(8, Math.min(size * 0.4, 22));
  const file = o.icons ? iconPath(o.manifest, quality, "quality") : undefined;
  const img = file ? image(file, o.onAsset) : null;
  if (img) { ctx.drawImage(img, x + 1, bottom - s - 1, s, s); return; }
  const q = QUALITIES[quality] ?? { color: "#9ca3af", pips: 1 };
  const r = s / 4.2;
  // Positions des pastilles comme dans les icônes du jeu (grille 2x2 + centre).
  const c0 = x + 1 + r * 1.1, c1 = c0 + r * 1.9, r0 = bottom - 1 - r * 1.1, r1 = r0 - r * 1.9;
  const layoutPips: [number, number][][] = [
    [[c0, r0]],
    [[c0, r1], [c0, r0]],
    [[c0, r1], [c0, r0], [c1, r0]],
    [[c0, r1], [c1, r1], [c0, r0], [c1, r0]],
    [[c0, r1], [c1, r1], [c0, r0], [c1, r0], [(c0 + c1) / 2, (r0 + r1) / 2]],
  ];
  for (const [px, py] of layoutPips[Math.min(5, Math.max(1, q.pips)) - 1]) {
    ctx.beginPath();
    ctx.arc(px, py, r, 0, Math.PI * 2);
    ctx.fillStyle = q.color;
    ctx.fill();
    ctx.lineWidth = Math.max(1, r * 0.35);
    ctx.strokeStyle = "#000";
    ctx.stroke();
  }
}

function label(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, maxWidth: number, size: number, color: string) {
  ctx.font = `600 ${size}px "JetBrains Mono", ui-monospace, monospace`;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  let t = text;
  while (t.length > 3 && ctx.measureText(t).width > maxWidth) t = t.slice(0, -2);
  if (t !== text) t = t.slice(0, -1) + "…";
  ctx.lineWidth = 3;
  ctx.strokeStyle = "rgba(0,0,0,0.55)";
  ctx.strokeText(t, x, y);
  ctx.fillStyle = color;
  ctx.fillText(t, x, y);
}

export function entityAt(model: BlueprintModel, view: View, sx: number, sy: number): RenderEntity | null {
  const wx = (sx - view.ox) / view.scale, wy = (sy - view.oy) / view.scale;
  let best: RenderEntity | null = null;
  for (const e of model.entities) {
    if (Math.abs(wx - e.x) <= e.w / 2 && Math.abs(wy - e.y) <= e.h / 2) {
      if (!best || e.w * e.h < best.w * best.h) best = e;
    }
  }
  return best;
}

/** Rend le plan dans un PNG 1200x630 (miniature OpenGraph). */
export async function renderThumbnail(model: BlueprintModel, manifest: AssetManifest | null): Promise<Blob | null> {
  const w = 1200, h = 630;
  const canvas = document.createElement("canvas");
  canvas.width = w; canvas.height = h;
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;
  const opts: RenderOptions = { grid: false, labels: true, wires: false, icons: Boolean(manifest), manifest, hover: null, dark: true, onAsset: () => {} };
  const view = fitView(model, w, h, 20);
  render(ctx, model, view, w, h, opts);
  if (manifest) {
    await new Promise((r) => setTimeout(r, 800));
    render(ctx, model, view, w, h, opts);
  }
  return new Promise((resolve) => canvas.toBlob((b) => resolve(b), "image/png"));
}
