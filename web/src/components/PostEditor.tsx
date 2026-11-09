import clsx from "clsx";
import { Save, SlidersHorizontal } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, type Kind, type Post, type PostInput, type Visibility } from "../lib/api";
import { detectLanguage, LANGUAGES } from "../lib/languages";
import { useConfig } from "../lib/session";
import { FbeFrame } from "../factorio/FbeFrame";
import { FactorioView, useFbeAvailable } from "../factorio/FactorioView";
import { looksLikeBlueprint } from "../factorio/model";
import { buildTree, TagInput, type FolderNode } from "./Organize";
import { Button, Input, Label, Select, Textarea, useToast } from "./ui";
import { CodeEditor, MarkdownView } from "./Viewers";

const EXPIRATIONS: { key: string; seconds: number }[] = [
  { key: "never", seconds: 0 }, { key: "1h", seconds: 3600 }, { key: "1d", seconds: 86400 },
  { key: "1w", seconds: 604800 }, { key: "1m", seconds: 2592000 }, { key: "1y", seconds: 31536000 },
];

export function PostEditor({ existing }: { existing?: Post }) {
  const { t } = useTranslation();
  const nav = useNavigate();
  const qc = useQueryClient();
  const toast = useToast();
  const { data: cfg } = useConfig();
  const s = existing?.summary;
  const [title, setTitle] = useState(s?.title ?? "");
  const [description, setDescription] = useState(s?.description ?? "");
  const [kind, setKind] = useState<Kind>(s?.kind ?? "code");
  const [language, setLanguage] = useState<string>(s?.language ?? "");
  const [content, setContent] = useState(existing?.content ?? "");
  const [visibility, setVisibility] = useState<Visibility>(s?.visibility ?? "public");
  const [folderId, setFolderId] = useState<number | null>(s?.folderId ?? null);
  const [tags, setTags] = useState<string[]>(s?.tags.map((x) => x.name) ?? []);
  const [expiration, setExpiration] = useState<string>(existing ? "keep" : "never");
  const [passwordMode, setPasswordMode] = useState<"keep" | "none" | "set">(s?.hasPassword ? "keep" : "none");
  const [password, setPassword] = useState("");
  const [burn, setBurn] = useState<number>(s?.burnAfterViews ?? 0);
  const [mdTab, setMdTab] = useState<"write" | "preview">("write");
  const [saving, setSaving] = useState(false);
  const [showOptions, setShowOptions] = useState(false);
  // FBE intégré : on peut dessiner un plan vanilla de zéro ; les exports (Ctrl+C) remplissent le contenu.
  const fbeVanilla = useFbeAvailable(content);
  const fbeEditing = kind === "factorio" && Boolean(cfg?.fbeUrl) && (!content.trim() || fbeVanilla);

  const { data: folders = [] } = useQuery({ queryKey: ["folders"], queryFn: api.folders });
  const { data: allTags = [] } = useQuery({ queryKey: ["tags"], queryFn: api.tags });
  const detected = useMemo(() => (kind === "code" && !language ? detectLanguage(content) : null), [kind, language, content]);

  // Coller un plan Factorio bascule automatiquement le type.
  useEffect(() => {
    if (!existing && kind !== "factorio" && looksLikeBlueprint(content)) setKind("factorio");
  }, [content, kind, existing]);

  const save = useCallback(async () => {
    if (!content.trim()) { toast(t("editor.emptyContent"), "error"); return; }
    if (cfg && content.length > cfg.maxContentLength) { toast(t("editor.tooLarge"), "error"); return; }
    if (passwordMode === "set" && !password) { toast(t("editor.passwordRequired"), "error"); return; }
    setSaving(true);
    const input: PostInput = {
      title: title.trim() || undefined,
      description,
      kind,
      language: kind === "code" ? (language || detected || "") : "",
      content,
      visibility,
      tags,
      burnAfterViews: burn,
      ...(folderId != null ? { folderId } : { clearFolder: true }),
    };
    if (expiration !== "keep") input.expiresIn = EXPIRATIONS.find((e) => e.key === expiration)?.seconds ?? 0;
    if (passwordMode === "set") input.password = password;
    if (passwordMode === "none" && s?.hasPassword) input.password = "";
    if (!existing && passwordMode === "none") delete input.password;
    try {
      const saved = existing ? await api.updatePost(existing.summary.key, input) : await api.createPost(input);
      qc.invalidateQueries({ queryKey: ["posts"] });
      qc.invalidateQueries({ queryKey: ["post", saved.key] });
      qc.invalidateQueries({ queryKey: ["tags"] });
      qc.invalidateQueries({ queryKey: ["folders"] });
      toast(existing ? t("editor.updated") : t("editor.created"));
      nav(`/${saved.key}`);
    } catch (e) {
      toast(e instanceof ApiError ? t(`errors.${e.code}`, { defaultValue: e.message }) : String(e), "error");
    } finally {
      setSaving(false);
    }
  }, [title, description, kind, language, detected, content, visibility, tags, burn, folderId, expiration, passwordMode, password, existing, s, cfg, nav, qc, toast, t]);

  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") { e.preventDefault(); save(); }
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [save]);

  const folderOptions = useMemo(() => {
    const out: { id: number; label: string }[] = [];
    const walk = (nodes: FolderNode[], depth: number) => nodes.forEach((n) => { out.push({ id: n.id, label: "  ".repeat(depth) + n.name }); walk(n.children, depth + 1); });
    walk(buildTree(folders), 0);
    return out;
  }, [folders]);

  const kinds: Kind[] = ["code", "markdown", "text", "factorio"];
  const bare = "h-8 rounded-none border-0 border-b border-transparent bg-transparent px-1 focus:border-zinc-500 focus:ring-0 dark:bg-transparent";

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2 border-b border-zinc-300 pb-2 dark:border-zinc-800">
        <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder={t("editor.titlePlaceholder")} className={clsx(bare, "min-w-48 flex-1 font-bold")} maxLength={200} />
        <Select value={kind} onChange={(e) => setKind(e.target.value as Kind)} className="h-8 w-auto">
          {kinds.map((k) => <option key={k} value={k}>{t(`kind.${k}`)}</option>)}
        </Select>
        {kind === "code" && (
          <Select value={language} onChange={(e) => setLanguage(e.target.value)} className="h-8 w-auto">
            <option value="">{detected ? `auto (${detected})` : "auto"}</option>
            {LANGUAGES.map((l) => <option key={l} value={l}>{l}</option>)}
          </Select>
        )}
        {kind === "markdown" && (
          <Button size="sm" variant="ghost" onClick={() => setMdTab(mdTab === "write" ? "preview" : "write")}>{t(`editor.${mdTab === "write" ? "preview" : "write"}`)}</Button>
        )}
        <Select value={visibility} onChange={(e) => setVisibility(e.target.value as Visibility)} className="h-8 w-auto">
          {(["public", "unlisted", "private"] as const).map((v) => <option key={v} value={v}>{t(`visibility.${v}`)}</option>)}
        </Select>
        <Button size="sm" variant={showOptions ? "secondary" : "ghost"} onClick={() => setShowOptions(!showOptions)} title={t("editor.options")}><SlidersHorizontal className="size-4" /></Button>
        <Button size="sm" variant="primary" onClick={save} loading={saving} title="Ctrl+S"><Save className="size-4" />{existing ? t("editor.save") : t("editor.publish")}</Button>
      </div>

      {showOptions && (
        <div className="grid gap-3 border-b border-zinc-300 pb-3 sm:grid-cols-2 lg:grid-cols-3 dark:border-zinc-800">
          <label className="block sm:col-span-2 lg:col-span-3">
            <Label>{t("editor.description")}</Label>
            <Input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={10000} />
          </label>
          <label className="block">
            <Label>{t("editor.folder")}</Label>
            <Select value={folderId ?? ""} onChange={(e) => setFolderId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">—</option>
              {folderOptions.map((f) => <option key={f.id} value={f.id}>{f.label}</option>)}
            </Select>
          </label>
          <div className="lg:col-span-2">
            <Label>{t("editor.tags")}</Label>
            <TagInput value={tags} onChange={setTags} suggestions={allTags} />
          </div>
          <label className="block">
            <Label>{t("editor.expiration")}</Label>
            <Select value={expiration} onChange={(e) => setExpiration(e.target.value)}>
              {existing && <option value="keep">{s?.expiresAt ? new Date(s.expiresAt).toLocaleString() : t("expiration.never")}</option>}
              {EXPIRATIONS.map((x) => <option key={x.key} value={x.key}>{t(`expiration.${x.key}`)}</option>)}
            </Select>
          </label>
          <div>
            <Label>{t("editor.password")}</Label>
            <div className="flex gap-2">
              <Select value={passwordMode} onChange={(e) => setPasswordMode(e.target.value as typeof passwordMode)} className={passwordMode === "set" ? "w-28" : undefined}>
                {s?.hasPassword && <option value="keep">{t("editor.passwordKeep")}</option>}
                <option value="none">{t("editor.passwordNone")}</option>
                <option value="set">{t("editor.passwordSet")}</option>
              </Select>
              {passwordMode === "set" && <Input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} className="flex-1" />}
            </div>
          </div>
          <label className="block">
            <Label>{t("editor.burn")}</Label>
            <Input type="number" min={0} max={1000} value={burn} onChange={(e) => setBurn(Math.max(0, Number(e.target.value) || 0))} />
          </label>
        </div>
      )}

      {kind === "markdown" && mdTab === "preview" ? (
        <div className="min-h-[75vh] p-2"><MarkdownView content={content} /></div>
      ) : kind === "factorio" ? (
        <div className="space-y-3">
          <Textarea value={content} onChange={(e) => setContent(e.target.value.trim())} rows={3} placeholder="0eNq…" className="font-mono text-xs break-all" autoFocus={!existing} />
          {fbeEditing ? <div className="relative" style={{ height: "calc(100vh - 150px)", minHeight: 420 }}><FbeFrame content={content} onExport={setContent} /></div> : content && <FactorioView content={content} compact />}
        </div>
      ) : (
        <CodeEditor value={content} onChange={setContent} language={kind === "code" ? (language || detected) : kind === "markdown" ? "Markdown" : null} wrap={kind !== "code"} autoFocus={!existing} minHeight="calc(100vh - 140px)" bare />
      )}
    </div>
  );
}
