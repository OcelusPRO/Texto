#!/usr/bin/env node
// Texto CLI — aucune dépendance (Node 18+).
//
//   export TEXTO_URL=https://texto.example.com TEXTO_TOKEN=txt_...
//   cat build.log | node texto.mjs -t "Build log" --tags ci,logs
//   node texto.mjs src/Main.kt --private --expire 1d
//   node texto.mjs get <clé>          # contenu brut
//   node texto.mjs ls [-q recherche]  # mes posts
//   node texto.mjs rm <clé>
import { readFileSync } from "node:fs";
import { basename, extname } from "node:path";

const HELP = `Usage :
  texto [fichier...] [options]     publie un fichier (ou stdin)
  texto get <clé>                  affiche le contenu brut d'un post
  texto ls [-q texte] [--tag t]    liste vos posts
  texto rm <clé>                   supprime un post

Options de publication :
  -t, --title <titre>        -d, --description <texte>
  -l, --lang <langage>       -k, --kind code|text|markdown|factorio
      --tags a,b,c               --folder <id>
      --public | --unlisted | --private (défaut : public)
      --expire 1h|1d|1w|1m|1y|<secondes>
      --password <mdp>           --burn <vues>

Variables : TEXTO_URL (ex. https://texto.example.com), TEXTO_TOKEN (token txt_… créé dans les paramètres).`;

const EXT_LANG = {
  ".kt": "Kotlin", ".kts": "Kotlin", ".java": "Java", ".js": "JavaScript", ".mjs": "JavaScript", ".ts": "TypeScript",
  ".tsx": "TSX", ".jsx": "JSX", ".py": "Python", ".rs": "Rust", ".go": "Go", ".c": "C", ".h": "C", ".cpp": "C++",
  ".cs": "C#", ".json": "JSON", ".yml": "YAML", ".yaml": "YAML", ".toml": "TOML", ".html": "HTML", ".css": "CSS",
  ".sql": "SQL", ".sh": "Shell", ".ps1": "PowerShell", ".lua": "Lua", ".php": "PHP", ".rb": "Ruby", ".xml": "XML",
  ".swift": "Swift", ".dart": "Dart", ".scala": "Scala", ".zig": "Zig", ".vue": "Vue",
};
const EXPIRE = { "1h": 3600, "1d": 86400, "1w": 604800, "1m": 2592000, "1y": 31536000, never: 0 };

const base = (process.env.TEXTO_URL ?? "").replace(/\/+$/, "");
const token = process.env.TEXTO_TOKEN ?? "";

function fail(msg) {
  console.error(msg);
  process.exit(1);
}

async function call(method, path, body) {
  if (!base) fail("TEXTO_URL n'est pas défini.");
  if (!token) fail("TEXTO_TOKEN n'est pas défini.");
  const res = await fetch(base + path, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let detail = "";
    try { detail = (await res.json()).error; } catch { /* ignore */ }
    fail(`Erreur ${res.status}${detail ? ` (${detail})` : ""}`);
  }
  return res.status === 204 ? null : res.headers.get("content-type")?.includes("json") ? res.json() : res.text();
}

async function readStdin() {
  if (process.stdin.isTTY) fail(HELP);
  const chunks = [];
  for await (const c of process.stdin) chunks.push(c);
  return Buffer.concat(chunks).toString("utf8");
}

const args = process.argv.slice(2);
const opts = { files: [], tags: [] };
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  const next = () => args[++i] ?? fail(`Valeur manquante pour ${a}`);
  switch (a) {
    case "-h": case "--help": console.log(HELP); process.exit(0);
    case "-t": case "--title": opts.title = next(); break;
    case "-d": case "--description": opts.description = next(); break;
    case "-l": case "--lang": opts.language = next(); break;
    case "-k": case "--kind": opts.kind = next(); break;
    case "--tags": case "--tag": opts.tags.push(...next().split(",").filter(Boolean)); break;
    case "--folder": opts.folderId = Number(next()); break;
    case "--public": opts.visibility = "public"; break;
    case "--unlisted": opts.visibility = "unlisted"; break;
    case "--private": opts.visibility = "private"; break;
    case "--expire": { const v = next(); opts.expiresIn = EXPIRE[v] ?? Number(v); break; }
    case "--password": opts.password = next(); break;
    case "--burn": opts.burnAfterViews = Number(next()); break;
    case "-q": case "--query": opts.q = next(); break;
    default: opts.files.push(a);
  }
}

const [command, ...rest] = opts.files;
if (command === "get") {
  const key = rest[0] ?? fail("Clé manquante.");
  const post = await call("GET", `/api/posts/${encodeURIComponent(key)}`);
  process.stdout.write(post.content);
} else if (command === "ls") {
  const p = new URLSearchParams({ pageSize: "50" });
  if (opts.q) p.set("q", opts.q);
  opts.tags.forEach((t) => p.append("tag", t));
  const page = await call("GET", `/api/me/posts?${p}`);
  for (const s of page.items) console.log(`${base}/${s.key}\t${s.kind}\t${s.title}${s.tags.length ? "\t#" + s.tags.map((t) => t.name).join(" #") : ""}`);
  console.error(`${page.items.length}/${page.total}`);
} else if (command === "rm") {
  const key = rest[0] ?? fail("Clé manquante.");
  await call("DELETE", `/api/posts/${encodeURIComponent(key)}`);
  console.log("Supprimé.");
} else {
  const inputs = opts.files.length ? opts.files.map((f) => ({ name: f, content: readFileSync(f, "utf8") })) : [{ name: null, content: await readStdin() }];
  for (const input of inputs) {
    const ext = input.name ? extname(input.name).toLowerCase() : "";
    const body = {
      title: opts.title ?? (input.name ? basename(input.name) : undefined),
      description: opts.description,
      kind: opts.kind ?? (ext === ".md" ? "markdown" : undefined),
      language: opts.language ?? EXT_LANG[ext],
      content: input.content,
      visibility: opts.visibility,
      tags: opts.tags.length ? opts.tags : undefined,
      folderId: opts.folderId,
      expiresIn: opts.expiresIn,
      password: opts.password,
      burnAfterViews: opts.burnAfterViews,
    };
    const created = await call("POST", "/api/posts", body);
    console.log(`${base}/${created.key}`);
  }
}
