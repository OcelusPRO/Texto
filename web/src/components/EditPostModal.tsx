import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, ApiError, type Kind, type Post, type PostInput, type Visibility } from "../lib/api";
import { LANGUAGES } from "../lib/languages";
import { buildTree, TagInput, type FolderNode } from "./Organize";
import { Button, Input, Label, Modal, PageSpinner, Select, Textarea, useToast } from "./ui";

const EXPIRATIONS: { key: string; seconds: number }[] = [
  { key: "never", seconds: 0 }, { key: "1h", seconds: 3600 }, { key: "1d", seconds: 86400 },
  { key: "1w", seconds: 604800 }, { key: "1m", seconds: 2592000 }, { key: "1y", seconds: 31536000 },
];

/** Édition rapide d'un post (métadonnées + contenu) sans ouvrir la page ni afficher le rendu. */
export function EditPostModal({ postKey, onClose }: { postKey: string | null; onClose: () => void }) {
  const { t } = useTranslation();
  const post = useQuery({ queryKey: ["post-edit", postKey], queryFn: () => api.post(postKey!), enabled: postKey != null, retry: false, refetchOnWindowFocus: false, gcTime: 0 });
  return (
    <Modal open={postKey != null} onClose={onClose} title={t("editor.quickEdit")} wide>
      {post.isLoading && <PageSpinner />}
      {post.error && <p className="text-sm text-red-600">{t("errors.not_found", { defaultValue: "Introuvable" })}</p>}
      {post.data && (post.data.isOwner
        ? <Form key={postKey} post={post.data} onClose={onClose} />
        : <p className="text-sm text-red-600">{t("errors.forbidden", { defaultValue: "Interdit" })}</p>)}
    </Modal>
  );
}

function Form({ post, onClose }: { post: Post; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const toast = useToast();
  const s = post.summary;
  const [title, setTitle] = useState(s.title);
  const [description, setDescription] = useState(s.description);
  const [kind, setKind] = useState<Kind>(s.kind);
  const [language, setLanguage] = useState(s.language ?? "");
  const [visibility, setVisibility] = useState<Visibility>(s.visibility);
  const [folderId, setFolderId] = useState<number | null>(s.folderId);
  const [tags, setTags] = useState<string[]>(s.tags.map((x) => x.name));
  const [expiration, setExpiration] = useState("keep");
  const [passwordMode, setPasswordMode] = useState<"keep" | "none" | "set">(s.hasPassword ? "keep" : "none");
  const [password, setPassword] = useState("");
  const [burn, setBurn] = useState(s.burnAfterViews ?? 0);
  const [content, setContent] = useState(post.content);
  const [saving, setSaving] = useState(false);
  const { data: folders = [] } = useQuery({ queryKey: ["folders"], queryFn: api.folders });
  const { data: allTags = [] } = useQuery({ queryKey: ["tags"], queryFn: api.tags });
  const folderOptions = useMemo(() => {
    const out: { id: number; label: string }[] = [];
    const walk = (nodes: FolderNode[], depth: number) => nodes.forEach((n) => { out.push({ id: n.id, label: "  ".repeat(depth) + n.name }); walk(n.children, depth + 1); });
    walk(buildTree(folders), 0);
    return out;
  }, [folders]);

  const save = async () => {
    if (!content.trim()) { toast(t("editor.emptyContent"), "error"); return; }
    if (passwordMode === "set" && !password) { toast(t("editor.passwordRequired"), "error"); return; }
    setSaving(true);
    const input: PostInput = {
      title: title.trim() || undefined, description, kind,
      language: kind === "code" ? language : "",
      visibility, tags, burnAfterViews: burn,
      ...(folderId != null ? { folderId } : { clearFolder: true }),
    };
    if (content !== post.content) input.content = content;
    if (expiration !== "keep") input.expiresIn = EXPIRATIONS.find((e) => e.key === expiration)?.seconds ?? 0;
    if (passwordMode === "set") input.password = password;
    if (passwordMode === "none" && s.hasPassword) input.password = "";
    try {
      await api.updatePost(s.key, input);
      for (const k of ["posts", "user-posts", "user-folders", "tags", "folders"]) qc.invalidateQueries({ queryKey: [k] });
      qc.invalidateQueries({ queryKey: ["post", s.key] });
      toast(t("editor.updated"));
      onClose();
    } catch (e) {
      toast(e instanceof ApiError ? t(`errors.${e.code}`, { defaultValue: e.message }) : String(e), "error");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-3 text-sm">
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="block sm:col-span-2"><Label>{t("editor.titleLabel")}</Label><Input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} /></label>
        <label className="block sm:col-span-2"><Label>{t("editor.description")}</Label><Input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={10000} /></label>
        <div className="sm:col-span-2"><Label>{t("editor.tags")}</Label><TagInput value={tags} onChange={setTags} suggestions={allTags} /></div>
        <label className="block"><Label>{t("editor.folder")}</Label>
          <Select value={folderId ?? ""} onChange={(e) => setFolderId(e.target.value ? Number(e.target.value) : null)}>
            <option value="">—</option>
            {folderOptions.map((f) => <option key={f.id} value={f.id}>{f.label}</option>)}
          </Select>
        </label>
        <label className="block"><Label>{t("editor.visibility")}</Label>
          <Select value={visibility} onChange={(e) => setVisibility(e.target.value as Visibility)}>
            {(["public", "unlisted", "private"] as const).map((v) => <option key={v} value={v}>{t(`visibility.${v}`)}</option>)}
          </Select>
        </label>
        <label className="block"><Label>{t("editor.kind")}</Label>
          <Select value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
            {(["code", "markdown", "text", "factorio"] as Kind[]).map((k) => <option key={k} value={k}>{t(`kind.${k}`)}</option>)}
          </Select>
        </label>
        {kind === "code" && (
          <label className="block"><Label>{t("editor.language")}</Label>
            <Select value={language} onChange={(e) => setLanguage(e.target.value)}>
              <option value="">auto</option>
              {LANGUAGES.map((l) => <option key={l} value={l}>{l}</option>)}
            </Select>
          </label>
        )}
        <label className="block"><Label>{t("editor.expiration")}</Label>
          <Select value={expiration} onChange={(e) => setExpiration(e.target.value)}>
            <option value="keep">{s.expiresAt ? new Date(s.expiresAt).toLocaleString() : t("expiration.never")}</option>
            {EXPIRATIONS.map((x) => <option key={x.key} value={x.key}>{t(`expiration.${x.key}`)}</option>)}
          </Select>
        </label>
        <div><Label>{t("editor.password")}</Label>
          <div className="flex gap-2">
            <Select value={passwordMode} onChange={(e) => setPasswordMode(e.target.value as typeof passwordMode)} className={passwordMode === "set" ? "w-28" : undefined}>
              {s.hasPassword && <option value="keep">{t("editor.passwordKeep")}</option>}
              <option value="none">{t("editor.passwordNone")}</option>
              <option value="set">{t("editor.passwordSet")}</option>
            </Select>
            {passwordMode === "set" && <Input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} className="flex-1" />}
          </div>
        </div>
        <label className="block"><Label>{t("editor.burn")}</Label>
          <Input type="number" min={0} max={1000} value={burn} onChange={(e) => setBurn(Math.max(0, Number(e.target.value) || 0))} />
        </label>
      </div>
      <div><Label>{t("editor.content")}</Label>
        <Textarea value={content} onChange={(e) => setContent(kind === "factorio" ? e.target.value.trim() : e.target.value)} rows={12} spellCheck={false} className="max-h-[40vh] font-mono text-xs" />
      </div>
      <div className="flex justify-end gap-2 border-t border-zinc-200 pt-3 dark:border-zinc-800">
        <Button onClick={onClose}>{t("editor.cancel")}</Button>
        <Button variant="primary" onClick={save} loading={saving}>{t("editor.save")}</Button>
      </div>
    </div>
  );
}
