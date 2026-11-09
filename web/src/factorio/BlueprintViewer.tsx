import clsx from "clsx";
import { BookOpen, Cable, Grid3x3, Image as ImageIcon, Maximize, Tags, TriangleAlert } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useConfig } from "../lib/session";
import { iconPath, loadManifest, type AssetManifest } from "./assets";
import { bomOf, richText, type BlueprintModel, type PlanModel, type SignalRef } from "./model";
import { entityAt, fitView, render, type View } from "./renderer";

export function useManifest() {
  const { data: cfg } = useConfig();
  const [manifest, setManifest] = useState<AssetManifest | null>(null);
  useEffect(() => {
    if (cfg) loadManifest(cfg.factorioAssets).then(setManifest);
  }, [cfg]);
  return manifest;
}

export const FBE_PUBLIC_URL = "https://fbe.factorygamefan.com/";
const VANILLA_MODS = new Set(["base", "space-age", "quality", "elevated-rails"]);

/** Plan uniquement composé d'entités vanilla (affichable et éditable dans Factorio Blueprint Editor). */
export function isVanilla(m: PlanModel): boolean {
  if (m.kind === "book") return m.children.every((c) => isVanilla(c.node));
  if (m.kind === "blueprint") return m.entities.every((e) => e.known && VANILLA_MODS.has(e.mod ?? ""));
  return true;
}

export function SignalIcon({ signal, manifest, size = 20 }: { signal: SignalRef; manifest: AssetManifest | null; size?: number }) {
  const p = iconPath(manifest, signal.name, signal.type ?? "item");
  if (p) return <img src={`/factorio-assets/${p}`} alt={signal.name} title={signal.name} width={size} height={size} className="inline-block" style={{ imageRendering: "auto" }} />;
  return (
    <span title={signal.name} className="inline-flex items-center rounded bg-zinc-200 px-1 font-mono text-[10px] leading-4 text-zinc-700 dark:bg-zinc-700 dark:text-zinc-200">
      {signal.name.length > 14 ? signal.name.slice(0, 13) + "…" : signal.name}
    </span>
  );
}

export function RichText({ text, manifest }: { text: string; manifest: AssetManifest | null }) {
  return (
    <>
      {richText(text).map((seg, i) =>
        "text" in seg ? <span key={i}>{seg.text}</span> : <SignalIcon key={i} signal={{ name: seg.icon, type: seg.type }} manifest={manifest} size={16} />,
      )}
    </>
  );
}

export function PlanTitle({ node, manifest }: { node: PlanModel; manifest: AssetManifest | null }) {
  const { t } = useTranslation();
  return (
    <span className="inline-flex min-w-0 items-center gap-1">
      {node.icons.slice(0, 4).map((s, i) => <SignalIcon key={i} signal={s} manifest={manifest} size={18} />)}
      <span className="truncate">{node.label ? <RichText text={node.label} manifest={manifest} /> : t(`factorio.kind.${node.kind}`)}</span>
    </span>
  );
}

export function BookTree({ node, path, selected, onSelect, manifest }: { node: PlanModel; path: number[]; selected: number[]; onSelect: (p: number[]) => void; manifest: AssetManifest | null }) {
  const active = path.length === selected.length && path.every((v, i) => v === selected[i]);
  return (
    <div>
      <button onClick={() => onSelect(path)} className={clsx("flex w-full items-center gap-1.5 rounded px-2 py-1 text-left", active ? "bg-zinc-200 text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100" : "hover:bg-zinc-100 dark:hover:bg-zinc-800")}>
        {node.kind === "book" && <BookOpen className="size-3.5 shrink-0 text-zinc-400" />}
        <PlanTitle node={node} manifest={manifest} />
      </button>
      {node.kind === "book" && (
        <div className="ml-3 border-l border-zinc-200 pl-1 dark:border-zinc-800">
          {node.children.map((c) => (
            <BookTree key={c.index} node={c.node} path={[...path, c.index]} selected={selected} onSelect={onSelect} manifest={manifest} />
          ))}
        </div>
      )}
    </div>
  );
}

export function BookGrid({ book, manifest, onOpen }: { book: Extract<PlanModel, { kind: "book" }>; manifest: AssetManifest | null; onOpen: (i: number) => void }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 text-lg font-semibold"><BookOpen className="size-5 text-zinc-400" /><PlanTitle node={book} manifest={manifest} /></div>
      {book.description && <p className="whitespace-pre-wrap text-sm text-zinc-600 dark:text-zinc-400"><RichText text={book.description} manifest={manifest} /></p>}
      <div className="grid grid-cols-[repeat(auto-fill,minmax(180px,1fr))] gap-3">
        {book.children.map((c) => (
          <button key={c.index} onClick={() => onOpen(c.index)} className="group overflow-hidden rounded-md border border-zinc-200 text-left transition hover:border-zinc-400 dark:border-zinc-800">
            {c.node.kind === "blueprint" ? (
              <MiniPreview model={c.node} manifest={manifest} />
            ) : (
              <div className="flex h-28 items-center justify-center bg-zinc-800 text-zinc-400">
                {c.node.kind === "book" ? <BookOpen className="size-8 text-zinc-400" /> : t(`factorio.kind.${c.node.kind}`)}
              </div>
            )}
            <div className="truncate px-2 py-1.5 text-sm"><PlanTitle node={c.node} manifest={manifest} /></div>
          </button>
        ))}
      </div>
    </div>
  );
}

function MiniPreview({ model, manifest }: { model: BlueprintModel; manifest: AssetManifest | null }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const c = ref.current;
    if (!c) return;
    const w = 360, h = 224;
    c.width = w; c.height = h;
    const ctx = c.getContext("2d")!;
    const draw = () => render(ctx, model, fitView(model, w, h, 10), w, h, { grid: false, labels: false, wires: false, icons: Boolean(manifest), manifest, hover: null, dark: true, onAsset: () => requestAnimationFrame(draw) });
    draw();
  }, [model, manifest]);
  return <canvas ref={ref} className="h-28 w-full bg-zinc-900 object-cover" />;
}

function Toggle({ on, onClick, icon, label }: { on: boolean; onClick: () => void; icon: ReactNode; label: string }) {
  return (
    <button onClick={onClick} title={label} className={clsx("inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium", on ? "bg-white/15 text-white" : "text-zinc-400 hover:text-white")}>
      {icon}<span className="hidden sm:inline">{label}</span>
    </button>
  );
}

export function BlueprintCanvas({ model, manifest }: { model: BlueprintModel; manifest: AssetManifest | null }) {
  const { t } = useTranslation();
  const wrap = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const view = useRef<View | null>(null);
  const [size, setSize] = useState({ w: 800, h: 500 });
  const [grid, setGrid] = useState(true);
  const [labels, setLabels] = useState(true);
  const [wires, setWires] = useState(true);
  const [icons, setIcons] = useState(true);
  const [hover, setHover] = useState<{ n: number; x: number; y: number } | null>(null);
  const drag = useRef<{ x: number; y: number; moved: boolean } | null>(null);
  const frame = useRef(0);
  const dark = document.documentElement.classList.contains("dark");

  const draw = useCallback(() => {
    cancelAnimationFrame(frame.current);
    frame.current = requestAnimationFrame(() => {
      const c = canvas.current;
      if (!c || !view.current) return;
      const dpr = window.devicePixelRatio || 1;
      const ctx = c.getContext("2d")!;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      render(ctx, model, view.current, size.w, size.h, {
        grid, labels, wires, icons: icons && Boolean(manifest), manifest, hover: hover?.n ?? null, dark, onAsset: draw,
      });
    });
  }, [model, size, grid, labels, wires, icons, manifest, hover, dark]);

  useEffect(() => {
    const el = wrap.current;
    if (!el) return;
    // Le canvas occupe tout son conteneur.
    const measure = () => {
      const w = el.clientWidth;
      const h = el.clientHeight;
      if (w > 0 && h > 0) setSize((s) => (s.w === w && s.h === h ? s : { w, h }));
    };
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    window.addEventListener("resize", measure);
    return () => { ro.disconnect(); window.removeEventListener("resize", measure); };
  }, []);

  useEffect(() => {
    view.current = fitView(model, size.w, size.h);
  }, [model, size.w, size.h]);

  useEffect(() => {
    const c = canvas.current;
    if (!c) return;
    const dpr = window.devicePixelRatio || 1;
    c.width = size.w * dpr;
    c.height = size.h * dpr;
    draw();
  }, [size, draw]);

  // Zoom à la molette (centré sur le curseur).
  useEffect(() => {
    const c = canvas.current;
    if (!c) return;
    const onWheel = (ev: WheelEvent) => {
      ev.preventDefault();
      const v = view.current!;
      const rect = c.getBoundingClientRect();
      const mx = ev.clientX - rect.left, my = ev.clientY - rect.top;
      const factor = Math.exp(-ev.deltaY * 0.0015);
      const scale = Math.max(1, Math.min(128, v.scale * factor));
      const k = scale / v.scale;
      view.current = { scale, ox: mx - (mx - v.ox) * k, oy: my - (my - v.oy) * k };
      draw();
    };
    c.addEventListener("wheel", onWheel, { passive: false });
    return () => c.removeEventListener("wheel", onWheel);
  }, [draw]);

  const hovered = hover ? model.entities.find((e) => e.number === hover.n) : null;

  return (
    <div ref={wrap} className="relative h-full min-h-[200px] overflow-hidden border border-zinc-300 dark:border-zinc-800">
      <canvas
        ref={canvas}
        style={{ width: size.w, height: size.h, cursor: drag.current ? "grabbing" : "grab", touchAction: "none", display: "block" }}
        onPointerDown={(e) => { drag.current = { x: e.clientX, y: e.clientY, moved: false }; (e.target as HTMLElement).setPointerCapture(e.pointerId); }}
        onPointerUp={() => { drag.current = null; }}
        onPointerLeave={() => setHover(null)}
        onPointerMove={(e) => {
          const v = view.current!;
          if (drag.current) {
            const dx = e.clientX - drag.current.x, dy = e.clientY - drag.current.y;
            drag.current = { x: e.clientX, y: e.clientY, moved: true };
            view.current = { ...v, ox: v.ox + dx, oy: v.oy + dy };
            draw();
            return;
          }
          const rect = e.currentTarget.getBoundingClientRect();
          const ent = entityAt(model, v, e.clientX - rect.left, e.clientY - rect.top);
          setHover(ent ? { n: ent.number, x: e.clientX - rect.left, y: e.clientY - rect.top } : null);
        }}
        onDoubleClick={() => { view.current = fitView(model, size.w, size.h); draw(); }}
      />
      <div className="absolute left-2 top-2 flex flex-wrap gap-0.5 rounded bg-zinc-950/80 p-1 backdrop-blur">
        <Toggle on={false} onClick={() => { view.current = fitView(model, size.w, size.h); draw(); }} icon={<Maximize className="size-3.5" />} label={t("factorio.fit")} />
        <Toggle on={grid} onClick={() => setGrid(!grid)} icon={<Grid3x3 className="size-3.5" />} label={t("factorio.grid")} />
        <Toggle on={labels} onClick={() => setLabels(!labels)} icon={<Tags className="size-3.5" />} label={t("factorio.labels")} />
        {model.wires.length > 0 && <Toggle on={wires} onClick={() => setWires(!wires)} icon={<Cable className="size-3.5" />} label={t("factorio.wires")} />}
        {manifest && <Toggle on={icons} onClick={() => setIcons(!icons)} icon={<ImageIcon className="size-3.5" />} label={t("factorio.icons")} />}
      </div>
      <div className="absolute bottom-2 right-2 rounded bg-zinc-950/70 px-2 py-0.5 text-[11px] text-zinc-400">
        {Math.round(model.bounds.maxX - model.bounds.minX)}×{Math.round(model.bounds.maxY - model.bounds.minY)} · {model.entities.length} {t("factorio.entities")}
      </div>
      {hovered && hover && (
        <div className="pointer-events-none absolute z-10 max-w-64 rounded bg-zinc-950/95 px-3 py-2 text-xs text-zinc-200 shadow ring-1 ring-zinc-700"
          style={{ left: Math.max(4, Math.min(hover.x + 14, size.w - 260)), top: Math.max(4, Math.min(hover.y + 14, size.h - 90)) }}>
          <div className="font-semibold text-white">{hovered.name}</div>
          {hovered.mod && <div className={clsx(hovered.known ? "text-zinc-400" : "text-amber-400")}>{t("factorio.mod")} : {hovered.mod}</div>}
          {!hovered.known && <div className="text-amber-400">{t("factorio.unknownEntity")}</div>}
          {hovered.guessed && <div className="text-zinc-400">{t("factorio.estimatedSize", { w: hovered.w, h: hovered.h })}</div>}
          {hovered.recipe && <div>{t("factorio.recipe")} : {hovered.recipe}</div>}
          {hovered.quality && hovered.quality !== "normal" && <div>{t("factorio.quality")} : {hovered.quality}</div>}
          <div className="text-zinc-500">x {hovered.x}, y {hovered.y}</div>
        </div>
      )}
    </div>
  );
}

/** Matériaux et entités de mods inconnues, en panneau superposé (n'ajoute jamais de hauteur à la page). */
export function InfoPanel({ model, manifest }: { model: BlueprintModel; manifest: AssetManifest | null }) {
  const { t } = useTranslation();
  const bom = useMemo(() => bomOf(model), [model]);
  const unknown = bom.filter((b) => !b.known);
  return (
    <div className="space-y-3 text-sm">
      {model.description && <p className="whitespace-pre-wrap text-zinc-600 dark:text-zinc-400"><RichText text={model.description} manifest={manifest} /></p>}
      {unknown.length > 0 && (
        <div className="border border-zinc-300 p-2 text-xs dark:border-zinc-700">
          <div className="mb-1 flex items-center gap-1 font-semibold"><TriangleAlert className="size-3.5" />{t("factorio.unknownTitle", { count: unknown.length })}</div>
          <ul className="space-y-0.5">
            {unknown.slice(0, 12).map((u) => <li key={u.name}><code>{u.name}</code>{u.mod && <span className="opacity-70"> · {u.mod}</span>}</li>)}
          </ul>
        </div>
      )}
      <div>
        <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-zinc-500">{t("factorio.bom")}</div>
        <ul className="divide-y divide-zinc-200 dark:divide-zinc-800">
          {bom.map((b) => (
            <li key={b.name} className="flex items-center gap-2 py-1">
              <SignalIcon signal={{ name: b.name, type: "entity" }} manifest={manifest} size={20} />
              <span className="min-w-0 flex-1 truncate text-xs text-zinc-500">{b.name}</span>
              <span className="font-mono text-xs font-semibold">×{b.count}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

export function PlannerView({ model, manifest }: { model: Extract<PlanModel, { kind: "upgrade" | "deconstruction" }>; manifest: AssetManifest | null }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-2 rounded-md border border-zinc-200 p-4 dark:border-zinc-800">
      <div className="font-semibold"><PlanTitle node={model} manifest={manifest} /></div>
      {model.description && <p className="whitespace-pre-wrap text-sm text-zinc-500"><RichText text={model.description} manifest={manifest} /></p>}
      <ul className="space-y-1 text-sm">
        {model.lines.map((l, i) => (
          <li key={i} className="flex items-center gap-2">
            {l.from && <SignalIcon signal={{ name: l.from, type: "entity" }} manifest={manifest} />}
            {l.to && <>→ <SignalIcon signal={{ name: l.to, type: "entity" }} manifest={manifest} /></>}
          </li>
        ))}
        {model.lines.length === 0 && <li className="text-zinc-500">{t("factorio.emptyPlanner")}</li>}
      </ul>
    </div>
  );
}
