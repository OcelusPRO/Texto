import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Download, Eye, FileText, Flame, History, Link2, Lock, Pencil, Trash2, WrapText } from "lucide-react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate, useParams } from "react-router";
import { api, ApiError, type Post } from "../lib/api";
import { fullDate, relativeTime } from "../lib/format";
import { useConfig, useMe } from "../lib/session";
import { FactorioView } from "../factorio/FactorioView";
import { VisibilityIcon } from "../components/Organize";
import { Avatar, Button, Card, Input, Modal, PageSpinner, TagChip, useCopy, useToast } from "../components/ui";
import { CodeEditor, MarkdownView } from "../components/Viewers";
import { NotFound } from "./NotFound";

export function PostPage() {
  const { key = "" } = useParams();
  const [password, setPassword] = useState<string | undefined>();
  const query = useQuery<Post, ApiError>({
    queryKey: ["post", key, password ?? ""],
    queryFn: () => api.post(key, password),
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });

  useEffect(() => {
    if (query.data) document.title = `${query.data.summary.title} · Texto`;
  }, [query.data]);

  if (query.isLoading) return <PageSpinner />;
  const err = query.error;
  if (err?.code === "password_required" || err?.code === "wrong_password") {
    return <PasswordGate wrong={err.code === "wrong_password"} onSubmit={setPassword} />;
  }
  if (err || !query.data) return <NotFound />;
  return <PostContent post={query.data} password={password} />;
}

function PasswordGate({ wrong, onSubmit }: { wrong: boolean; onSubmit: (p: string) => void }) {
  const { t } = useTranslation();
  const [value, setValue] = useState("");
  return (
    <Card className="mx-auto mt-16 max-w-sm p-6">
      <form onSubmit={(e) => { e.preventDefault(); onSubmit(value); }} className="space-y-3 text-center">
        <Lock className="mx-auto size-8 text-zinc-400" />
        <h1 className="text-lg font-semibold">{t("post.passwordTitle")}</h1>
        <Input type="password" autoFocus value={value} onChange={(e) => setValue(e.target.value)} placeholder={t("post.passwordPlaceholder")} />
        {wrong && <p className="text-sm text-red-600">{t("errors.wrong_password")}</p>}
        <Button variant="primary" className="w-full" type="submit">{t("post.unlock")}</Button>
      </form>
    </Card>
  );
}

function PostContent({ post, password }: { post: Post; password?: string }) {
  const { t } = useTranslation();
  const s = post.summary;
  const copy = useCopy();
  const toast = useToast();
  const nav = useNavigate();
  const qc = useQueryClient();
  const { data: me } = useMe();
  const { data: cfg } = useConfig();
  const [wrap, setWrap] = useState(s.kind !== "code");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const url = `${cfg?.baseUrl ?? location.origin}/${s.key}`;
  const canDelete = post.isOwner || me?.role === "admin";

  const download = () => {
    const ext = s.kind === "markdown" ? "md" : s.kind === "factorio" ? "txt" : extensionFor(s.language);
    const blob = new Blob([post.content], { type: "text/plain;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `${s.title.replace(/[^\w.-]+/g, "_") || s.key}.${ext}`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  return (
    <div className="space-y-3">
      {post.burned && (
        <div className="flex items-center gap-2 border border-orange-500/40 px-3 py-2 text-sm text-orange-500">
          <Flame className="size-4 shrink-0" /> {t("post.burnedNotice")}
        </div>
      )}
      <header className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-zinc-300 pb-2 dark:border-zinc-800">
        <h1 className="min-w-0 truncate font-bold">{s.title}</h1>
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-zinc-500">
          <Link to={`/u/${post.author.handle}`} className="flex items-center gap-1 hover:text-zinc-900 dark:hover:text-zinc-100">
            <Avatar src={post.author.avatarUrl} name={post.author.displayName} size={16} /> {post.author.handle}
          </Link>
          <span title={fullDate(s.updatedAt)}>{relativeTime(s.updatedAt)}</span>
          <span className="inline-flex items-center gap-1"><Eye className="size-3" />{s.views}</span>
          <VisibilityIcon v={s.visibility} />
          {s.language && <span>{s.language.toLowerCase()}</span>}
          {s.expiresAt && <span>{t("post.expires", { when: relativeTime(s.expiresAt) })}</span>}
          {s.burnAfterViews != null && post.isOwner && <span className="inline-flex items-center gap-1"><Flame className="size-3" />{s.burnAfterViews}</span>}
          {s.tags.map((tag) => <TagChip key={tag.id} name={tag.name} color={tag.color} onClick={post.isOwner ? () => nav(`/dashboard?tag=${encodeURIComponent(tag.name)}`) : undefined} />)}
        </div>
        <div className="flex items-center gap-0.5">
          <Button size="sm" variant="ghost" onClick={() => copy(url, t("post.linkCopied"))} title={t("post.copyLink")}><Link2 className="size-4" /></Button>
          <Button size="sm" variant="ghost" onClick={() => copy(post.content, t("post.contentCopied"))} title={t("post.copy")}><Copy className="size-4" /></Button>
          {!password && !s.hasPassword && s.burnAfterViews == null && (
            <a href={`/raw/${s.key}`} title="raw" className={ghost}><FileText className="size-4" /></a>
          )}
          <Button size="sm" variant="ghost" onClick={download} title={t("post.download")}><Download className="size-4" /></Button>
          {s.kind === "code" && <Button size="sm" variant={wrap ? "secondary" : "ghost"} onClick={() => setWrap(!wrap)} title={t("post.wrap")}><WrapText className="size-4" /></Button>}
          {post.isOwner && (
            <>
              <Link to={`/${s.key}/history`} title={t("history.title")} className={ghost}><History className="size-4" /><span className="ml-1 text-xs">{s.revision}</span></Link>
              <Link to={`/${s.key}/edit`} title={t("post.edit")} className={ghost}><Pencil className="size-4" /></Link>
            </>
          )}
          {canDelete && <Button size="sm" variant="ghost" onClick={() => setConfirmDelete(true)} title={t("post.delete")}><Trash2 className="size-4" /></Button>}
        </div>
      </header>
      {s.description && <p className="whitespace-pre-wrap text-sm text-zinc-500">{s.description}</p>}

      {s.kind === "factorio" ? (
        <FactorioView
          content={post.content}
          onSave={post.isOwner ? async (full) => {
            await api.updatePost(s.key, { content: full });
            await qc.invalidateQueries({ queryKey: ["post", s.key] });
            toast(t("editor.updated"));
          } : undefined}
          thumbnail={post.isOwner ? {
            missing: !s.hasThumbnail,
            upload: async (png) => {
              await api.uploadThumbnail(s.key, png);
              await qc.invalidateQueries({ queryKey: ["post", s.key] });
            },
          } : undefined}
        />
      ) : s.kind === "markdown" ? (
        <div className="max-w-4xl"><MarkdownView content={post.content} /></div>
      ) : (
        <CodeEditor value={post.content} language={s.kind === "code" ? s.language : null} readOnly minHeight="calc(100vh - 130px)" wrap={wrap} bare />
      )}

      <Modal
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        title={t("post.deleteTitle")}
        footer={
          <>
            <Button onClick={() => setConfirmDelete(false)}>{t("common.cancel")}</Button>
            <Button variant="danger" onClick={async () => {
              await api.deletePost(s.key);
              qc.invalidateQueries({ queryKey: ["posts"] });
              toast(t("post.deleted"));
              nav("/dashboard");
            }}>{t("post.delete")}</Button>
          </>
        }
      >
        <p className="text-sm">{t("post.deleteConfirm", { title: s.title })}</p>
      </Modal>
    </div>
  );
}

const ghost = "inline-flex h-8 items-center rounded px-2 text-zinc-500 hover:bg-zinc-200 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-zinc-100";

function extensionFor(lang: string | null): string {
  const m: Record<string, string> = {
    Kotlin: "kt", Java: "java", JavaScript: "js", TypeScript: "ts", Python: "py", Rust: "rs", Go: "go", "C++": "cpp", C: "c",
    "C#": "cs", JSON: "json", YAML: "yml", HTML: "html", CSS: "css", SQL: "sql", Shell: "sh", Bash: "sh", PowerShell: "ps1",
    Lua: "lua", PHP: "php", Ruby: "rb", XML: "xml", TOML: "toml", Markdown: "md", Dockerfile: "Dockerfile", Swift: "swift",
  };
  return (lang && m[lang]) || "txt";
}
