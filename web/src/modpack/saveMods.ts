// Lecture, entièrement dans le navigateur, de la liste des mods d'une sauvegarde Factorio.
// Rien n'est envoyé au serveur : seule la liste (nom + version) est gardée.
//
// Une sauvegarde est un .zip contenant <nom>/level-init.dat (1.1 et 2.x ; level.dat sur les très anciennes versions).
// Son en-tête binaire contient la liste des mods actifs : nom, version, CRC.
// Sources acceptées : un ou plusieurs .zip, un dossier de sauvegarde décompressé, le dossier « saves » entier,
// ou un dossier « mods » (mod-list.json + archives nom_version.zip).

export interface SaveMod { name: string; version: string }
export interface ParsedSave {
  /** Nom de la sauvegarde (fichier ou dossier). */
  name: string;
  gameVersion: string;
  mods: SaveMod[];
  /** Mods désactivés (mod-list.json uniquement). */
  disabled?: string[];
  source: "save" | "mod-list";
}

// ---------------------------------------------------------------- en-tête level-init.dat

class Reader {
  p = 0;
  readonly d: Uint8Array;
  constructor(d: Uint8Array) { this.d = d; }
  u8() { if (this.p >= this.d.length) throw new Error("eof"); return this.d[this.p++]; }
  u16() { return this.u8() | (this.u8() << 8); }
  u32() { return (this.u16() | (this.u16() << 16)) >>> 0; }
  ou16() { const b = this.u8(); return b === 0xff ? this.u16() : b; }
  ou32() { const b = this.u8(); return b === 0xff ? this.u32() : b; }
  str() {
    const n = this.ou32();
    if (n > 4096 || this.p + n > this.d.length) throw new Error("bad string");
    const s = new TextDecoder().decode(this.d.subarray(this.p, this.p + n));
    this.p += n;
    return s;
  }
}

const MOD_NAME = /^[A-Za-z0-9_\-. +]{1,100}$/;

function readMods(d: Uint8Array, at: number): SaveMod[] | null {
  try {
    const r = new Reader(d);
    r.p = at;
    const count = r.ou32();
    if (count < 1 || count > 5000) return null;
    const mods: SaveMod[] = [];
    for (let i = 0; i < count; i++) {
      const name = r.str();
      if (!MOD_NAME.test(name)) return null;
      const version = [r.ou16(), r.ou16(), r.ou16()].join(".");
      r.p += 4; // CRC
      mods.push({ name, version });
    }
    return mods.some((m) => m.name === "base") ? mods : null;
  } catch {
    return null;
  }
}

/** Liste des mods de l'en-tête d'un level-init.dat / level.dat (non compressé). */
export function parseLevelHeader(d: Uint8Array): { gameVersion: string; mods: SaveMod[] } | null {
  try {
    const r = new Reader(d);
    const v = [r.u16(), r.u16(), r.u16(), r.u16()];
    const gameVersion = v.slice(0, 3).join(".");
    // 1.1 : un octet après la version ; 2.x : deux.
    r.p += v[0] >= 2 ? 2 : 1;
    r.str(); // campagne
    r.str(); // niveau
    r.str(); // mod de base
    r.p += 3; // difficulté, terminée, gagnée
    r.str(); // niveau suivant
    r.p += 4; // drapeaux
    r.ou16(); r.ou16(); r.ou16(); // « chargée depuis » (version)
    r.p += 3; // build, commandes autorisées
    // Quelques octets de plus selon les versions : on cherche le début de la liste juste après.
    for (let off = 0; off < 32; off++) {
      const mods = readMods(d, r.p + off);
      if (mods) return { gameVersion, mods };
    }
  } catch { /* format inattendu : repli ci-dessous */ }
  // Repli : recherche de la liste dans le début du fichier.
  for (let p = 8; p < Math.min(d.length, 8192); p++) {
    const mods = readMods(d, p);
    if (mods && mods.length > 0) {
      const v = new Reader(d);
      return { gameVersion: [v.u16(), v.u16(), v.u16()].join("."), mods };
    }
  }
  return null;
}

// ---------------------------------------------------------------- zip minimal (lecture partielle du fichier)

interface ZipEntry { name: string; method: number; compSize: number; offset: number }

async function bytes(blob: Blob) { return new Uint8Array(await blob.arrayBuffer()); }
const dv = (b: Uint8Array) => new DataView(b.buffer, b.byteOffset, b.byteLength);

async function zipEntries(file: Blob): Promise<ZipEntry[]> {
  const tailSize = Math.min(file.size, 65_557);
  const tail = await bytes(file.slice(file.size - tailSize));
  let eocd = -1;
  for (let i = tail.length - 22; i >= 0; i--) {
    if (tail[i] === 0x50 && tail[i + 1] === 0x4b && tail[i + 2] === 0x05 && tail[i + 3] === 0x06) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error("not a zip");
  const e = dv(tail);
  const cdSize = e.getUint32(eocd + 12, true);
  const cdOffset = e.getUint32(eocd + 16, true);
  const cd = await bytes(file.slice(cdOffset, cdOffset + cdSize));
  const c = dv(cd);
  const out: ZipEntry[] = [];
  let p = 0;
  while (p + 46 <= cd.length && c.getUint32(p, true) === 0x02014b50) {
    const method = c.getUint16(p + 10, true);
    const compSize = c.getUint32(p + 20, true);
    const nameLen = c.getUint16(p + 28, true), extraLen = c.getUint16(p + 30, true), commentLen = c.getUint16(p + 32, true);
    const offset = c.getUint32(p + 42, true);
    const name = new TextDecoder().decode(cd.subarray(p + 46, p + 46 + nameLen));
    out.push({ name, method, compSize, offset });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

async function inflate(data: Uint8Array, format: CompressionFormat): Promise<Uint8Array> {
  const stream = new Blob([data as BlobPart]).stream().pipeThrough(new DecompressionStream(format));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

async function zipRead(file: Blob, entry: ZipEntry): Promise<Uint8Array> {
  const head = dv(await bytes(file.slice(entry.offset, entry.offset + 30)));
  const start = entry.offset + 30 + head.getUint16(26, true) + head.getUint16(28, true);
  const raw = await bytes(file.slice(start, start + entry.compSize));
  if (entry.method === 0) return raw;
  if (entry.method === 8) return inflate(raw, "deflate-raw");
  throw new Error("unsupported zip method");
}

/** level-init.dat, sinon level.dat, sinon level.dat0 (compressé zlib). */
async function levelFrom(read: (suffix: string) => Promise<Uint8Array | null>) {
  for (const f of ["level-init.dat", "level.dat"]) {
    const d = await read(f);
    const r = d && parseLevelHeader(d);
    if (r) return r;
  }
  const d0 = await read("level.dat0");
  if (d0) {
    const r = parseLevelHeader(await inflate(d0, "deflate").catch(() => d0));
    if (r) return r;
  }
  return null;
}

export async function parseSaveZip(file: File): Promise<ParsedSave | null> {
  const entries = await zipEntries(file);
  const pick = (suffix: string) => entries
    .filter((e) => e.name === suffix || e.name.endsWith("/" + suffix))
    .sort((a, b) => a.name.split("/").length - b.name.split("/").length)[0];
  const r = await levelFrom(async (s) => { const e = pick(s); return e ? zipRead(file, e) : null; });
  return r && { name: file.name.replace(/\.zip$/i, ""), ...r, source: "save" };
}

// ---------------------------------------------------------------- dossiers

const relPath = (f: File) => (f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name;
const dirOf = (path: string) => path.split("/").slice(0, -1).join("/");
const baseName = (path: string) => path.split("/").pop() ?? path;

/** mod-list.json (+ archives nom_version.zip du même dossier pour les versions). */
async function parseModList(list: File, siblings: File[]): Promise<ParsedSave | null> {
  const json = JSON.parse(await list.text()) as { mods?: { name: string; enabled?: boolean; version?: string }[] };
  if (!Array.isArray(json.mods)) return null;
  const versions = new Map<string, string>();
  for (const f of siblings) {
    const m = /^(.+)_(\d+\.\d+\.\d+)\.zip$/.exec(baseName(relPath(f)));
    if (m && (!versions.has(m[1]) || cmpVersion(m[2], versions.get(m[1])!) > 0)) versions.set(m[1], m[2]);
  }
  const enabled = json.mods.filter((m) => m.enabled !== false);
  const base = versions.get("base") ?? "";
  return {
    name: dirOf(relPath(list)) || "mod-list.json",
    gameVersion: base,
    mods: enabled.map((m) => ({ name: m.name, version: m.version ?? versions.get(m.name) ?? "" })),
    disabled: json.mods.filter((m) => m.enabled === false).map((m) => m.name),
    source: "mod-list",
  };
}

export function cmpVersion(a: string, b: string) {
  const pa = a.split(".").map(Number), pb = b.split(".").map(Number);
  for (let i = 0; i < 3; i++) if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) - (pb[i] || 0);
  return 0;
}

/**
 * Analyse ce que l'utilisateur a choisi (fichiers ou dossier) et renvoie toutes les sauvegardes trouvées.
 * Les archives de mods d'un dossier « mods » ne sont pas prises pour des sauvegardes.
 */
export async function parseSelection(files: File[]): Promise<{ saves: ParsedSave[]; failed: string[] }> {
  const saves: ParsedSave[] = [];
  const failed: string[] = [];
  const byDir = new Map<string, File[]>();
  for (const f of files) {
    const d = dirOf(relPath(f));
    byDir.set(d, [...(byDir.get(d) ?? []), f]);
  }
  const modDirs = new Set<string>();
  for (const [dir, list] of byDir) {
    const ml = list.find((f) => baseName(relPath(f)) === "mod-list.json");
    if (ml) {
      modDirs.add(dir);
      const r = await parseModList(ml, list).catch(() => null);
      if (r) saves.push(r); else failed.push(relPath(ml));
    }
    // Sauvegarde décompressée : un level-init.dat / level.dat dans le dossier.
    const byName = new Map(list.map((f) => [baseName(relPath(f)), f]));
    if (byName.has("level-init.dat") || byName.has("level.dat") || byName.has("level.dat0")) {
      const r = await levelFrom(async (s) => { const f = byName.get(s); return f ? bytes(f) : null; }).catch(() => null);
      if (r) saves.push({ name: baseName(dir) || "save", ...r, source: "save" }); else failed.push(dir);
    }
  }
  for (const f of files) {
    if (!/\.zip$/i.test(f.name) || modDirs.has(dirOf(relPath(f)))) continue;
    try {
      const r = await parseSaveZip(f);
      if (r) saves.push(r); else failed.push(f.name);
    } catch {
      failed.push(f.name);
    }
  }
  return { saves, failed };
}

// ---------------------------------------------------------------- glisser-déposer / coller

/** Seuls ces fichiers servent (le reste d'un dossier n'est jamais lu). */
export function relevant(f: File) {
  return /\.zip$/i.test(f.name) || ["mod-list.json", "level-init.dat", "level.dat", "level.dat0"].includes(f.name);
}

/**
 * Vrai si ce qui est glissé / collé peut contenir une sauvegarde : un dossier ou un fichier utile.
 * À appeler pendant l'événement (les entrées ne sont plus lisibles ensuite).
 */
export function maybeSaves(dt: DataTransfer | null): boolean {
  if (!dt) return false;
  for (const item of Array.from(dt.items ?? [])) {
    if (item.kind !== "file") continue;
    const entry = item.webkitGetAsEntry?.();
    if (entry?.isDirectory) return true;
    const f = item.getAsFile();
    if (f && relevant(f)) return true;
  }
  return Array.from(dt.files ?? []).some(relevant);
}

/** Fichiers utiles d'un glisser-déposer ou d'un collage, dossiers compris (parcourus sur 3 niveaux au plus). */
export async function filesFromDataTransfer(dt: DataTransfer): Promise<File[]> {
  // Les entrées doivent être récupérées avant le premier await.
  const entries = Array.from(dt.items ?? []).filter((i) => i.kind === "file").map((i) => i.webkitGetAsEntry?.()).filter((x): x is FileSystemEntry => !!x);
  const plain = Array.from(dt.files ?? []);
  if (!entries.length) return plain.filter(relevant);
  const files: File[] = [];
  const walk = async (entry: FileSystemEntry, prefix: string): Promise<void> => {
    if (entry.isFile) {
      const f = await new Promise<File>((res, rej) => (entry as FileSystemFileEntry).file(res, rej));
      Object.defineProperty(f, "webkitRelativePath", { value: prefix + entry.name });
      if (relevant(f)) files.push(f);
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
