import clsx from "clsx";
import { Code2, Copy, Info, PencilRuler, TriangleAlert } from "lucide-react";
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useConfig } from "../lib/session";
import { Button, Modal, useCopy } from "../components/ui";
import { BlueprintCanvas, BookGrid, BookTree, FBE_PUBLIC_URL, InfoPanel, PlannerView, RichText, isVanilla, useManifest } from "./BlueprintViewer";
import { FbeFrame, type FbeHandle } from "./FbeFrame";
import { parsePlanString, type PlanModel } from "./model";
import { nodeString, replaceNode } from "./plan";
import { frameThumbnail, pictureResolution } from "./thumbnail";

/** Marge sous la zone (padding du <main> + petit jeu) : la page ne doit jamais défiler. */
const BOTTOM_GAP = 26;

/** Le plan peut-il être ouvert dans FBE (instance configurée, entités vanilla uniquement) ? */
export function useFbeAvailable(content: string): boolean {
  const { data: cfg } = useConfig();
  const manifest = useManifest();
  return useMemo(() => {
    if (!cfg?.fbeUrl || !content.trim()) return false;
    try {
      return isVanilla(parsePlanString(content, manifest));
    } catch {
      return false;
    }
  }, [cfg?.fbeUrl, content, manifest]);
}

function walk(root: PlanModel, path: number[]): { current: PlanModel; trail: PlanModel[]; path: number[] } {
  let current = root;
  const trail = [root];
  const valid: number[] = [];
  for (const idx of path) {
    if (current.kind !== "book") break;
    const child = current.children.find((c) => c.index === idx)?.node;
    if (!child) break;
    current = child;
    trail.push(child);
    valid.push(idx);
  }
  return { current, trail, path: valid };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const tabClass = (on: boolean) => clsx("px-2 py-1", on ? "bg-zinc-200 text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100" : "hover:text-zinc-900 dark:hover:text-zinc-100");
const barButton = "inline-flex items-center gap-1.5 border border-zinc-300 px-2 py-1 text-xs text-zinc-700 hover:bg-zinc-200 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800";

/**
 * Affichage d'un post Factorio : plan, livre ou planificateur.
 * - Schéma de Texto par défaut (léger) ; FBE (iframe, lourd) n’est monté que si on clique dessus, puis reste monté quand on rebascule sur le schéma ;
 *   rendu schématique de Texto sinon (entités de mods visibles en blocs).
 * - Les livres se parcourent avec l'arbre à gauche ; chaque plan vanilla s'ouvre dans FBE.
 * - La zone occupe exactement la hauteur restante de la fenêtre.
 * [onSave] : présent pour l'auteur ; un export fait dans FBE (Ctrl+C) peut être enregistré (le livre est reconstruit).
 * [thumbnail] : pour l'auteur, capture FBE envoyée automatiquement tant que le post n'a pas d'image d'aperçu.
 */
export function FactorioView({ content, compact = false, onSave, thumbnail }: {
  content: string;
  compact?: boolean;
  onSave?: (fullString: string) => Promise<void>;
  thumbnail?: { missing: boolean; upload: (png: Blob) => Promise<void> };
}) {
  const { t } = useTranslation();
  const { data: cfg } = useConfig();
  const manifest = useManifest();
  const copy = useCopy();
  const fbeUrl = compact ? null : cfg?.fbeUrl ?? null;

  const parsed = useMemo<{ model?: PlanModel; error?: string }>(() => {
    try {
      return { model: parsePlanString(content, manifest) };
    } catch (e) {
      return { error: e instanceof Error ? e.message : String(e) };
    }
  }, [content, manifest]);
  const root = parsed.model;

  const [path, setPath] = useState<number[]>([]);
  const [mode, setMode] = useState<"fbe" | "schema">("schema");
  const [fbeMounted, setFbeMounted] = useState(false);
  const [fbeContent, setFbeContent] = useState<string | null>(null);
  const [edited, setEdited] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [info, setInfo] = useState(false);
  const [rawOpen, setRawOpen] = useState(false);
  const [height, setHeight] = useState(compact ? 320 : 600);
  const areaRef = useRef<HTMLDivElement>(null);
  const fbeHandle = useRef<FbeHandle>(null);
  const savedRef = useRef<string | null>(null);
  const thumbDone = useRef(false);

  const nav = root ? walk(root, path) : null;
  const current = nav?.current;
  const canFbe = Boolean(fbeUrl && current?.kind === "blueprint" && isVanilla(current));
  const showFbe = canFbe && mode === "fbe";

  // Hauteur disponible : de la zone jusqu'au bas de la fenêtre.
  useLayoutEffect(() => {
    if (compact) return;
    const measure = () => {
      const el = areaRef.current;
      if (!el) return;
      const top = el.getBoundingClientRect().top + window.scrollY;
      setHeight(Math.max(360, Math.floor(window.innerHeight - top - BOTTOM_GAP)));
    };
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  });

  // FBE est monté à la première utilisation, puis seulement masqué.
  useEffect(() => { if (showFbe) setFbeMounted(true); }, [showFbe]);

  // Plan montré dans FBE : celui du nœud courant (sauf si c'est le résultat de notre propre enregistrement).
  useEffect(() => {
    if (!showFbe || !root || !current) return;
    if (current === root && content.trim() === savedRef.current) return;
    const next = current === root ? content.trim() : nodeString(current);
    setFbeContent((prev) => (prev === next ? prev : next));
  }, [showFbe, root, current, content]);

  const handleExport = (bp: string) => {
    if (!onSave || !root || !nav) return;
    try {
      setEdited(replaceNode(root, nav.path, bp));
    } catch { /* export inexploitable : ignoré */ }
  };

  // Capture FBE pour les aperçus de liens (auteur, plan seul, tant qu'aucune image n'existe).
  useEffect(() => {
    if (!thumbnail?.missing || !showFbe || !fbeMounted || !fbeContent || root?.kind !== "blueprint" || thumbDone.current) return;
    thumbDone.current = true;
    let finished = false;
    (async () => {
      await sleep(2500);
      const res = pictureResolution(root.bounds.maxX - root.bounds.minX, root.bounds.maxY - root.bounds.minY);
      const picture = await fbeHandle.current?.picture(res);
      const framed = picture ? await frameThumbnail(picture) : null;
      if (framed) await thumbnail.upload(framed);
      finished = true;
    })().catch(() => { finished = false; });
    return () => { if (!finished) thumbDone.current = false; };
  }, [thumbnail, showFbe, fbeMounted, fbeContent, root]);

  if (parsed.error || !root || !nav || !current) {
    return (
      <div className="flex items-center gap-2 border border-zinc-300 p-4 text-sm dark:border-zinc-700">
        <TriangleAlert className="size-4 shrink-0" /> {t("factorio.invalid")} <code className="text-xs opacity-70">{parsed.error}</code>
      </div>
    );
  }

  const showTree = root.kind === "book" && !compact;
  const label = (n: PlanModel) => (n.label ? <RichText text={n.label} manifest={manifest} /> : t(`factorio.kind.${n.kind}`));
  const showToolbar = !compact || nav.trail.length > 1;

  return (
    <div ref={areaRef} style={{ height }} className={clsx("grid gap-3", showTree && "lg:grid-cols-[220px_minmax(0,1fr)]")}>
      {showTree && (
        <aside className="hidden overflow-auto border border-zinc-300 p-1 text-sm lg:block dark:border-zinc-800">
          <BookTree node={root} path={[]} selected={nav.path} onSelect={setPath} manifest={manifest} />
        </aside>
      )}
      <div className="flex min-h-0 min-w-0 flex-col gap-2">
        {showToolbar && (
          <div className="relative flex flex-wrap items-center gap-2 text-xs text-zinc-500">
            <nav className="flex min-w-0 flex-1 flex-wrap items-center gap-1">
              {nav.trail.length > 1 && nav.trail.map((n, i) => (
                <button key={i} onClick={() => setPath(nav.path.slice(0, i))} className="px-1.5 py-0.5 hover:bg-zinc-200 dark:hover:bg-zinc-800">
                  {i > 0 && "› "}{label(n)}
                </button>
              ))}
            </nav>

            {edited && edited !== content.trim() && onSave && (
              <>
                <span>{t("factorio.edited")}</span>
                <Button size="sm" variant="primary" loading={saving} onClick={async () => {
                  setSaving(true);
                  try {
                    savedRef.current = edited;
                    await onSave(edited);
                    setEdited(null);
                  } finally { setSaving(false); }
                }}>{t("factorio.saveVersion")}</Button>
                <Button size="sm" variant="ghost" onClick={() => setEdited(null)}>{t("common.cancel")}</Button>
              </>
            )}

            {canFbe && (
              <div className="flex border border-zinc-300 dark:border-zinc-700">
                <button onClick={() => setMode("fbe")} className={tabClass(mode === "fbe")}>FBE</button>
                <button onClick={() => setMode("schema")} className={tabClass(mode === "schema")}>{t("factorio.viewSchema")}</button>
              </div>
            )}
            {!compact && current.kind === "blueprint" && (
              <button onClick={() => setInfo(!info)} className={clsx(barButton, info && "bg-zinc-200 dark:bg-zinc-800")} title={t("factorio.bom")}>
                <Info className="size-3.5" /> {t("factorio.info")}
              </button>
            )}
            {!compact && <span>Factorio {current.version}</span>}
            {!compact && (
              <>
                <button onClick={() => copy(content.trim(), t("factorio.copied"))} className={barButton}><Copy className="size-3.5" /> {t("factorio.copyString")}</button>
                <button onClick={() => setRawOpen(true)} className={barButton} title={t("post.showSource")}><Code2 className="size-3.5" /></button>
                {!fbeUrl && isVanilla(root) && (
                  <a href={`${FBE_PUBLIC_URL}#source=${content.trim()}`} target="_blank" rel="noopener noreferrer" className={barButton} title={t("factorio.editInFbe")}>
                    <PencilRuler className="size-3.5" /> {t("factorio.edit")}
                  </a>
                )}
              </>
            )}
            {info && current.kind === "blueprint" && (
              <div className="absolute right-0 top-full z-30 mt-1 max-h-[60vh] w-80 overflow-auto border border-zinc-300 bg-zinc-50 p-3 shadow dark:border-zinc-700 dark:bg-zinc-900">
                <InfoPanel model={current} manifest={manifest} />
              </div>
            )}
          </div>
        )}

        <div className="relative min-h-0 flex-1">
          {fbeMounted && fbeContent && fbeUrl && (
            <FbeFrame content={fbeContent} visible={showFbe} onExport={onSave ? handleExport : undefined} handle={fbeHandle} />
          )}
          {current.kind === "book" && (
            <div className="absolute inset-0 overflow-auto"><BookGrid book={current} manifest={manifest} onOpen={(idx) => setPath([...nav.path, idx])} /></div>
          )}
          {current.kind === "blueprint" && !showFbe && (
            <div className="absolute inset-0"><BlueprintCanvas model={current} manifest={manifest} /></div>
          )}
          {(current.kind === "upgrade" || current.kind === "deconstruction") && (
            <div className="absolute inset-0 overflow-auto"><PlannerView model={current} manifest={manifest} /></div>
          )}
        </div>
      </div>

      <Modal open={rawOpen} onClose={() => setRawOpen(false)} wide title={t("factorio.rawString")}
        footer={<Button onClick={() => copy(content.trim(), t("factorio.copied"))}><Copy className="size-4" />{t("factorio.copyString")}</Button>}>
        <pre className="max-h-[50vh] overflow-auto whitespace-pre-wrap break-all font-mono text-xs">{content.trim()}</pre>
      </Modal>
    </div>
  );
}
