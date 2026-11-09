import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, RotateCcw } from "lucide-react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, Navigate, useNavigate, useParams } from "react-router";
import { api, type ApiError, type Post } from "../lib/api";
import { bytes, fullDate, relativeTime } from "../lib/format";
import { useMe } from "../lib/session";
import { PostEditor } from "../components/PostEditor";
import { Button, PageSpinner, Select, useToast } from "../components/ui";
import { DiffView } from "../components/Viewers";
import { NotFound } from "./NotFound";

export function NewPostPage() {
  const { data: me, isLoading } = useMe();
  const { t } = useTranslation();
  useEffect(() => { document.title = `${t("nav.new")} · Texto`; }, [t]);
  if (isLoading) return <PageSpinner />;
  if (!me) return <Navigate to="/login?redirect=/new" replace />;
  return <PostEditor />;
}

export function EditPostPage() {
  const { key = "" } = useParams();
  const { data, isLoading, error } = useQuery<Post, ApiError>({
    queryKey: ["post-edit", key],
    queryFn: () => api.post(key),
    retry: false,
    refetchOnWindowFocus: false,
  });
  if (isLoading) return <PageSpinner />;
  if (error || !data || !data.isOwner) return <NotFound />;
  return <PostEditor existing={data} />;
}

export function HistoryPage() {
  const { key = "" } = useParams();
  const { t } = useTranslation();
  const toast = useToast();
  const nav = useNavigate();
  const qc = useQueryClient();
  const revs = useQuery({ queryKey: ["revisions", key], queryFn: () => api.revisions(key), retry: false });
  const [a, setA] = useState<number | null>(null);
  const [b, setB] = useState<number | null>(null);

  useEffect(() => {
    if (revs.data?.length) {
      setB((x) => x ?? revs.data[0].number);
      setA((x) => x ?? (revs.data[1]?.number ?? revs.data[0].number));
    }
  }, [revs.data]);

  const ra = useQuery({ queryKey: ["revision", key, a], queryFn: () => api.revision(key, a!), enabled: a != null });
  const rb = useQuery({ queryKey: ["revision", key, b], queryFn: () => api.revision(key, b!), enabled: b != null });

  if (revs.isLoading) return <PageSpinner />;
  if (revs.error || !revs.data) return <NotFound />;
  const latest = revs.data[0]?.number;

  return (
    <div className="space-y-5">
      <Link to={`/${key}`} className="inline-flex items-center gap-1 text-sm text-zinc-500 hover:text-zinc-900 dark:hover:text-white"><ArrowLeft className="size-4" />{t("history.back")}</Link>
      <h1 className="text-2xl font-bold">{t("history.title")}</h1>
      <div className="grid gap-6 lg:grid-cols-[280px_1fr]">
        <ol className="space-y-1">
          {revs.data.map((r) => (
            <li key={r.number} className="flex items-center gap-2 rounded px-3 py-2 text-sm hover:bg-zinc-50 dark:hover:bg-zinc-900">
              <span className="font-mono font-semibold">v{r.number}</span>
              <span className="min-w-0 flex-1">
                <span className="block truncate">{r.title}</span>
                <span className="text-xs text-zinc-500" title={fullDate(r.createdAt)}>{relativeTime(r.createdAt)} · {bytes(r.size)}</span>
              </span>
              {r.number !== latest && (
                <Button size="sm" variant="ghost" title={t("history.restore")} onClick={async () => {
                  await api.restore(key, r.number);
                  qc.invalidateQueries({ queryKey: ["post", key] });
                  qc.invalidateQueries({ queryKey: ["revisions", key] });
                  toast(t("history.restored", { n: r.number }));
                  nav(`/${key}`);
                }}><RotateCcw className="size-4" /></Button>
              )}
            </li>
          ))}
        </ol>
        <div className="min-w-0 space-y-3">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <Select value={a ?? ""} onChange={(e) => setA(Number(e.target.value))} className="w-auto">
              {revs.data.map((r) => <option key={r.number} value={r.number}>v{r.number}</option>)}
            </Select>
            →
            <Select value={b ?? ""} onChange={(e) => setB(Number(e.target.value))} className="w-auto">
              {revs.data.map((r) => <option key={r.number} value={r.number}>v{r.number}</option>)}
            </Select>
          </div>
          {ra.data && rb.data ? (
            <>
              {ra.data.title !== rb.data.title && <p className="text-sm">{t("history.titleChanged")} <del className="text-red-600">{ra.data.title}</del> → <ins className="text-emerald-600">{rb.data.title}</ins></p>}
              <DiffView before={ra.data.content} after={rb.data.content} />
            </>
          ) : <PageSpinner />}
        </div>
      </div>
    </div>
  );
}
