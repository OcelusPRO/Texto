import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, GitMerge, Link2, Search, ShieldCheck, Trash2, UserCog } from "lucide-react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Navigate, useNavigate } from "react-router";
import { api, ApiError, type AdminUser } from "../lib/api";
import { bytes, relativeTime } from "../lib/format";
import { useMe } from "../lib/session";
import { PostRow } from "../components/Organize";
import { ProviderIcon } from "./UserPages";
import { Avatar, Button, Card, Input, Modal, PageSpinner, Select, useToast } from "../components/ui";

export function AdminPage() {
  const { data: me, isLoading } = useMe();
  if (isLoading) return <PageSpinner />;
  if (me?.role !== "admin") return <Navigate to="/" replace />;
  return <AdminInner myId={me.id} />;
}

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <Card className="p-4">
      <div className="text-xs text-zinc-500">{label}</div>
      <div className="mt-1 text-2xl font-bold tabular-nums">{value}</div>
    </Card>
  );
}

function AdminInner({ myId }: { myId: number }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const nav = useNavigate();
  const [tab, setTab] = useState<"users" | "posts">("users");
  const [q, setQ] = useState("");
  const [debounced, setDebounced] = useState("");
  const [merge, setMerge] = useState<AdminUser | null>(null);
  const [remove, setRemove] = useState<AdminUser | null>(null);
  const [linkUserId, setLinkUserId] = useState<number | null>(null);
  const [linkProvider, setLinkProvider] = useState("discord");
  const [linkId, setLinkId] = useState("");
  const [target, setTarget] = useState("");
  useEffect(() => { const id = setTimeout(() => setDebounced(q), 300); return () => clearTimeout(id); }, [q]);
  useEffect(() => { document.title = `${t("nav.admin")} · Texto`; }, [t]);

  const stats = useQuery({ queryKey: ["admin", "stats"], queryFn: api.admin.stats });
  const users = useQuery({ queryKey: ["admin", "users", debounced], queryFn: () => api.admin.users(debounced), enabled: tab === "users", placeholderData: keepPreviousData });
  const posts = useQuery({ queryKey: ["admin", "posts", debounced], queryFn: () => api.admin.posts(debounced), enabled: tab === "posts", placeholderData: keepPreviousData });
  const targets = useQuery({ queryKey: ["admin", "users", "all"], queryFn: () => api.admin.users(undefined, 1), enabled: merge != null });
  const settings = useQuery({ queryKey: ["admin", "settings"], queryFn: api.admin.settings });
  const linkUser = users.data?.items.find((u) => u.id === linkUserId);
  const refresh = () => qc.invalidateQueries({ queryKey: ["admin"] });

  const s = stats.data;
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("nav.admin")}</h1>
      {s ? (
        <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
          <Stat label={t("admin.users")} value={s.users} />
          <Stat label={t("admin.posts")} value={s.posts} />
          <Stat label={t("admin.views")} value={s.views.toLocaleString()} />
          <Stat label={t("admin.storage")} value={bytes(s.storageBytes)} />
          <Stat label={t("admin.unclaimed")} value={s.legacyUsersUnclaimed} />
        </div>
      ) : <PageSpinner />}
      {settings.data && (
        <label className="flex cursor-pointer items-start gap-3 border border-zinc-300 p-3 dark:border-zinc-800">
          <input
            type="checkbox"
            className="mt-1 size-4 accent-zinc-500"
            checked={settings.data.allowNewUsers}
            onChange={async (e) => { await api.admin.setSettings({ allowNewUsers: e.target.checked }); qc.invalidateQueries({ queryKey: ["admin", "settings"] }); qc.invalidateQueries({ queryKey: ["config"] }); }}
          />
          <span>
            <span className="block text-sm font-medium">{t("admin.registrations")}</span>
            <span className="block text-xs text-zinc-500">{t("admin.registrationsHint")}</span>
          </span>
        </label>
      )}
      {s && <p className="text-xs text-zinc-500">{Object.entries(s.postsByKind).map(([k, v]) => `${t(`kind.${k}`)} : ${v}`).join(" · ")}</p>}

      <div className="flex flex-wrap items-center gap-2">
        <div className="inline-flex rounded border border-zinc-200 p-0.5 dark:border-zinc-700">
          {(["users", "posts"] as const).map((x) => (
            <button key={x} onClick={() => setTab(x)} className={`rounded-md px-3 py-1 text-sm ${tab === x ? "bg-zinc-800 text-zinc-100 dark:bg-zinc-200 dark:text-zinc-900" : "text-zinc-600 dark:text-zinc-300"}`}>{t(`admin.${x}`)}</button>
          ))}
        </div>
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-zinc-400" />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("admin.search")} className="pl-9" />
        </div>
      </div>

      {tab === "users" ? (
        <Card className="divide-y divide-zinc-100 dark:divide-zinc-800">
          {users.data?.items.map((u) => (
            <div key={u.id} className="flex flex-wrap items-center gap-3 px-4 py-2.5">
              <Avatar src={u.avatarUrl} name={u.displayName} size={32} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 text-sm font-medium">
                  <a href={`/u/${u.handle}`} className="hover:underline">{u.displayName}</a>
                  <span className="text-zinc-500">@{u.handle}</span>
                  {u.role === "admin" && <ShieldCheck className="size-4 text-zinc-400" />}
                  {u.banned && <span className="rounded bg-red-100 px-1.5 text-xs text-red-700 dark:bg-red-950 dark:text-red-300">{t("admin.banned")}</span>}
                  {u.legacy && u.providers.length === 0 && <span className="rounded bg-amber-100 px-1.5 text-xs text-amber-800 dark:bg-amber-950 dark:text-amber-300">{t("admin.legacyUnclaimed")}</span>}
                </div>
                <div className="text-xs text-zinc-500">
                  {u.posts} posts · {u.providers.join(", ") || "—"} · {u.lastLoginAt ? t("admin.lastLogin", { when: relativeTime(u.lastLoginAt) }) : t("admin.neverLogged")}
                </div>
              </div>
              {u.id !== myId && (
                <div className="flex gap-1">
                  <Button size="sm" variant="ghost" title={t("admin.accounts")} onClick={() => { setLinkUserId(u.id); setLinkId(""); }}><Link2 className="size-4" /></Button>
                  {!u.banned && <Button size="sm" variant="ghost" title={t("admin.impersonate")} onClick={async () => { await api.admin.impersonate(u.id); qc.clear(); nav("/dashboard"); }}><UserCog className="size-4" /></Button>}
                  <Button size="sm" variant="ghost" title={t("admin.merge")} onClick={() => { setMerge(u); setTarget(""); }}><GitMerge className="size-4" /></Button>
                  <Button size="sm" variant="ghost" title={u.role === "admin" ? t("admin.demote") : t("admin.promote")} onClick={async () => { await api.admin.patchUser(u.id, { role: u.role === "admin" ? "user" : "admin" }); refresh(); }}><ShieldCheck className="size-4" /></Button>
                  <Button size="sm" variant="ghost" title={u.banned ? t("admin.unban") : t("admin.ban")} onClick={async () => { await api.admin.patchUser(u.id, { banned: !u.banned }); refresh(); }}><Ban className={`size-4 ${u.banned ? "text-red-500" : ""}`} /></Button>
                  <Button size="sm" variant="ghost" title={t("admin.delete")} onClick={() => setRemove(u)}><Trash2 className="size-4 text-red-500" /></Button>
                </div>
              )}
            </div>
          ))}
        </Card>
      ) : (
        <Card className="p-2">
          {posts.data?.items.map((p) => (
            <div key={p.key} className="flex items-center">
              <div className="min-w-0 flex-1"><PostRow p={p} showAuthor /></div>
              <Button size="sm" variant="ghost" onClick={async () => {
                if (!confirm(t("post.deleteConfirm", { title: p.title }))) return;
                await api.deletePost(p.key); refresh(); toast(t("post.deleted"));
              }}><Trash2 className="size-4 text-red-500" /></Button>
            </div>
          ))}
        </Card>
      )}

      <Modal open={linkUserId != null} onClose={() => setLinkUserId(null)} title={t("admin.accountsTitle", { name: linkUser?.displayName ?? "" })}>
        <div className="space-y-4 text-sm">
          <ul className="space-y-1.5">
            {linkUser?.accounts.map((a) => (
              <li key={a.provider} className="flex items-center gap-2">
                <ProviderIcon provider={a.provider} className="size-4 shrink-0 text-zinc-500" />
                <span className="min-w-0 flex-1 truncate"><code>{a.providerUserId}</code> <span className="text-zinc-500">· {a.username}</span></span>
                <Button size="sm" variant="ghost" onClick={async () => { await api.admin.unlinkAccount(linkUser.id, a.provider); refresh(); toast(t("admin.unlinked")); }}>{t("admin.unlink")}</Button>
              </li>
            ))}
            {linkUser?.accounts.length === 0 && <li className="text-zinc-500">{t("admin.noAccounts")}</li>}
          </ul>
          <form
            className="flex flex-wrap gap-2 border-t border-zinc-300 pt-3 dark:border-zinc-800"
            onSubmit={async (e) => {
              e.preventDefault();
              try {
                await api.admin.linkAccount(linkUser!.id, linkProvider, linkId.trim());
                setLinkId(""); refresh(); toast(t("admin.linked"));
              } catch (err) {
                toast(err instanceof ApiError ? t(`errors.${err.code}`, { defaultValue: err.code, handle: err.message }) : String(err), "error");
              }
            }}
          >
            <Select value={linkProvider} onChange={(e) => setLinkProvider(e.target.value)} className="w-32">
              <option value="discord">Discord</option>
              <option value="twitch">Twitch</option>
            </Select>
            <Input value={linkId} onChange={(e) => setLinkId(e.target.value)} placeholder={t("admin.linkPlaceholder")} inputMode="numeric" className="min-w-40 flex-1 font-mono" />
            <Button type="submit" variant="primary" disabled={!linkId.trim()}>{t("admin.link")}</Button>
          </form>
          <p className="text-xs text-zinc-500">{t("admin.linkHint")}</p>
        </div>
      </Modal>

      <Modal open={remove != null} onClose={() => setRemove(null)} title={t("admin.deleteTitle")}
        footer={<>
          <Button onClick={() => setRemove(null)}>{t("common.cancel")}</Button>
          <Button variant="danger" onClick={async () => {
            await api.admin.deleteUser(remove!.id); setRemove(null); refresh(); toast(t("admin.deleted"));
          }}>{t("admin.delete")}</Button>
        </>}>
        <p className="text-sm">{t("admin.deleteHint", { name: remove?.displayName })}</p>
      </Modal>

      <Modal open={merge != null} onClose={() => setMerge(null)} title={t("admin.mergeTitle")}
        footer={<>
          <Button onClick={() => setMerge(null)}>{t("common.cancel")}</Button>
          <Button variant="danger" disabled={!target} onClick={async () => {
            await api.admin.merge(merge!.id, Number(target)); setMerge(null); refresh(); toast(t("admin.merged"));
          }}>{t("admin.merge")}</Button>
        </>}>
        <p className="mb-3 text-sm">{t("admin.mergeHint", { name: merge?.displayName })}</p>
        <Select value={target} onChange={(e) => setTarget(e.target.value)}>
          <option value="">—</option>
          {targets.data?.items.filter((u) => u.id !== merge?.id).map((u) => <option key={u.id} value={u.id}>{u.displayName} (@{u.handle})</option>)}
        </Select>
      </Modal>
    </div>
  );
}
