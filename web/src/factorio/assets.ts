// Assets optionnels extraits du jeu par tools/factorio-exporter (servis sous /factorio-assets/).

export interface PrototypeInfo {
  type: string;
  /** mod qui a créé le prototype */
  mod: string;
  w: number;
  h: number;
  /** chemin relatif d'une icône (sous /factorio-assets/) */
  icon?: string;
}

export interface AssetManifest {
  factorioVersion: string;
  generatedAt: string;
  mods: Record<string, string>;
  prototypes: Record<string, PrototypeInfo>;
  /** icônes des items, fluides, signaux, recettes... : "type/name" ou "name" -> chemin */
  icons: Record<string, string>;
}

let manifestPromise: Promise<AssetManifest | null> | null = null;

export function loadManifest(enabled: boolean): Promise<AssetManifest | null> {
  if (!enabled) return Promise.resolve(null);
  manifestPromise ??= fetch("/factorio-assets/manifest.json")
    .then((r) => (r.ok ? (r.json() as Promise<AssetManifest>) : null))
    .catch(() => null);
  return manifestPromise;
}

const images = new Map<string, HTMLImageElement | null>();

/** Charge une image (null tant qu'elle n'est pas prête ; [onLoad] est appelé quand elle l'est). */
export function image(path: string, onLoad: () => void): HTMLImageElement | null {
  if (images.has(path)) {
    const img = images.get(path)!;
    return img && img.complete && img.naturalWidth > 0 ? img : null;
  }
  const img = new Image();
  img.decoding = "async";
  img.onload = onLoad;
  img.onerror = () => images.set(path, null);
  img.src = "/factorio-assets/" + path;
  images.set(path, img);
  return null;
}

export function iconPath(manifest: AssetManifest | null, name: string, type?: string): string | undefined {
  if (!manifest) return undefined;
  if (type) {
    const t = type === "virtual" ? "virtual-signal" : type;
    const p = manifest.icons[`${t}/${name}`];
    if (p) return p;
  }
  return manifest.prototypes[name]?.icon ?? manifest.icons[`item/${name}`] ?? manifest.icons[name];
}
