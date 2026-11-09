import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Folder as FolderIcon, FolderPlus, MoreHorizontal, Pencil, Search, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Navigate, useSearchParams } from "react-router";
import { api, type Folder, type Tag } from "../lib/api";
import { TAG_COLORS } from "../lib/format";
import { useMe } from "../lib/session";
import { EditPostModal } from "../components/EditPostModal";
import { FolderTree, TagTree, folderPath, PostGrid, PostRow, DRAG_POST } from "../components/Organize";
import { Button, EmptyState, Input, Modal, PageSpinner, Select, Spinner, TagChip, useToast } from "../components/ui";

const COLORS = Object.keys(TAG_COLORS);

export function Dashboard() {
  const { data: me, isLoading } = useMe();
  if (isLoading) return <PageSpinner />;
  if (!me) return <Navigate to="/login?redirect=/dashboard" replace />;
  return <DashboardInner />;
}

function DashboardInner() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const folderParam = params.get("folder");
  const folder: number | "none" | null = folderParam === "none" ? "none" : folderParam ? Number(folderParam) : null;
  const tags = params.getAll("tag");
  const q = params.get("q") ?? "";
  const kind = params.get("kind") ?? "";
  const visibility = params.get("visibility") ?? "";
  const sort = params.get("sort") ?? "updated";
  const page = Number(params.get("page") ?? 1);
  const [search, setSearch] = useState(q);

  useEffect(() => { document.title = `${t("nav.dashboard")} · Texto`; }, [t]);

  const update = (patch: Record<string, string | string[] | null>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      next.delete(k);
      if (Array.isArray(v)) v.forEach((x) => next.append(k, x));
      else if (v) next.set(k, v);
    }
    if (!("page" in patch)) next.delete("page");
    setParams(next, { replace: true });
  };

  // Recherche avec un léger délai.
  useEffect(() => {
    const id = setTimeout(() => { if (search !== q) update({ q: search || null }); }, 300);
    return () => clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  const folders = useQuery({ queryKey: ["folders"], queryFn: api.folders });
  const allTags = useQuery({ queryKey: ["tags"], queryFn: api.tags });
  const posts = useQuery({
    queryKey: ["posts", { folder, tags, q, kind, visibility, sort, page }],
    queryFn: () => api.myPosts({
      folder: folder ?? undefined, tag: tags, q: q || undefined, kind: kind || undefined,
      visibility: visibility || undefined, sort, page, pageSize: 30,
      // Une recherche dans un dossier inclut ses sous-dossiers.
      recursive: Boolean(q) && typeof folder === "number",
    }),
    placeholderData: keepPreviousData,
  });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["posts"] });
    qc.invalidateQueries({ queryKey: ["folders"] });
    qc.invalidateQueries({ queryKey: ["tags"] });
  };

  const movePost = useMutation({
    mutationFn: ({ key, folderId }: { key: string; folderId: number | null }) =>
      api.updatePost(key, folderId == null ? { clearFolder: true } : { folderId }),
    onSuccess: () => { invalidate(); toast(t("dashboard.moved")); },
  });
  const moveFolder = useMutation({
    mutationFn: ({ id, parentId }: { id: number; parentId: number | null }) =>
      api.updateFolder(id, parentId == null ? { moveToRoot: true } : { parentId }),
    onSuccess: invalidate,
    onError: () => toast(t("errors.folder_cycle"), "error"),
  });

  const [editing, setEditing] = useState<string | null>(null);
  const [folderModal, setFolderModal] = useState<{ mode: "create" | "rename"; folder?: Folder } | null>(null);
  const [deleteFolder, setDeleteFolder] = useState<Folder | null>(null);
  const [tagModal, setTagModal] = useState<Tag | null>(null);

  const list = folders.data ?? [];
  const crumbs = typeof folder === "number" ? folderPath(list, folder) : [];
  const subfolders = useMemo(() => list.filter((f) => (typeof folder === "number" ? f.parentId === folder : folder === null && f.parentId === null)), [list, folder]);
  const currentFolder = typeof folder === "number" ? list.find((f) => f.id === folder) : undefined;
  const totalPages = posts.data ? Math.max(1, Math.ceil(posts.data.total / posts.data.pageSize)) : 1;

  return (
    <div className="grid gap-6 lg:grid-cols-[250px_1fr]">
      <aside className="space-y-6">
        <section>
          <div className="mb-1 flex items-center justify-between px-2">
            <h2 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">{t("dashboard.folders")}</h2>
            <button onClick={() => setFolderModal({ mode: "create" })} title={t("dashboard.newFolder")} className="rounded p-1 text-zinc-400 hover:bg-zinc-100 hover:text-zinc-700 dark:hover:bg-zinc-800"><FolderPlus className="size-4" /></button>
          </div>
          <FolderTree
            folders={list}
            selected={folder}
            onSelect={(id) => update({ folder: id == null ? null : String(id) })}
            onDropPost={(key, folderId) => movePost.mutate({ key, folderId })}
            onDropFolder={(id, parentId) => moveFolder.mutate({ id, parentId })}
            extra={(f) => <FolderMenu onRename={() => setFolderModal({ mode: "rename", folder: f })} onDelete={() => setDeleteFolder(f)} />}
          />
        </section>
        <section>
          <h2 className="mb-2 px-2 text-xs font-semibold uppercase tracking-wide text-zinc-500">{t("dashboard.tags")}</h2>
          <div className="flex flex-wrap gap-1.5 px-2">
            <TagTree
              tags={allTags.data ?? []}
              isActive={(n) => tags.some((x) => x.toLowerCase() === n.toLowerCase())}
              onToggle={(n) => {
                const has = tags.some((x) => x.toLowerCase() === n.toLowerCase());
                update({ tag: has ? tags.filter((x) => x.toLowerCase() !== n.toLowerCase()) : [...tags, n] });
              }}
              wrap={(tag, chip) => <span className="group relative" onDoubleClick={() => setTagModal(tag)}>{chip}</span>}
            />
            {allTags.data?.length === 0 && <p className="text-xs text-zinc-500">{t("dashboard.noTags")}</p>}
          </div>
        </section>
      </aside>

      <section className="min-w-0 space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative min-w-60 flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-zinc-400" />
            <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t("dashboard.searchPlaceholder")} className="pl-9" />
          </div>
          <Select value={kind} onChange={(e) => update({ kind: e.target.value || null })} className="w-auto">
            <option value="">{t("dashboard.allKinds")}</option>
            {["code", "markdown", "text", "factorio"].map((k) => <option key={k} value={k}>{t(`kind.${k}`)}</option>)}
          </Select>
          <Select value={visibility} onChange={(e) => update({ visibility: e.target.value || null })} className="w-auto">
            <option value="">{t("dashboard.allVisibilities")}</option>
            {["public", "unlisted", "private"].map((v) => <option key={v} value={v}>{t(`visibility.${v}`)}</option>)}
          </Select>
          <Select value={sort} onChange={(e) => update({ sort: e.target.value })} className="w-auto">
            {["updated", "created", "title", "views"].map((s) => <option key={s} value={s}>{t(`dashboard.sort.${s}`)}</option>)}
          </Select>
        </div>

        <div className="flex flex-wrap items-center gap-1 text-sm">
          <button onClick={() => update({ folder: null })} className="rounded px-1.5 py-0.5 font-medium text-zinc-500 hover:bg-zinc-100 dark:hover:bg-zinc-800">{t("dashboard.allPosts")}</button>
          {folder === "none" && <><ChevronRight className="size-3.5 text-zinc-400" /><span className="px-1.5 font-medium">{t("dashboard.unfiled")}</span></>}
          {crumbs.map((c) => (
            <span key={c.id} className="flex items-center gap-1">
              <ChevronRight className="size-3.5 text-zinc-400" />
              <button onClick={() => update({ folder: String(c.id) })} className="rounded px-1.5 py-0.5 font-medium hover:bg-zinc-100 dark:hover:bg-zinc-800">{c.name}</button>
            </span>
          ))}
          {tags.length > 0 && <span className="ml-2 flex gap-1">{tags.map((x) => <TagChip key={x} name={x} color={allTags.data?.find((a) => a.name.toLowerCase() === x.toLowerCase())?.color ?? "slate"} onRemove={() => update({ tag: tags.filter((y) => y !== x) })} />)}</span>}
          {posts.isFetching && <Spinner className="ml-2 size-4" />}
        </div>

        {!q && subfolders.length > 0 && (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(170px,1fr))] gap-2">
            {subfolders.map((f) => (
              <FolderCard key={f.id} folder={f} onOpen={() => update({ folder: String(f.id) })} onDropPost={(key) => movePost.mutate({ key, folderId: f.id })} />
            ))}
          </div>
        )}

        {posts.isLoading ? <PageSpinner /> : posts.data && posts.data.items.length > 0 ? (
          <div className="space-y-2">
            <p className="text-xs text-zinc-500">{t("dashboard.results", { count: posts.data.total })}{currentFolder && ` · ${currentFolder.name}`}</p>
            <PostGrid>
              {posts.data.items.map((p) => (
                <PostRow key={p.key} p={p} draggable onEdit={setEditing} onTagClick={(name) => !tags.includes(name) && update({ tag: [...tags, name] })} />
              ))}
            </PostGrid>
          </div>
        ) : (
          <EmptyState icon={<FolderIcon className="size-12" />} title={q || tags.length ? t("dashboard.noResults") : t("dashboard.empty")} />
        )}

        {totalPages > 1 && (
          <div className="flex items-center justify-center gap-2">
            <Button size="sm" disabled={page <= 1} onClick={() => update({ page: String(page - 1) })}><ChevronLeft className="size-4" /></Button>
            <span className="text-sm text-zinc-500">{page} / {totalPages}</span>
            <Button size="sm" disabled={page >= totalPages} onClick={() => update({ page: String(page + 1) })}><ChevronRight className="size-4" /></Button>
          </div>
        )}
      </section>

      <EditPostModal postKey={editing} onClose={() => setEditing(null)} />
      <FolderModal
        state={folderModal}
        parentId={typeof folder === "number" ? folder : null}
        onClose={() => setFolderModal(null)}
        onDone={() => { setFolderModal(null); invalidate(); }}
      />
      <Modal
        open={deleteFolder != null}
        onClose={() => setDeleteFolder(null)}
        title={t("dashboard.deleteFolder")}
        footer={<>
          <Button onClick={() => setDeleteFolder(null)}>{t("common.cancel")}</Button>
          <Button variant="danger" onClick={async () => {
            await api.deleteFolder(deleteFolder!.id);
            if (folder === deleteFolder!.id) update({ folder: null });
            setDeleteFolder(null);
            invalidate();
          }}>{t("common.delete")}</Button>
        </>}
      >
        <p className="text-sm">{t("dashboard.deleteFolderConfirm", { name: deleteFolder?.name })}</p>
      </Modal>
      <TagModal tag={tagModal} onClose={() => setTagModal(null)} onDone={() => { setTagModal(null); invalidate(); }} />
    </div>
  );
}

function FolderMenu({ onRename, onDelete }: { onRename: () => void; onDelete: () => void }) {
  const [open, setOpen] = useState(false);
  const { t } = useTranslation();
  return (
    <span className="relative">
      <button onClick={() => setOpen(!open)} onBlur={() => setTimeout(() => setOpen(false), 150)} className="invisible rounded p-0.5 text-zinc-400 hover:text-zinc-700 group-hover:visible">
        <MoreHorizontal className="size-4" />
      </button>
      {open && (
        <span className="absolute right-0 z-30 mt-1 block w-36 rounded border border-zinc-200 bg-white p-1 text-sm shadow dark:border-zinc-700 dark:bg-zinc-900">
          <button onMouseDown={onRename} className="flex w-full items-center gap-2 rounded px-2 py-1 hover:bg-zinc-100 dark:hover:bg-zinc-800"><Pencil className="size-3.5" />{t("common.rename")}</button>
          <button onMouseDown={onDelete} className="flex w-full items-center gap-2 rounded px-2 py-1 text-red-600 hover:bg-zinc-100 dark:hover:bg-zinc-800"><Trash2 className="size-3.5" />{t("common.delete")}</button>
        </span>
      )}
    </span>
  );
}

function FolderCard({ folder, onOpen, onDropPost }: { folder: Folder; onOpen: () => void; onDropPost: (key: string) => void }) {
  const [over, setOver] = useState(false);
  return (
    <button
      onClick={onOpen}
      onDragOver={(e) => { if (e.dataTransfer.types.includes(DRAG_POST)) { e.preventDefault(); setOver(true); } }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => { e.preventDefault(); setOver(false); const k = e.dataTransfer.getData(DRAG_POST); if (k) onDropPost(k); }}
      className={`flex items-center gap-2 rounded-md border px-3 py-2.5 text-left text-sm transition hover:border-zinc-400 ${over ? "border-zinc-400 ring-2 ring-zinc-500/30" : "border-zinc-200 dark:border-zinc-800"}`}
    >
      <FolderIcon className="size-5 text-zinc-400" />
      <span className="min-w-0 flex-1 truncate font-medium">{folder.name}</span>
      <span className="text-xs text-zinc-400">{folder.postCount || ""}</span>
    </button>
  );
}

function FolderModal({ state, parentId, onClose, onDone }: { state: { mode: "create" | "rename"; folder?: Folder } | null; parentId: number | null; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [name, setName] = useState("");
  useEffect(() => setName(state?.folder?.name ?? ""), [state]);
  const submit = async () => {
    try {
      if (state?.mode === "rename" && state.folder) await api.updateFolder(state.folder.id, { name });
      else await api.createFolder(name, parentId);
      onDone();
    } catch {
      toast(t("errors.folder_exists"), "error");
    }
  };
  return (
    <Modal open={state != null} onClose={onClose} title={state?.mode === "rename" ? t("common.rename") : t("dashboard.newFolder")}
      footer={<><Button onClick={onClose}>{t("common.cancel")}</Button><Button variant="primary" disabled={!name.trim()} onClick={submit}>{t("common.save")}</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); if (name.trim()) submit(); }}>
        <Input autoFocus value={name} onChange={(e) => setName(e.target.value)} maxLength={100} />
      </form>
    </Modal>
  );
}

function TagModal({ tag, onClose, onDone }: { tag: Tag | null; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [name, setName] = useState("");
  const [color, setColor] = useState("slate");
  useEffect(() => { setName(tag?.name ?? ""); setColor(tag?.color ?? "slate"); }, [tag]);
  return (
    <Modal open={tag != null} onClose={onClose} title={t("dashboard.editTag")}
      footer={<>
        <Button variant="ghost" className="mr-auto text-red-600" onClick={async () => { await api.deleteTag(tag!.id); onDone(); }}><Trash2 className="size-4" />{t("common.delete")}</Button>
        <Button onClick={onClose}>{t("common.cancel")}</Button>
        <Button variant="primary" onClick={async () => {
          try { await api.updateTag(tag!.id, name, color); onDone(); } catch { toast(t("errors.tag_exists"), "error"); }
        }}>{t("common.save")}</Button>
      </>}>
      <div className="space-y-3">
        <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={50} />
        <div className="flex flex-wrap gap-1.5">
          {COLORS.map((c) => (
            <button key={c} onClick={() => setColor(c)} className={`rounded-md ${color === c ? "ring-2 ring-zinc-400 ring-offset-1 dark:ring-offset-zinc-900" : ""}`}>
              <TagChip name={name || "tag"} color={c} />
            </button>
          ))}
        </div>
      </div>
    </Modal>
  );
}
