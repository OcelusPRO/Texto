import { languages } from "@codemirror/language-data";
import type { LanguageSupport } from "@codemirror/language";
import hljs from "highlight.js/lib/common";

/** Langages proposés dans l'éditeur (nom CodeMirror). */
export const LANGUAGES = [
  "Bash", "C", "C++", "C#", "CSS", "Dart", "Diff", "Dockerfile", "Elixir", "Erlang", "Go", "Groovy", "Haskell",
  "HTML", "INI", "Java", "JavaScript", "JSON", "JSX", "Kotlin", "LaTeX", "Lua", "Markdown", "Nginx", "Perl", "PHP",
  "PowerShell", "Properties files", "Protobuf", "Python", "R", "Ruby", "Rust", "Sass", "Scala", "Shell", "SQL",
  "Swift", "TOML", "TSX", "TypeScript", "Vue", "XML", "YAML", "Zig",
];

const cache = new Map<string, Promise<LanguageSupport | null>>();

export function loadLanguage(name: string | null | undefined): Promise<LanguageSupport | null> {
  if (!name) return Promise.resolve(null);
  const key = name.toLowerCase();
  if (!cache.has(key)) {
    const desc = languages.find(
      (l) => l.name.toLowerCase() === key || l.alias.includes(key) || l.extensions.includes(key),
    );
    cache.set(key, desc ? desc.load().catch(() => null) : Promise.resolve(null));
  }
  return cache.get(key)!;
}

// highlight.js -> nom CodeMirror, pour la détection automatique.
const HLJS_TO_CM: Record<string, string> = {
  bash: "Shell", shell: "Shell", c: "C", cpp: "C++", csharp: "C#", css: "CSS", diff: "Diff", go: "Go",
  java: "Java", javascript: "JavaScript", json: "JSON", kotlin: "Kotlin", lua: "Lua", markdown: "Markdown",
  perl: "Perl", php: "PHP", python: "Python", r: "R", ruby: "Ruby", rust: "Rust", scss: "Sass", sql: "SQL",
  swift: "Swift", typescript: "TypeScript", xml: "XML", yaml: "YAML", ini: "INI", makefile: "Shell",
  "php-template": "PHP", plaintext: "", graphql: "", objectivec: "C", vbnet: "", wasm: "",
};

/** Devine le langage d'un contenu (null si incertain). */
export function detectLanguage(content: string): string | null {
  const sample = content.slice(0, 20_000);
  if (!sample.trim()) return null;
  const r = hljs.highlightAuto(sample);
  if (!r.language || r.relevance < 5) return null;
  return HLJS_TO_CM[r.language] || null;
}
