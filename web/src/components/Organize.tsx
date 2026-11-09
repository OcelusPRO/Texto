import clsx from "clsx";
import { ChevronRight, Eye, FileCode2, FileText, Flame, Folder as FolderIcon, FolderOpen, Globe, Link2, Lock, NotebookText, Factory, Pencil, Timer } from "lucide-react";
import { useMemo, useState, type DragEvent, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router";
import type { Folder, PostSummary, Tag } from "../lib/api";
import { bytes, relativeTime } from "../lib/format";
import { TagChip } from "./ui";

// ---------------------------------------------------------------- saisie de tags

/** Tags de version automatiques (« factorio/2.0 ») : gérés par Texto, ni ajoutés ni retirés à la main. */
export const isVersionTag = (name: string) => /^factorio\/\d+\.\d+$/i.test(name.trim());

export function TagInput({ value, onChange, suggestions }: { value: string[]; onChange: (v: string[]) => void; suggestions: Tag[] }) {
  const { t } = useTranslation();
  const [text, setText] = useState("");
  const colorOf = (name: string) => suggestions.find((s) => s.name.toLowerCase() === name.toLowerCase())?.color ?? "slate";
  const add = (raw: string) => {
    // Accepte aussi une liste collée : "a, b c" -> trois tags.
    const next = [...value];
    for (const part of raw.split(/[,\s]+/)) {
      const n = part.trim().replace(/^#/, "");
      if (n && !isVersionTag(n) && !next.some((v) => v.toLowerCase() === n.toLowerCase())) next.push(n);
    }
    if (next.length !== value.length) onChange(next);
    setText("");
  };
  const matches = text
    ? suggestions.filter((s) => s.name.toLowerCase().includes(text.toLowerCase()) && !value.some((v) => v.toLowerCase() === s.name.toLowerCase())).slice(0, 6)
    : [];
  return (
    <div className="relative">
      <div className="flex min-h-9 flex-wrap items-center gap-1 rounded border border-zinc-200 bg-white px-2 py-1 focus-within:border-zinc-500 focus-within:ring-2 focus-within:ring-zinc-500/20 dark:border-zinc-700 dark:bg-zinc-900">
        {value.map((v) => <TagChip key={v} name={v} color={colorOf(v)} onRemove={isVersionTag(v) ? undefined : () => onChange(value.filter((x) => x !== v))} />)}
        <input
          value={text}
          onChange={(e) => (/[,\s]/.test(e.target.value) ? add(e.target.value) : setText(e.target.value))}
          onKeyDown={(e) => {
            if ((e.key === "Enter" || e.key === "," || e.key === " ") && text.trim()) { e.preventDefault(); add(text); }
            if (e.key === "Backspace" && !text && value.length && !isVersionTag(value[value.length - 1])) onChange(value.slice(0, -1));
          }}
          onBlur={() => text.trim() && add(text)}
          placeholder={value.length ? "" : t("editor.tagsPlaceholder")}
          className="min-w-24 flex-1 bg-transparent py-0.5 text-sm outline-none"
        />
      </div>
      {matches.length > 0 && (
        <div className="absolute z-20 mt-1 w-full rounded border border-zinc-200 bg-white p-1 shadow dark:border-zinc-700 dark:bg-zinc-900">
          {matches.map((m) => (
            <button key={m.id} type="button" onMouseDown={(e) => { e.preventDefault(); add(m.name); }} className="flex w-full items-center justify-between rounded px-2 py-1 text-left text-sm hover:bg-zinc-100 dark:hover:bg-zinc-800">
              <TagChip name={m.name} color={m.color} /> <span className="text-xs text-zinc-400">{m.postCount}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- arbre de dossiers

export interface FolderNode extends Folder { children: FolderNode[] }

export function buildTree(folders: Folder[]): FolderNode[] {
  const map = new Map<number, FolderNode>(folders.map((f) => [f.id, { ...f, children: [] }]));
  const roots: FolderNode[] = [];
  for (const n of map.values()) {
    const parent = n.parentId != null ? map.get(n.parentId) : undefined;
    (parent ? parent.children : roots).push(n);
  }
  const sort = (l: FolderNode[]) => { l.sort((a, b) => a.name.localeCompare(b.name)); l.forEach((c) => sort(c.children)); };
  sort(roots);
  return roots;
}

export function folderPath(folders: Folder[], id: number | null): Folder[] {
  const byId = new Map(folders.map((f) => [f.id, f]));
  const out: Folder[] = [];
  let cur = id != null ? byId.get(id) : undefined;
  while (cur) { out.unshift(cur); cur = cur.parentId != null ? byId.get(cur.parentId) : undefined; }
  return out;
}

export const DRAG_POST = "application/x-texto-post";
export const DRAG_FOLDER = "application/x-texto-folder";

export function FolderTree({
  folders, selected, onSelect, onDropPost, onDropFolder, extra,
}: {
  folders: Folder[]; selected: number | "none" | null; onSelect: (id: number | "none" | null) => void;
  onDropPost: (postKey: string, folderId: number | null) => void; onDropFolder: (folderId: number, parentId: number | null) => void;
  extra?: (f: Folder) => ReactNode;
}) {
  const { t } = useTranslation();
  const tree = useMemo(() => buildTree(folders), [folders]);
  const [open, setOpen] = useState<Set<number>>(() => new Set());
  const [over, setOver] = useState<number | "root" | null>(null);

  const dropHandlers = (target: number | null) => ({
    onDragOver: (e: DragEvent) => {
      if (e.dataTransfer.types.includes(DRAG_POST) || e.dataTransfer.types.includes(DRAG_FOLDER)) { e.preventDefault(); setOver(target ?? "root"); }
    },
    onDragLeave: () => setOver(null),
    onDrop: (e: DragEvent) => {
      e.preventDefault();
      setOver(null);
      const post = e.dataTransfer.getData(DRAG_POST);
      const folder = e.dataTransfer.getData(DRAG_FOLDER);
      if (post) onDropPost(post, target);
      else if (folder && Number(folder) !== target) onDropFolder(Number(folder), target);
    },
  });

  const row = (active: boolean, isOver: boolean) =>
    clsx("group flex w-full items-center gap-1.5 rounded px-2 py-1.5 text-left text-sm",
      active ? "bg-zinc-200 font-medium text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100" : "text-zinc-700 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800",
      isOver && "ring-2 ring-zinc-400");

  const renderNode = (n: FolderNode, depth: number): ReactNode => {
    const isOpen = open.has(n.id);
    return (
      <div key={n.id}>
        <div
          draggable
          onDragStart={(e) => e.dataTransfer.setData(DRAG_FOLDER, String(n.id))}
          {...dropHandlers(n.id)}
          className={row(selected === n.id, over === n.id)}
          style={{ paddingLeft: 8 + depth * 14 }}
        >
          <button
            onClick={() => setOpen((s) => { const c = new Set(s); if (c.has(n.id)) c.delete(n.id); else c.add(n.id); return c; })}
            className={clsx("rounded p-0.5 text-zinc-400 hover:text-zinc-700", !n.children.length && "invisible")}
          >
            <ChevronRight className={clsx("size-3.5 transition", isOpen && "rotate-90")} />
          </button>
          <button onClick={() => onSelect(n.id)} className="flex min-w-0 flex-1 items-center gap-1.5">
            {selected === n.id ? <FolderOpen className="size-4 shrink-0 text-zinc-400" /> : <FolderIcon className="size-4 shrink-0 text-zinc-400" />}
            <span className="truncate">{n.name}</span>
            <span className="ml-auto text-xs text-zinc-400">{n.postCount || ""}</span>
          </button>
          {extra?.(n)}
        </div>
        {isOpen && n.children.map((c) => renderNode(c, depth + 1))}
      </div>
    );
  };

  return (
    <div className="space-y-0.5">
      <button onClick={() => onSelect(null)} {...dropHandlers(null)} className={row(selected === null, over === "root")}>
        <NotebookText className="size-4 text-zinc-400" /> {t("dashboard.allPosts")}
      </button>
      <button onClick={() => onSelect("none")} className={row(selected === "none", false)}>
        <FileText className="size-4 text-zinc-400" /> {t("dashboard.unfiled")}
      </button>
      <div className="my-1 border-t border-zinc-200 dark:border-zinc-800" />
      {tree.map((n) => renderNode(n, 0))}
    </div>
  );
}

// ---------------------------------------------------------------- liste de posts

export function KindIcon({ kind, className }: { kind: string; className?: string }) {
  const c = clsx("size-4 shrink-0", className);
  if (kind === "factorio") return <Factory className={clsx(c, "text-zinc-400")} />;
  if (kind === "markdown") return <NotebookText className={clsx(c, "text-zinc-400")} />;
  if (kind === "text") return <FileText className={clsx(c, "text-zinc-400")} />;
  return <FileCode2 className={clsx(c, "text-zinc-400")} />;
}

export function VisibilityIcon({ v }: { v: string }) {
  const { t } = useTranslation();
  if (v === "public") return <span title={t("visibility.public")}><Globe className="size-3.5 text-zinc-400" /></span>;
  if (v === "private") return <span title={t("visibility.private")}><Lock className="size-3.5 text-red-500" /></span>;
  return <span title={t("visibility.unlisted")}><Link2 className="size-3.5 text-zinc-400" /></span>;
}

/** Carte d'un post : toute la zone est cliquable (Ctrl/Cmd+clic : nouvel onglet). */
export function PostRow({ p, draggable, onTagClick, showAuthor, folderLabel, onEdit }: { p: PostSummary; draggable?: boolean; onTagClick?: (t: string) => void; showAuthor?: boolean; folderLabel?: string; onEdit?: (key: string) => void }) {
  const { t } = useTranslation();
  const nav = useNavigate();
  const open = (newTab: boolean) => (newTab ? window.open(`/${p.key}`, "_blank", "noopener") : nav(`/${p.key}`));
  const stop = (e: { stopPropagation: () => void }) => e.stopPropagation();
  return (
    <div
      role="link"
      tabIndex={0}
      draggable={draggable}
      onDragStart={(e) => { e.dataTransfer.setData(DRAG_POST, p.key); e.dataTransfer.effectAllowed = "move"; }}
      onClick={(e) => { if (!window.getSelection()?.toString()) open(e.ctrlKey || e.metaKey); }}
      onAuxClick={(e) => { if (e.button === 1) open(true); }}
      onKeyDown={(e) => { if (e.key === "Enter") open(e.ctrlKey || e.metaKey); }}
      className="group flex cursor-pointer items-start gap-3 border border-zinc-300 px-3 py-2.5 hover:bg-zinc-200/60 focus-visible:outline-2 focus-visible:outline-zinc-400 dark:border-zinc-800 dark:hover:bg-zinc-800/60"
    >
      <KindIcon kind={p.kind} className="mt-0.5" />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate font-medium group-hover:underline">{p.title}</span>
          <VisibilityIcon v={p.visibility} />
          {p.hasPassword && <span title={t("post.passwordProtected")}><Lock className="size-3.5 text-zinc-400" /></span>}
          {p.burnAfterViews != null && <span title={t("post.burn", { count: p.burnAfterViews })}><Flame className="size-3.5 text-orange-500" /></span>}
          {p.expiresAt != null && <span title={t("post.expires", { when: relativeTime(p.expiresAt) })}><Timer className="size-3.5 text-zinc-400" /></span>}
          {onEdit && (
            <button type="button" title={t("editor.edit")} onClick={(e) => { stop(e); onEdit(p.key); }} onKeyDown={stop} className="ml-auto shrink-0 rounded p-1 text-zinc-400 hover:bg-zinc-300 hover:text-zinc-900 dark:hover:bg-zinc-700 dark:hover:text-white">
              <Pencil className="size-3.5" />
            </button>
          )}
        </div>
        {p.description && <p className="mt-0.5 line-clamp-3 whitespace-pre-line break-words text-sm text-zinc-500">{p.description}</p>}
        <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-zinc-500">
          {showAuthor && p.author && <Link to={`/u/${p.author.handle}`} onClick={stop} className="hover:underline">@{p.author.handle}</Link>}
          {folderLabel && <span className="inline-flex items-center gap-1"><FolderIcon className="size-3" />{folderLabel}</span>}
          {p.language && <span>{p.language}</span>}
          <span>{relativeTime(p.updatedAt)}</span>
          <span className="inline-flex items-center gap-0.5"><Eye className="size-3" />{p.views}</span>
          <span>{bytes(p.size)}</span>
          {p.revision > 1 && <span>v{p.revision}</span>}
          {p.tags.map((tag) => <TagChip key={tag.id} name={tag.name} color={tag.color} onClick={onTagClick ? () => onTagClick(tag.name) : undefined} />)}
        </div>
      </div>
    </div>
  );
}

/** Liste de posts en deux colonnes (une seule sur petit écran). */
export function PostGrid({ children }: { children: ReactNode }) {
  return <div className="grid items-start gap-2 lg:grid-cols-2">{children}</div>;
}

// ---------------------------------------------------------------- arbre de tags

/** Tags hiérarchiques (« factorio/1.0 ») : la racine regroupe ses sous-tags, qui s'affichent en retrait. */
export function TagTree({ tags, isActive, onToggle, wrap }: {
  tags: Tag[]; isActive: (name: string) => boolean; onToggle: (name: string) => void; wrap?: (tag: Tag, chip: ReactNode) => ReactNode;
}) {
  const lower = (s: string) => s.toLowerCase();
  const byParent = useMemo(() => {
    const names = new Set(tags.map((x) => lower(x.name)));
    const m = new Map<string, Tag[]>();
    for (const tag of tags) {
      const i = tag.name.lastIndexOf("/");
      const parent = i > 0 && names.has(lower(tag.name.slice(0, i))) ? lower(tag.name.slice(0, i)) : "";
      m.set(parent, [...(m.get(parent) ?? []), tag]);
    }
    return m;
  }, [tags]);
  const render = (parent: string, depth: number): ReactNode => {
    const items = byParent.get(parent);
    if (!items?.length) return null;
    return (
      <div className={clsx("flex flex-wrap items-start gap-1.5", depth > 0 && "basis-full border-l border-zinc-300 pl-3 dark:border-zinc-700")}>
        {items.map((tag) => {
          const label = tag.name.slice(tag.name.lastIndexOf("/") + 1);
          const chip = <TagChip name={`${label} ${tag.postCount}`} color={tag.color} active={isActive(tag.name)} onClick={() => onToggle(tag.name)} />;
          const kids = render(lower(tag.name), depth + 1);
          return (
            <div key={tag.id} className={clsx("flex flex-col gap-1.5", kids && "basis-full")}>
              {wrap ? wrap(tag, chip) : chip}
              {kids}
            </div>
          );
        })}
      </div>
    );
  };
  return <>{render("", 0)}</>;
}
