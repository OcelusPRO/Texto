import { Prec } from "@codemirror/state";
import CodeMirror, { EditorView, type Extension } from "@uiw/react-codemirror";
import { githubDark, githubLight } from "@uiw/codemirror-theme-github";
import DOMPurify from "dompurify";
import hljs from "highlight.js/lib/common";
import { Marked } from "marked";
import { diffLines } from "diff";
import { useEffect, useMemo, useRef, useState } from "react";
import { loadLanguage } from "../lib/languages";

export function useDark() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains("dark"));
  useEffect(() => {
    const obs = new MutationObserver(() => setDark(document.documentElement.classList.contains("dark")));
    obs.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
    return () => obs.disconnect();
  }, []);
  return dark;
}

export function useLanguageExtension(language: string | null | undefined) {
  const [ext, setExt] = useState<Extension[]>([]);
  useEffect(() => {
    let alive = true;
    loadLanguage(language).then((l) => alive && setExt(l ? [l] : []));
    return () => { alive = false; };
  }, [language]);
  return ext;
}

// Fond anthracite / gris clair, sans les teintes bleutées du thème GitHub.
const surfaces = {
  dark: EditorView.theme({
    "&": { backgroundColor: "#1e1f22", color: "#d4d4d8" },
    ".cm-gutters": { backgroundColor: "#1e1f22", color: "#5c5f66", border: "none" },
    ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: "#26272b" },
  }, { dark: true }),
  light: EditorView.theme({
    "&": { backgroundColor: "#fafafa" },
    ".cm-gutters": { backgroundColor: "#fafafa", color: "#a1a1aa", border: "none" },
    ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: "#f0f0f1" },
  }),
};
const surface = (dark: boolean) => Prec.highest(dark ? surfaces.dark : surfaces.light);

/** Éditeur / visionneuse CodeMirror. */
export function CodeEditor({
  value, onChange, language, readOnly, minHeight = "60vh", wrap = false, autoFocus, bare = false,
}: { value: string; onChange?: (v: string) => void; language?: string | null; readOnly?: boolean; minHeight?: string; wrap?: boolean; autoFocus?: boolean; bare?: boolean }) {
  const dark = useDark();
  const lang = useLanguageExtension(language);
  // La zone éditable couvre toute la hauteur : un clic n'importe où place le curseur.
  const fill = useMemo(() => EditorView.theme({
    ".cm-scroller": { minHeight, cursor: readOnly ? "auto" : "text" },
    ".cm-content, .cm-gutter": { minHeight },
  }), [minHeight, readOnly]);
  const extensions = useMemo(() => [...lang, surface(dark), fill, ...(wrap ? [EditorView.lineWrapping] : [])], [lang, wrap, dark, fill]);
  return (
    <CodeMirror
      value={value}
      onChange={onChange}
      readOnly={readOnly}
      editable={!readOnly}
      autoFocus={autoFocus}
      theme={dark ? githubDark : githubLight}
      extensions={extensions}
      minHeight={minHeight}
      basicSetup={{ foldGutter: true, highlightActiveLine: !readOnly, highlightActiveLineGutter: !readOnly, autocompletion: false }}
      className={bare ? "" : "overflow-hidden border border-zinc-300 dark:border-zinc-800"}
    />
  );
}

const marked = new Marked({
  gfm: true,
  breaks: false,
  renderer: {
    code({ text, lang }) {
      const l = (lang ?? "").trim().split(/\s+/)[0];
      if (l === "mermaid") return `<pre class="mermaid">${escapeHtml(text)}</pre>`;
      const html = l && hljs.getLanguage(l) ? hljs.highlight(text, { language: l }).value : hljs.highlightAuto(text).value;
      return `<pre><code class="hljs language-${escapeHtml(l)}">${html}</code></pre>`;
    },
  },
});

function escapeHtml(s: string) {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

let mermaidId = 0;

/** Rendu Markdown (GFM) assaini, avec coloration des blocs de code et diagrammes Mermaid. */
export function MarkdownView({ content }: { content: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const dark = useDark();
  const html = useMemo(
    () => DOMPurify.sanitize(marked.parse(content) as string, { ADD_ATTR: ["target"] }),
    [content],
  );
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.querySelectorAll("a[href^='http']").forEach((a) => { a.setAttribute("target", "_blank"); a.setAttribute("rel", "noopener noreferrer nofollow"); });
    const blocks = Array.from(el.querySelectorAll<HTMLElement>("pre.mermaid"));
    if (!blocks.length) return;
    let alive = true;
    import("mermaid").then(async ({ default: mermaid }) => {
      mermaid.initialize({ startOnLoad: false, theme: dark ? "dark" : "default", securityLevel: "strict" });
      for (const b of blocks) {
        if (!alive) return;
        const src = b.textContent ?? "";
        try {
          const { svg } = await mermaid.render(`mmd-${++mermaidId}`, src);
          b.innerHTML = DOMPurify.sanitize(svg, { USE_PROFILES: { svg: true, svgFilters: true }, ADD_TAGS: ["foreignObject"] });
        } catch (e) {
          b.textContent = `Mermaid: ${e instanceof Error ? e.message : e}`;
        }
      }
    });
    return () => { alive = false; };
  }, [html, dark]);
  return (
    <div ref={ref} className="prose prose-slate max-w-none dark:prose-invert prose-pre:rounded-md prose-headings:scroll-mt-20 prose-img:rounded" dangerouslySetInnerHTML={{ __html: html }} />
  );
}

/** Diff ligne à ligne entre deux textes. */
export function DiffView({ before, after }: { before: string; after: string }) {
  const parts = useMemo(() => diffLines(before, after), [before, after]);
  let oldLine = 1, newLine = 1;
  const rows: { type: "add" | "del" | "same"; text: string; o?: number; n?: number }[] = [];
  for (const p of parts) {
    const lines = p.value.replace(/\n$/, "").split("\n");
    for (const l of lines) {
      if (p.added) rows.push({ type: "add", text: l, n: newLine++ });
      else if (p.removed) rows.push({ type: "del", text: l, o: oldLine++ });
      else rows.push({ type: "same", text: l, o: oldLine++, n: newLine++ });
    }
  }
  // Replie les longues zones inchangées.
  const visible = rows.map((r, i) => r.type !== "same" || rows.slice(Math.max(0, i - 3), i + 4).some((x) => x.type !== "same"));
  const added = rows.filter((r) => r.type === "add").length, removed = rows.filter((r) => r.type === "del").length;
  return (
    <div className="overflow-hidden rounded-md border border-zinc-200 font-mono text-[13px] dark:border-zinc-800">
      <div className="border-b border-zinc-200 bg-zinc-50 px-3 py-1.5 text-xs dark:border-zinc-800 dark:bg-zinc-900">
        <span className="text-emerald-600">+{added}</span> <span className="text-red-600">−{removed}</span>
      </div>
      <div className="overflow-x-auto">
        {rows.map((r, i) =>
          visible[i] ? (
            <div key={i} className={r.type === "add" ? "bg-emerald-50 dark:bg-emerald-950/40" : r.type === "del" ? "bg-red-50 dark:bg-red-950/40" : ""}>
              <span className="inline-block w-10 select-none pr-2 text-right text-zinc-400">{r.o ?? ""}</span>
              <span className="inline-block w-10 select-none pr-2 text-right text-zinc-400">{r.n ?? ""}</span>
              <span className="inline-block w-4 select-none text-zinc-400">{r.type === "add" ? "+" : r.type === "del" ? "−" : " "}</span>
              <span className="whitespace-pre">{r.text}</span>
            </div>
          ) : visible[i - 1] ? (
            <div key={i} className="bg-zinc-50 px-3 text-xs text-zinc-400 dark:bg-zinc-900">⋯</div>
          ) : null,
        )}
      </div>
    </div>
  );
}
