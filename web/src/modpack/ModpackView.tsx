import clsx from "clsx";
import { AlertTriangle, ChevronRight, Copy, Download, ExternalLink, FileJson, ListTree, List, Package, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { api } from "../lib/api";
import { Button, Input, Spinner, useCopy } from "../components/ui";
import { MarkdownView } from "../components/Viewers";
import { cmpVersion } from "./saveMods";
import { gameLabel, type ModpackGame } from "./games";
import {
  BUILTIN, BUILTIN_TITLES, buildTree, builtinRank, isModpack, modListJson, parseModpack, portalUrl, safeUrl,
  type ModInfo, type Modpack, type ModpackMod, type TreeNode,
} from "./model";

/** Fiches du portail pour une liste de mods (mises en cache côté serveur et côté client). */
export function useModInfos(mods: ModpackMod[]) {
  const refs = useMemo(
    () => mods.filter((m) => !BUILTIN.has(m.name)).map((m) => ({ name: m.name, version: m.version || undefined })),
    [mods],
  );
  const key = refs.map((r) => `${r.name}@${r.version ?? ""}`).join(",");
  const q = useQuery({
    queryKey: ["factorio-mods", key],
    queryFn: () => api.factorioMods(refs),
    enabled: refs.length > 0,
    staleTime: 3600_000,
    retry: 1,
  });
  const infos = useMemo(() => new Map<string, ModInfo>((q.data ?? []).map((i) => [i.name, i])), [q.data]);
  return { infos, loading: q.isLoading && refs.length > 0 };
}

export function ModpackView({ content, title, game }: { content: string; title?: string; game: ModpackGame }) {
  const pack = useMemo(() => parseModpack(content), [content]);
  return <ModpackDisplay pack={pack} title={title} game={game} />;
}

const NO_MODS: ModpackMod[] = [];

export function ModpackDisplay({ pack, title, game }: { pack: Modpack; title?: string; game: ModpackGame }) {
  const { t } = useTranslation();
  const copy = useCopy();
  // Fiches détaillées (image, dépendances…) : portail des mods Factorio uniquement pour l'instant.
  const factorio = game === "factorio";
  const { infos, loading } = useModInfos(factorio ? pack.mods : NO_MODS);
  const [filter, setFilter] = useState("");
  const [mode, setMode] = useState<"tree" | "flat">("tree");
  // Nœuds dépliés / repliés à la main (clé : chemin) ; par défaut les modpacks sont dépliés, le reste replié.
  const [toggled, setToggled] = useState<Map<string, boolean>>(new Map());
  const [allOpen, setAllOpen] = useState<boolean | null>(null);

  const { roots, missing } = useMemo(() => buildTree(pack.mods, infos), [pack.mods, infos]);
  const userMods = pack.mods.filter((m) => !BUILTIN.has(m.name));
  // Image par défaut : celle du modpack le plus haut dans l'arbre (racine, le plus grand en cas d'égalité).
  const cover = useMemo(() => {
    if (safeUrl(pack.image)) return pack.image;
    const packs = roots.filter((n) => isModpack(n.info) && n.info?.thumbnail).sort((a, b) => b.total - a.total);
    return packs[0]?.info?.thumbnail
      || pack.mods.map((m) => infos.get(m.name)).find((i) => isModpack(i) && i?.thumbnail)?.thumbnail || "";
  }, [pack.image, pack.mods, roots, infos]);

  const isOpen = (path: string, n: TreeNode) =>
    toggled.get(path) ?? allOpen ?? isModpack(n.info);
  const toggle = (path: string, n: TreeNode) => setToggled(new Map(toggled).set(path, !isOpen(path, n)));
  const setAll = (open: boolean) => { setAllOpen(open); setToggled(new Map()); };

  const f = filter.trim().toLowerCase();
  const flat = useMemo(() => {
    const list = [...pack.mods].sort((a, b) => builtinRank(a.name) - builtinRank(b.name)).filter((m) => {
      if (!f) return true;
      const i = infos.get(m.name);
      return m.name.toLowerCase().includes(f) || (i?.title ?? "").toLowerCase().includes(f) || (i?.owner ?? "").toLowerCase().includes(f);
    });
    return list.map((m) => ({ mod: m, info: infos.get(m.name), children: [], total: 0 }) as TreeNode);
  }, [pack.mods, infos, f]);

  const download = () => {
    const blob = new Blob([modListJson(pack)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "mod-list.json";
    a.click();
    URL.revokeObjectURL(a.href);
  };
  const plainList = pack.mods.map((m) => (m.version ? `${m.name} ${m.version}` : m.name)).join("\n");

  const renderNode = (n: TreeNode, path: string, depth: number, link?: SoftLink) => {
    const open = n.children.length > 0 && isOpen(path, n);
    return (
      <li key={path}>
        <ModRow node={n} depth={depth} relation={link} open={open} onToggle={n.children.length ? () => toggle(path, n) : undefined} />
        {open && (
          <ul className="ml-[18px] border-l border-zinc-200 pl-2 dark:border-zinc-800">
            {n.children.map((c) => renderNode(c, `${path}/${c.mod.name}`, depth + 1, n.soft?.get(c.mod.name)))}
          </ul>
        )}
      </li>
    );
  };

  return (
    <div className="space-y-4">
      {cover && (
        <div className="overflow-hidden border border-zinc-300 bg-zinc-100 dark:border-zinc-800 dark:bg-zinc-900">
          <img src={cover} alt={title ?? ""} className="mx-auto max-h-80 w-full object-contain" referrerPolicy="no-referrer" />
        </div>
      )}

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
        <span className="inline-flex items-center gap-1.5 font-medium"><Package className="size-4 text-zinc-400" />{t("modpack.mods", { count: userMods.length })}</span>
        {pack.factorioVersion && <span className="text-zinc-500">{gameLabel(game)} {pack.factorioVersion}</span>}
        {pack.mods.filter((m) => BUILTIN.has(m.name) && m.name !== "base").map((m) => (
          <span key={m.name} className="rounded-sm bg-amber-500/15 px-1.5 py-px text-xs text-amber-700 dark:text-amber-400">{BUILTIN_TITLES[m.name]}</span>
        ))}
        {loading && <span className="inline-flex items-center gap-1.5 text-xs text-zinc-500"><Spinner className="size-3.5" />{t("modpack.loading")}</span>}
        <div className="ml-auto flex gap-1">
          <Button size="sm" variant="ghost" onClick={() => copy(plainList, t("modpack.listCopied"))} title={t("modpack.copyList")}><Copy className="size-4" /></Button>
          {factorio && <Button size="sm" onClick={download} title={t("modpack.modListHint")}><FileJson className="size-4" />{t("modpack.modList")}</Button>}
        </div>
      </div>

      {pack.readme.trim() && <div className="max-w-4xl"><MarkdownView content={pack.readme} /></div>}

      {missing.length > 0 && (
        <div className="flex items-start gap-2 border border-orange-500/40 px-3 py-2 text-sm text-orange-600 dark:text-orange-400">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" />
          <div><span className="font-medium">{t("modpack.missingTitle")} :</span>{" "}
            {missing.map((n, i) => <span key={n}>{i > 0 && ", "}<a href={portalUrl(n)} target="_blank" rel="noopener noreferrer" className="underline">{n}</a></span>)}
          </div>
        </div>
      )}

      {pack.mods.length === 0 ? (
        <p className="py-8 text-center text-sm text-zinc-500">{t("modpack.noMods")}</p>
      ) : (
        <div className="border border-zinc-300 dark:border-zinc-800">
          <div className="flex flex-wrap items-center gap-2 border-b border-zinc-300 p-2 dark:border-zinc-800">
            <div className="relative min-w-48 flex-1">
              <Search className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-zinc-400" />
              <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t("modpack.search")} className="h-9 pl-8" />
            </div>
            <div className="flex">
              <Button size="sm" variant={mode === "tree" && !f ? "secondary" : "ghost"} onClick={() => setMode("tree")} title={t("modpack.tree")}><ListTree className="size-4" /></Button>
              <Button size="sm" variant={mode === "flat" || f ? "secondary" : "ghost"} onClick={() => setMode("flat")} title={t("modpack.flat")}><List className="size-4" /></Button>
            </div>
            {mode === "tree" && !f && (
              <>
                <Button size="sm" variant="ghost" onClick={() => setAll(true)}>{t("modpack.expandAll")}</Button>
                <Button size="sm" variant="ghost" onClick={() => setAll(false)}>{t("modpack.collapseAll")}</Button>
              </>
            )}
          </div>
          <ul className="p-1">
            {mode === "tree" && !f
              ? roots.map((n) => renderNode(n, n.mod.name, 0))
              : flat.map((n) => <li key={n.mod.name}><ModRow node={n} depth={0} /></li>)}
          </ul>
        </div>
      )}
    </div>
  );
}

type SoftLink = "optional" | "recommended";

function ModRow({ node, depth, relation, open, onToggle }: { node: TreeNode; depth: number; relation?: SoftLink; open?: boolean; onToggle?: () => void }) {
  const { t } = useTranslation();
  const { info } = node;
  // Lien saisi par l'auteur : ignoré s'il n'est pas en http(s).
  const mod = node.mod.url && !safeUrl(node.mod.url) ? { ...node.mod, url: undefined } : node.mod;
  const builtin = BUILTIN.has(mod.name);
  const pack = isModpack(info);
  const title = builtin ? BUILTIN_TITLES[mod.name] : info?.title || mod.name;
  const version = mod.version || info?.version || "";
  const newer = mod.version && info?.latestVersion && cmpVersion(info.latestVersion, mod.version) > 0 ? info.latestVersion : null;
  const link = mod.url || (!builtin && info?.found !== false ? portalUrl(mod.name) : null);
  const dl = !builtin && info?.found ? info.downloadUrl : null;

  return (
    <div className={clsx("group flex items-center gap-2 rounded px-1.5 py-1.5 hover:bg-zinc-100 dark:hover:bg-zinc-900", node.missing && "opacity-70")}>
      <button
        type="button"
        onClick={onToggle}
        disabled={!onToggle}
        className={clsx("flex size-4 shrink-0 items-center justify-center text-zinc-400", !onToggle && "invisible")}
        aria-label={open ? "collapse" : "expand"}
      >
        <ChevronRight className={clsx("size-4 transition-transform", open && "rotate-90")} />
      </button>
      {info?.thumbnail ? (
        <img src={info.thumbnail} alt="" loading="lazy" referrerPolicy="no-referrer" className={clsx("shrink-0 rounded-sm object-cover", relation && "opacity-60", depth === 0 ? "size-10" : "size-8")} />
      ) : (
        <span className={clsx("flex shrink-0 items-center justify-center rounded-sm bg-zinc-200 text-zinc-400 dark:bg-zinc-800", relation && "opacity-60", depth === 0 ? "size-10" : "size-8")}>
          <Package className="size-4" />
        </span>
      )}
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
          {link ? (
            <a href={link} target="_blank" rel="noopener noreferrer" className="truncate font-medium hover:underline">{title}</a>
          ) : (
            <span className="truncate font-medium">{title}</span>
          )}
          {title !== mod.name && <span className="font-mono text-xs text-zinc-400">{mod.name}</span>}
          {version && <span className="rounded-sm bg-zinc-200 px-1 font-mono text-[11px] text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300">{version}</span>}
          {newer && <span className="text-[11px] text-emerald-600 dark:text-emerald-400">{t("modpack.newer", { v: newer })}</span>}
          {builtin && <Badge className="bg-amber-500/15 text-amber-700 dark:text-amber-400">{t("modpack.official")}</Badge>}
          {pack && <Badge className="bg-sky-500/15 text-sky-700 dark:text-sky-400">{t("modpack.modpack")}</Badge>}
          {relation && (
            <Badge className={clsx("border border-dashed", relation === "recommended"
              ? "border-violet-500/50 text-violet-700 dark:text-violet-400"
              : "border-zinc-400/60 text-zinc-500")}>{t(`modpack.${relation}`)}</Badge>
          )}
          {node.missing && <Badge className="bg-orange-500/15 text-orange-700 dark:text-orange-400">{t("modpack.missing")}</Badge>}
          {!builtin && !node.missing && info && !info.found && !mod.url && <Badge className="bg-zinc-500/15 text-zinc-500">{t("modpack.notFound")}</Badge>}
          {node.children.length > 0 && <span className="text-xs text-zinc-400">{t("modpack.contains", { count: node.total })}</span>}
        </div>
        {(info?.summary || mod.note || info?.owner) && (
          <p className="truncate text-xs text-zinc-500">
            {mod.note || info?.summary}
            {info?.owner && <span className="text-zinc-400">{(mod.note || info?.summary) ? " · " : ""}{t("modpack.by", { owner: info.owner })}</span>}
          </p>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-0.5 opacity-60 group-hover:opacity-100">
        {dl && (
          <a href={dl} target="_blank" rel="noopener noreferrer" title={info?.version ? t("modpack.download", { v: info.version }) : t("modpack.downloadLatest")} className={iconBtn}>
            <Download className="size-4" />
          </a>
        )}
        {mod.url && !dl && (
          <a href={mod.url} target="_blank" rel="noopener noreferrer nofollow" title={mod.url} className={iconBtn}><Download className="size-4" /></a>
        )}
        {link && !mod.url && (
          <a href={link} target="_blank" rel="noopener noreferrer" title={t("modpack.page")} className={iconBtn}><ExternalLink className="size-4" /></a>
        )}
      </div>
    </div>
  );
}

const iconBtn = "inline-flex size-8 items-center justify-center rounded text-zinc-500 hover:bg-zinc-200 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-zinc-100";

function Badge({ className, children }: { className: string; children: React.ReactNode }) {
  return <span className={clsx("rounded-sm px-1.5 py-px text-[11px] font-medium", className)}>{children}</span>;
}
