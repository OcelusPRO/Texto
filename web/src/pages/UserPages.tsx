import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, ChevronRight, Copy, Fingerprint, Folder as FolderIcon, KeyRound, Pencil, Plus, Search, Trash2, Unlink } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { Trans, useTranslation } from "react-i18next";
import { Link, Navigate, useLocation, useNavigate, useParams, useSearchParams } from "react-router";
import { api, ApiError, type Social } from "../lib/api";
import { fullDate, relativeTime } from "../lib/format";
import { loginUrl, useConfig, useMe } from "../lib/session";
import { isPasskeyCancel, loginWithPasskey, passkeysSupported, registerPasskey } from "../lib/passkey";
import { EditPostModal } from "../components/EditPostModal";
import { PostGrid, PostRow, TagTree, folderPath } from "../components/Organize";
import { SocialEditor, SocialIcon, socialHandle, socialKey, socialLabel, socialsValid } from "../components/Social";
import { Avatar, Button, EmptyState, Input, Label, Modal, PageSpinner, Select, Textarea, useCopy, useToast } from "../components/ui";
import { NotFound } from "./NotFound";

// ---------------------------------------------------------------- icônes des fournisseurs

export function ProviderIcon({ provider, className = "size-5" }: { provider: string; className?: string }) {
  if (provider === "discord")
    return <svg viewBox="0 0 24 24" className={className} fill="currentColor"><path d="M20.3 4.4A19.8 19.8 0 0 0 15.4 3l-.6 1.3a18.4 18.4 0 0 0-5.5 0L8.6 3a19.7 19.7 0 0 0-4.9 1.5C.6 9.1-.3 13.6.1 18.1A19.9 19.9 0 0 0 6.1 21l1.3-2a13 13 0 0 1-2-1l.5-.4a14.2 14.2 0 0 0 12.2 0l.5.4-2 1 1.3 2a19.8 19.8 0 0 0 6-3c.5-5.2-.8-9.7-3.6-13.7ZM8 15.4c-1.2 0-2.2-1.1-2.2-2.4S6.8 10.6 8 10.6s2.2 1.1 2.2 2.4-1 2.4-2.2 2.4Zm8 0c-1.2 0-2.2-1.1-2.2-2.4s1-2.4 2.2-2.4 2.2 1.1 2.2 2.4-1 2.4-2.2 2.4Z" /></svg>;
  if (provider === "twitch")
    return <svg viewBox="0 0 24 24" className={className} fill="currentColor"><path d="M4.3 2 3 5.4v13.8h4.7V22h2.7l2.6-2.8h3.8l5.2-5.3V2H4.3Zm15.6 11-3 3H12l-2.6 2.7V16H5.7V3.8h14.2V13ZM17 6.9h-1.8v5.3H17V6.9Zm-4.8 0h-1.8v5.3h1.8V6.9Z" /></svg>;
  return <KeyRound className={className} />;
}


// ---------------------------------------------------------------- connexion / accueil

export function LoginPage() {
  const { t } = useTranslation();
  const { data: cfg } = useConfig();
  const { data: me } = useMe();
  const [params] = useSearchParams();
  const redirect = params.get("redirect") ?? "/new";
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [passkeyError, setPasskeyError] = useState<string | null>(null);
  const [passkeyBusy, setPasskeyBusy] = useState(false);
  const error = passkeyError ?? params.get("error");
  if (me) return <Navigate to={redirect} replace />;
  const passkeyLogin = async () => {
    setPasskeyError(null); setPasskeyBusy(true);
    try {
      await loginWithPasskey();
      await qc.invalidateQueries({ queryKey: ["me"] });
      navigate(redirect, { replace: true });
    } catch (e) {
      if (!isPasskeyCancel(e)) setPasskeyError(e instanceof ApiError ? e.code : "passkey_failed");
    } finally { setPasskeyBusy(false); }
  };
  return (
    <div className="mx-auto mt-24 w-full max-w-xs space-y-3">
      <h1 className="mb-6 text-lg font-bold">{(cfg?.siteName ?? "texto").toLowerCase()}</h1>
      {error && <p className="text-sm text-red-500">{t(`errors.${error}`, { defaultValue: error })}</p>}
      {(cfg?.providers ?? []).map((p) => (
        <a key={p} href={loginUrl(p, redirect)} className="flex h-10 items-center gap-3 border border-zinc-300 px-3 text-sm hover:bg-zinc-200 dark:border-zinc-700 dark:hover:bg-zinc-800">
          <ProviderIcon provider={p} className="size-4" /> {t(`providers.${p}`)}
        </a>
      ))}
      {passkeysSupported() && (
        <button type="button" onClick={passkeyLogin} disabled={passkeyBusy} className="flex h-10 w-full items-center gap-3 border border-zinc-300 px-3 text-sm hover:bg-zinc-200 disabled:opacity-50 dark:border-zinc-700 dark:hover:bg-zinc-800">
          <Fingerprint className="size-4" /> {t("login.passkey")}
        </button>
      )}
      {cfg && cfg.providers.length === 0 && <p className="text-sm text-zinc-500">{t("login.noProvider")}</p>}
    </div>
  );
}

/** Pas de page de présentation : la racine mène à l'éditeur ou à la connexion. */
export function HomePage() {
  const { data: me, isLoading } = useMe();
  if (isLoading) return <PageSpinner />;
  return <Navigate to={me ? "/new" : "/login"} replace />;
}

// ---------------------------------------------------------------- profil public

export function ProfilePage() {
  const { handle = "" } = useParams();
  const { t } = useTranslation();
  const nav = useNavigate();
  const [params] = useSearchParams();
  const { pathname } = useLocation();
  // /u/<handle>/<Dossier>/<Sous-dossier> : le chemin du dossier est dans l'URL.
  const segs = pathname.split("/").filter(Boolean).slice(2).map((x) => { try { return decodeURIComponent(x); } catch { return x; } });
  const legacyParam = params.get("folder");
  const { data: me } = useMe();
  const [editing, setEditing] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  const [kind, setKind] = useState("");
  const [sort, setSort] = useState("updated");
  const [tagFilter, setTagFilter] = useState<string[]>([]);
  useEffect(() => { const id = setTimeout(() => { setQ(search.trim()); setPage(1); }, 300); return () => clearTimeout(id); }, [search]);
  const user = useQuery({ queryKey: ["user", handle], queryFn: () => api.user(handle), retry: false });
  const folders = useQuery({ queryKey: ["user-folders", handle], queryFn: () => api.userFolders(handle), enabled: user.isSuccess });
  const userTags = useQuery({ queryKey: ["user-tags", handle], queryFn: () => api.userTags(handle), enabled: user.isSuccess });
  const allFolders = folders.data ?? [];
  let folderId: number | null = null;
  let unknownPath = false;
  for (const name of segs) {
    const hit = allFolders.find((f) => f.parentId === folderId && f.name.toLowerCase() === name.toLowerCase());
    if (!hit) { unknownPath = true; break; }
    folderId = hit.id;
  }
  useEffect(() => { setPage(1); }, [folderId]);
  const pathUrl = (id: number | null) => `/u/${user.data?.handle ?? handle}` + folderPath(allFolders, id).map((c) => "/" + encodeURIComponent(c.name)).join("");
  const posts = useQuery({
    queryKey: ["user-posts", handle, page, q, folderId, kind, sort, tagFilter],
    queryFn: () => api.userPosts(handle, page, q || undefined, folderId ?? undefined, { kind: kind || undefined, sort, tag: tagFilter }),
    enabled: user.isSuccess && folders.isSuccess && !unknownPath,
    placeholderData: keepPreviousData,
    retry: false,
  });
  useEffect(() => { if (user.data) document.title = `${user.data.displayName} · Texto`; }, [user.data]);
  // Ancien identifiant : l'API renvoie le profil, on met l'URL à jour.
  useEffect(() => { if (user.data && user.data.handle !== handle.toLowerCase()) nav(`/u/${user.data.handle}${segs.map((x) => "/" + encodeURIComponent(x)).join("")}`, { replace: true }); }, [user.data, handle, nav, pathname]);
  if (user.isLoading) return <PageSpinner />;
  if (!user.data) return <NotFound />;
  const u = user.data;
  const all = allFolders;
  const crumbs = folderPath(all, folderId);
  const searching = q.length > 0 || kind !== "" || tagFilter.length > 0;
  const canEdit = me != null && me.handle === u.handle;
  if (folders.isSuccess) {
    // Ancien lien ?folder=ID : redirigé vers le chemin.
    if (legacyParam && /^\d+$/.test(legacyParam)) return <Navigate to={pathUrl(all.some((f) => f.id === Number(legacyParam)) ? Number(legacyParam) : null)} replace />;
    // Dossier inconnu ou sans contenu public : on revient à la racine.
    if (unknownPath) return <Navigate to={`/u/${u.handle}`} replace />;
  }
  const subfolders = searching ? [] : all.filter((f) => f.parentId === folderId);
  const labelOf = (id: number | null) => (id == null ? undefined : folderPath(all, id).map((f) => f.name).join(" / ") || undefined);
    const pages = posts.data ? Math.ceil(posts.data.total / posts.data.pageSize) : 1;
  const items = posts.data?.items ?? [];
  return (
    <div className="grid gap-8 md:grid-cols-[240px_1fr]">
      <aside className="space-y-4">
        <Avatar src={u.avatarUrl} name={u.displayName} size={64} />
        <div>
          <h1 className="font-bold">{u.displayName}</h1>
          <p className="text-zinc-500">@{u.handle}</p>
        </div>
        {u.bio && <p className="whitespace-pre-wrap text-sm">{u.bio}</p>}
        <dl className="grid grid-cols-2 gap-2 text-sm">
          <div><dt className="text-xs text-zinc-500">{t("profile.posts")}</dt><dd className="font-semibold">{u.publicPosts}</dd></div>
          <div><dt className="text-xs text-zinc-500">{t("profile.views")}</dt><dd className="font-semibold">{u.totalViews}</dd></div>
        </dl>
        <p className="text-xs text-zinc-500" title={fullDate(u.createdAt)}>{t("profile.since", { when: relativeTime(u.createdAt) })}</p>
        {u.socials.length > 0 && (
          <ul className="space-y-1.5 text-sm">
            {u.socials.map((s) => {
              const key = socialKey(s.icon, s.url);
              return (
                <li key={s.url}>
                  <a href={s.url} target="_blank" rel="noopener noreferrer nofollow" className="inline-flex max-w-full items-center gap-2 text-zinc-600 hover:text-zinc-900 hover:underline dark:text-zinc-300 dark:hover:text-white">
                    <SocialIcon name={key} className="size-4 shrink-0" /><span className="truncate" title={socialLabel(key)}>{socialHandle(key, s.url)}</span>
                  </a>
                </li>
              );
            })}
          </ul>
        )}
      </aside>
      <section className="min-w-0 space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative min-w-48 max-w-md flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-zinc-400" />
            <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t("profile.searchPlaceholder")} className="pl-9" />
          </div>
          <Select value={kind} onChange={(e) => { setKind(e.target.value); setPage(1); }} className="w-auto">
            <option value="">{t("dashboard.allKinds")}</option>
            {["code", "markdown", "text", "factorio"].map((k) => <option key={k} value={k}>{t(`kind.${k}`)}</option>)}
          </Select>
          <Select value={sort} onChange={(e) => { setSort(e.target.value); setPage(1); }} className="w-auto">
            {["updated", "created", "title", "views"].map((s) => <option key={s} value={s}>{t(`dashboard.sort.${s}`)}</option>)}
          </Select>
        </div>
        {(userTags.data?.length ?? 0) > 0 && (
          <TagTree
            tags={userTags.data!}
            isActive={(n) => tagFilter.some((x) => x.toLowerCase() === n.toLowerCase())}
            onToggle={(n) => {
              const has = tagFilter.some((x) => x.toLowerCase() === n.toLowerCase());
              setTagFilter(has ? tagFilter.filter((x) => x.toLowerCase() !== n.toLowerCase()) : [...tagFilter, n]);
              setPage(1);
            }}
          />
        )}

        {!searching && crumbs.length > 0 && (
          <nav className="flex flex-wrap items-center gap-1 text-sm">
            <Link to={pathUrl(null)} className="px-1.5 py-0.5 text-zinc-500 hover:bg-zinc-200 dark:hover:bg-zinc-800">{t("profile.root")}</Link>
            {crumbs.map((c, i) => (
              <span key={c.id} className="flex items-center gap-1">
                <ChevronRight className="size-3.5 text-zinc-400" />
                {i === crumbs.length - 1
                  ? <span className="px-1.5 font-medium">{c.name}</span>
                  : <Link to={pathUrl(c.id)} className="px-1.5 py-0.5 hover:bg-zinc-200 dark:hover:bg-zinc-800">{c.name}</Link>}
              </span>
            ))}
          </nav>
        )}
        {searching && <p className="text-xs text-zinc-500">{t("profile.searchAll", { count: posts.data?.total ?? 0 })}</p>}

        {subfolders.length > 0 && (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(200px,1fr))] gap-2">
            {subfolders.map((f) => (
              <Link key={f.id} to={pathUrl(f.id)} className="flex items-center gap-2 border border-zinc-300 px-3 py-2.5 text-left text-sm hover:bg-zinc-200/60 dark:border-zinc-800 dark:hover:bg-zinc-800/60">
                <FolderIcon className="size-5 shrink-0 text-zinc-400" />
                <span className="min-w-0 flex-1 truncate font-medium">{f.name}</span>
                <span className="text-xs text-zinc-500">{f.postCount}</span>
              </Link>
            ))}
          </div>
        )}

        {items.length > 0 && <PostGrid>{items.map((p) => <PostRow key={p.key} p={p} onTagClick={(n) => { if (!tagFilter.some((x) => x.toLowerCase() === n.toLowerCase())) { setTagFilter([...tagFilter, n]); setPage(1); } }} onEdit={canEdit ? setEditing : undefined} folderLabel={searching ? labelOf(p.folderId) : undefined} />)}</PostGrid>}
        {items.length === 0 && subfolders.length === 0 && !posts.isLoading && (
          <EmptyState title={searching ? t("dashboard.noResults") : t("profile.noPosts")} />
        )}
        {pages > 1 && (
          <div className="flex justify-center gap-2">
            <Button size="sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>‹</Button>
            <span className="text-sm text-zinc-500">{page} / {pages}</span>
            <Button size="sm" disabled={page >= pages} onClick={() => setPage(page + 1)}>›</Button>
          </div>
        )}
      </section>
      <EditPostModal postKey={editing} onClose={() => setEditing(null)} />
    </div>
  );
}

// ---------------------------------------------------------------- paramètres

export function SettingsPage() {
  const { t } = useTranslation();
  const { data: me, isLoading } = useMe();
  if (isLoading) return <PageSpinner />;
  if (!me) return <Navigate to="/login?redirect=/settings" replace />;
  if (me.impersonatedBy) return <p className="mx-auto max-w-xl py-16 text-center text-sm text-zinc-500">{t("impersonation.settingsLocked")}</p>;
  return <SettingsInner />;
}

function Section({ title, description, children }: { title: string; description?: ReactNode; children: ReactNode }) {
  return (
    <section className="border-b border-zinc-300 pb-6 dark:border-zinc-800">
      <h2 className="mb-3 text-xs uppercase tracking-wider text-zinc-500">{title}</h2>
      {description && <p className="mb-3 text-sm text-zinc-500">{description}</p>}
      {children}
    </section>
  );
}

function SettingsInner() {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const toast = useToast();
  const copy = useCopy();
  const { data: me } = useMe();
  const { data: cfg } = useConfig();
  const [params, setParams] = useSearchParams();
  const [displayName, setDisplayName] = useState(me!.displayName);
  const [handle, setHandle] = useState(me!.handle);
  const [bio, setBio] = useState(me!.bio);
  const [avatarUrl, setAvatarUrl] = useState(me!.avatarUrl ?? "");
  const [socials, setSocials] = useState<Social[]>(me!.socials);
  const [newToken, setNewToken] = useState<string | null>(null);
  const [tokenName, setTokenName] = useState("");
  const [tokenScope, setTokenScope] = useState("rw");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const tokens = useQuery({ queryKey: ["tokens"], queryFn: api.tokens });
  const passkeys = useQuery({ queryKey: ["passkeys"], queryFn: api.passkeys });
  const [passkeyName, setPasskeyName] = useState("");
  const [renaming, setRenaming] = useState<{ id: number; name: string } | null>(null);
  const errorToast = (e: unknown) => toast(e instanceof ApiError ? t(`errors.${e.code}`, { defaultValue: e.code }) : t("errors.passkey_failed"), "error");
  const addPasskey = async () => {
    try {
      await registerPasskey(passkeyName.trim());
      setPasskeyName(""); passkeys.refetch();
      toast(t("settings.passkeyAdded"));
    } catch (e) { if (!isPasskeyCancel(e)) errorToast(e); }
  };

  useEffect(() => {
    document.title = `${t("nav.settings")} · Texto`;
    const linked = params.get("linked"), err = params.get("error");
    if (linked) toast(params.get("merged") ? t("settings.mergedToast", { provider: t(`providers.${linked}`) }) : t("settings.linkedToast", { provider: t(`providers.${linked}`) }));
    if (err) toast(t(`errors.${err}`, { defaultValue: err }), "error");
    if (linked || err) setParams({}, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refresh = () => qc.invalidateQueries({ queryKey: ["me"] });
  const saveProfile = async () => {
    if (!socialsValid(socials, cfg?.socials ?? [])) { toast(t("errors.invalid_social_url"), "error"); return; }
    try {
      await api.updateMe({ displayName, handle, bio, avatarUrl, locale: i18n.language.slice(0, 2) });
      await api.setSocials(socials.filter((s) => s.url.trim()));
      refresh();
      toast(t("settings.saved"));
    } catch (e) {
      toast(e instanceof ApiError ? t(`errors.${e.code}`, { defaultValue: e.code }) : String(e), "error");
    }
  };
  const base = cfg?.baseUrl ?? location.origin;

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      

      <Section title={t("settings.profile")}>
        <div className="grid gap-4 sm:grid-cols-2">
          <label><Label>{t("settings.displayName")}</Label><Input value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={100} /></label>
          <label><Label hint={`${base}/u/${handle}`}>{t("settings.handle")}</Label><Input value={handle} onChange={(e) => setHandle(e.target.value.toLowerCase())} maxLength={40} /></label>
          <label className="sm:col-span-2"><Label>{t("settings.avatar")}</Label>
            <div className="flex gap-2">
              <Input value={avatarUrl} onChange={(e) => setAvatarUrl(e.target.value)} placeholder="https://…" />
              {me!.accounts.filter((a) => a.avatarUrl).map((a) => (
                <button key={a.provider} title={t("settings.useAvatarOf", { provider: t(`providers.${a.provider}`) })} onClick={() => setAvatarUrl(a.avatarUrl!)} className="shrink-0"><Avatar src={a.avatarUrl} name={a.username} size={36} /></button>
              ))}
            </div>
          </label>
          <label className="sm:col-span-2"><Label>{t("settings.bio")}</Label><Textarea value={bio} onChange={(e) => setBio(e.target.value)} rows={3} maxLength={2000} /></label>
        </div>
        <div className="mt-4 space-y-2">
          <Label>{t("settings.socials")}</Label>
          <SocialEditor value={socials} onChange={setSocials} />
        </div>
        <div className="mt-5 flex justify-end"><Button variant="primary" onClick={saveProfile}>{t("common.save")}</Button></div>
      </Section>

      <Section title={t("settings.accounts")}>
        <ul className="space-y-2">
          {(cfg?.providers ?? []).map((p) => {
            const acc = me!.accounts.find((a) => a.provider === p);
            return (
              <li key={p} className="flex items-center gap-3 rounded border border-zinc-200 px-3 py-2 dark:border-zinc-800">
                <ProviderIcon provider={p} className="size-4 text-zinc-500" />
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium">{t(`providers.${p}`)}</div>
                  <div className="text-xs text-zinc-500">{acc ? t("settings.linkedAs", { name: acc.username, when: relativeTime(acc.linkedAt) }) : t("settings.notLinked")}</div>
                </div>
                {acc ? (
                  <Button size="sm" variant="ghost" disabled={me!.accounts.length <= 1} title={me!.accounts.length <= 1 ? t("errors.last_account") : undefined}
                    onClick={async () => { try { await api.unlink(p); refresh(); } catch (e) { toast(e instanceof ApiError ? t(`errors.${e.code}`) : String(e), "error"); } }}>
                    <Unlink className="size-4" />{t("settings.unlink")}
                  </Button>
                ) : (
                  <a href={loginUrl(p, "/settings", true)} className="inline-flex h-8 items-center gap-1 rounded bg-zinc-800 px-2.5 text-sm font-medium text-zinc-100 hover:bg-zinc-700 dark:bg-zinc-200 dark:text-zinc-900 dark:hover:bg-white"><Check className="size-4" />{t("settings.link")}</a>
                )}
              </li>
            );
          })}
        </ul>
      </Section>

      <Section title={t("settings.passkeys")} description={t("settings.passkeysHint")}>
        {passkeysSupported() ? (
          <form className="flex flex-wrap gap-2" onSubmit={(e) => { e.preventDefault(); addPasskey(); }}>
            <Input value={passkeyName} onChange={(e) => setPasskeyName(e.target.value)} placeholder={t("settings.passkeyName")} className="w-52 flex-1" maxLength={100} />
            <Button type="submit" variant="primary"><Fingerprint className="size-4" />{t("settings.addPasskey")}</Button>
          </form>
        ) : <p className="text-sm text-zinc-500">{t("settings.passkeysUnsupported")}</p>}
        <ul className="mt-4 divide-y divide-zinc-100 dark:divide-zinc-800">
          {passkeys.data?.map((pk) => (
            <li key={pk.id} className="flex items-center gap-3 py-2 text-sm">
              <Fingerprint className="size-4 text-zinc-400" />
              <div className="min-w-0 flex-1">
                <div className="font-medium">{pk.name}</div>
                <div className="text-xs text-zinc-500">{t("settings.passkeyCreated", { when: relativeTime(pk.createdAt) })} · {pk.lastUsedAt ? t("settings.lastUsed", { when: relativeTime(pk.lastUsedAt) }) : t("settings.neverUsed")}</div>
              </div>
              <Button size="sm" variant="ghost" title={t("settings.rename")} onClick={() => setRenaming({ id: pk.id, name: pk.name })}><Pencil className="size-4" /></Button>
              <Button size="sm" variant="ghost" title={t("settings.deletePasskey")} onClick={async () => { try { await api.deletePasskey(pk.id); passkeys.refetch(); } catch (e) { errorToast(e); } }}><Trash2 className="size-4 text-red-500" /></Button>
            </li>
          ))}
        </ul>
      </Section>

      <Section title={t("settings.tokens")}>
        <form className="flex flex-wrap gap-2" onSubmit={async (e) => {
          e.preventDefault();
          const r = await api.createToken(tokenName || "Token", tokenScope === "rw" ? ["posts:read", "posts:write"] : ["posts:read"]);
          setNewToken(r.secret); setTokenName(""); tokens.refetch();
        }}>
          <Input value={tokenName} onChange={(e) => setTokenName(e.target.value)} placeholder={t("settings.tokenName")} className="w-52 flex-1" maxLength={100} />
          <Select value={tokenScope} onChange={(e) => setTokenScope(e.target.value)} className="w-auto">
            <option value="rw">{t("settings.scopeRw")}</option>
            <option value="r">{t("settings.scopeR")}</option>
          </Select>
          <Button type="submit" variant="primary"><Plus className="size-4" />{t("settings.createToken")}</Button>
        </form>
        <ul className="mt-4 divide-y divide-zinc-100 dark:divide-zinc-800">
          {tokens.data?.map((tok) => (
            <li key={tok.id} className="flex items-center gap-3 py-2 text-sm">
              <KeyRound className="size-4 text-zinc-400" />
              <div className="min-w-0 flex-1">
                <div className="font-medium">{tok.name} {tok.legacy && <span className="ml-1 rounded bg-amber-100 px-1 text-xs text-amber-800 dark:bg-amber-950 dark:text-amber-300">v1</span>}</div>
                <div className="text-xs text-zinc-500"><code>{tok.prefix}…</code> · {tok.scopes.join(", ")} · {tok.lastUsedAt ? t("settings.lastUsed", { when: relativeTime(tok.lastUsedAt) }) : t("settings.neverUsed")}</div>
              </div>
              <Button size="sm" variant="ghost" onClick={async () => { await api.deleteToken(tok.id); tokens.refetch(); }}><Trash2 className="size-4 text-red-500" /></Button>
            </li>
          ))}
        </ul>
        <div className="mt-4 border-t border-zinc-300 pt-3 text-xs dark:border-zinc-800">
          <pre className="overflow-x-auto whitespace-pre font-mono">{`curl -fsSLo texto.mjs ${base}/cli/texto.mjs
export TEXTO_URL=${base} TEXTO_TOKEN=txt_…
cat build.log | node texto.mjs -t "Build log" --tags ci,logs
node texto.mjs fichier.kt --private --expire 1d`}</pre>
          <p className="mt-2 text-zinc-500"><Trans i18nKey="settings.apiDocs" components={{ a: <a href="/api/docs" className="text-zinc-400 hover:underline" /> }} /></p>
        </div>
      </Section>

      <Section title={t("settings.danger")}>
        <Button variant="danger" onClick={() => setConfirmDelete(true)}><Trash2 className="size-4" />{t("settings.deleteAccount")}</Button>
      </Section>

      <Modal open={newToken != null} onClose={() => setNewToken(null)} title={t("settings.tokenCreated")}
        footer={<Button variant="primary" onClick={() => setNewToken(null)}>{t("common.done")}</Button>}>
        <p className="mb-3 text-sm text-zinc-500">{t("settings.tokenOnce")}</p>
        <div className="flex gap-2">
          <Input readOnly value={newToken ?? ""} className="font-mono text-xs" onFocus={(e) => e.target.select()} />
          <Button onClick={() => copy(newToken!, t("settings.tokenCopied"))}><Copy className="size-4" /></Button>
        </div>
      </Modal>
      <Modal open={renaming != null} onClose={() => setRenaming(null)} title={t("settings.rename")}
        footer={<>
          <Button onClick={() => setRenaming(null)}>{t("common.cancel")}</Button>
          <Button variant="primary" onClick={async () => {
            try { await api.renamePasskey(renaming!.id, renaming!.name); setRenaming(null); passkeys.refetch(); } catch (e) { errorToast(e); }
          }}>{t("common.save")}</Button>
        </>}>
        <Input value={renaming?.name ?? ""} onChange={(e) => setRenaming((r) => r && { ...r, name: e.target.value })} maxLength={100} autoFocus />
      </Modal>
      <Modal open={confirmDelete} onClose={() => setConfirmDelete(false)} title={t("settings.deleteAccount")}
        footer={<>
          <Button onClick={() => setConfirmDelete(false)}>{t("common.cancel")}</Button>
          <Button variant="danger" onClick={async () => { await api.deleteMe(); qc.clear(); location.href = "/"; }}>{t("settings.deleteForever")}</Button>
        </>}>
        <p className="text-sm">{t("settings.deleteAccountConfirm")}</p>
      </Modal>
    </div>
  );
}
