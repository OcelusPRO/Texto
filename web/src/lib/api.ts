// Client de l'API Texto (mêmes formes que les DTO Kotlin de service/Dto.kt).

export type Kind = "code" | "text" | "markdown" | "factorio";
export type Visibility = "public" | "unlisted" | "private";

export interface Social { icon: string; url: string }
export interface UserMini { handle: string; displayName: string; avatarUrl: string | null }
export interface LinkedAccount { provider: string; username: string; avatarUrl: string | null; linkedAt: number }
export interface Me {
  id: number; handle: string; displayName: string; avatarUrl: string | null; bio: string;
  role: "user" | "admin"; locale: string | null; accounts: LinkedAccount[]; socials: Social[];
  /** Renseigné quand un administrateur agit en tant que ce compte : l'administrateur réel. */
  impersonatedBy?: UserMini | null;
}
export interface UserPublic {
  handle: string; displayName: string; avatarUrl: string | null; bio: string; socials: Social[];
  createdAt: number; publicPosts: number; totalViews: number;
}
export interface Tag { id: number; name: string; color: string; postCount: number }
export interface Folder { id: number; parentId: number | null; name: string; postCount: number }
export interface PostSummary {
  key: string; title: string; description: string; kind: Kind; language: string | null; visibility: Visibility;
  views: number; revision: number; tags: Tag[]; folderId: number | null; hasPassword: boolean;
  burnAfterViews: number | null; hasThumbnail: boolean; size: number; createdAt: number; updatedAt: number;
  expiresAt: number | null; author?: UserMini | null;
}
export interface Post { summary: PostSummary; content: string; author: UserMini; isOwner: boolean; burned: boolean }
export interface PostInput {
  title?: string; description?: string; kind?: Kind; language?: string; content?: string; visibility?: Visibility;
  password?: string; burnAfterViews?: number; expiresIn?: number; folderId?: number; clearFolder?: boolean; tags?: string[];
}
export interface RevisionSummary { number: number; title: string; size: number; createdAt: number }
export interface Revision { number: number; title: string; description: string; kind: Kind; language: string | null; content: string; createdAt: number }
export interface Page<T> { items: T[]; total: number; page: number; pageSize: number }
export interface Token { id: number; name: string; prefix: string; scopes: string[]; legacy: boolean; createdAt: number; lastUsedAt: number | null; expiresAt: number | null }
export interface SocialDef {
  key: string; label: string; placeholder: string; embed: boolean; allowHttp: boolean;
  rules: { host: string; pathPrefix: string | null }[];
}
export interface PublicConfig {
  siteName: string; baseUrl: string; providers: string[]; maxContentLength: number; factorioAssets: boolean;
  allowNewUsers: boolean; fbeUrl: string | null; socials: SocialDef[];
}
export interface AdminStats { users: number; posts: number; views: number; legacyUsersUnclaimed: number; postsByKind: Record<string, number>; storageBytes: number }
export interface AdminUser {
  id: number; handle: string; displayName: string; avatarUrl: string | null; role: string; banned: boolean; posts: number;
  providers: string[]; legacy: boolean; createdAt: number; lastLoginAt: number | null;
  accounts: { provider: string; providerUserId: string; username: string }[];
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message?: string) {
    super(message ?? code);
  }
}

async function request<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  const init: RequestInit = { method, headers: { ...headers }, credentials: "same-origin" };
  if (body instanceof Blob) {
    init.body = body;
    (init.headers as Record<string, string>)["Content-Type"] = body.type || "application/octet-stream";
  } else if (body !== undefined) {
    init.body = JSON.stringify(body);
    (init.headers as Record<string, string>)["Content-Type"] = "application/json";
  }
  const res = await fetch(path, init);
  if (!res.ok) {
    let code = "http_" + res.status;
    let message: string | undefined;
    try {
      const j = await res.json();
      code = j.error ?? code;
      message = j.message;
    } catch { /* corps non JSON */ }
    throw new ApiError(res.status, code, message);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

const get = <T,>(p: string, h?: Record<string, string>) => request<T>("GET", p, undefined, h);
const qs = (o: Record<string, string | number | undefined | null | string[]>) => {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(o)) {
    if (v === undefined || v === null || v === "") continue;
    if (Array.isArray(v)) v.forEach((x) => u.append(k, x));
    else u.set(k, String(v));
  }
  const s = u.toString();
  return s ? "?" + s : "";
};

export interface PostListQuery {
  q?: string; tag?: string[]; folder?: number | "none"; recursive?: boolean; kind?: string; lang?: string;
  visibility?: string; sort?: string; page?: number; pageSize?: number;
}

export const api = {
  config: () => get<PublicConfig>("/api/config"),
  me: () => get<Me>("/api/me"),
  updateMe: (p: Partial<Pick<Me, "handle" | "displayName" | "bio" | "avatarUrl" | "locale">>) => request<Me>("PATCH", "/api/me", p),
  deleteMe: () => request<void>("DELETE", "/api/me"),
  setSocials: (s: Social[]) => request<Social[]>("PUT", "/api/me/socials", s),
  unlink: (provider: string) => request<Me>("DELETE", `/api/me/accounts/${provider}`),
  logout: () => request<void>("POST", "/api/auth/logout"),

  myPosts: (q: PostListQuery) =>
    get<Page<PostSummary>>("/api/me/posts" + qs({ ...q, recursive: q.recursive ? "1" : undefined } as never)),
  folders: () => get<Folder[]>("/api/me/folders"),
  createFolder: (name: string, parentId: number | null) => request<Folder>("POST", "/api/me/folders", { name, parentId }),
  updateFolder: (id: number, p: { name?: string; parentId?: number | null; moveToRoot?: boolean }) =>
    request<Folder>("PATCH", `/api/me/folders/${id}`, p),
  deleteFolder: (id: number) => request<void>("DELETE", `/api/me/folders/${id}`),
  tags: () => get<Tag[]>("/api/me/tags"),
  createTag: (name: string, color?: string) => request<Tag>("POST", "/api/me/tags", { name, color }),
  updateTag: (id: number, name: string, color?: string) => request<Tag>("PATCH", `/api/me/tags/${id}`, { name, color }),
  deleteTag: (id: number) => request<void>("DELETE", `/api/me/tags/${id}`),
  tokens: () => get<Token[]>("/api/me/tokens"),
  createToken: (name: string, scopes: string[], expiresInDays?: number) =>
    request<{ token: Token; secret: string }>("POST", "/api/me/tokens", { name, scopes, expiresInDays }),
  deleteToken: (id: number) => request<void>("DELETE", `/api/me/tokens/${id}`),

  post: (key: string, password?: string) =>
    get<Post>(`/api/posts/${encodeURIComponent(key)}`, password ? { "X-Post-Password": password } : {}),
  createPost: (p: PostInput) => request<PostSummary>("POST", "/api/posts", p),
  updatePost: (key: string, p: PostInput) => request<PostSummary>("PATCH", `/api/posts/${key}`, p),
  deletePost: (key: string) => request<void>("DELETE", `/api/posts/${key}`),
  uploadThumbnail: (key: string, png: Blob) => request<void>("PUT", `/api/posts/${key}/thumbnail`, png),
  revisions: (key: string) => get<RevisionSummary[]>(`/api/posts/${key}/revisions`),
  revision: (key: string, n: number) => get<Revision>(`/api/posts/${key}/revisions/${n}`),
  restore: (key: string, n: number) => request<PostSummary>("POST", `/api/posts/${key}/revisions/${n}/restore`),

  user: (handle: string) => get<UserPublic>(`/api/users/${handle}`),
  userPosts: (handle: string, page = 1, q?: string, folder?: number, f: { kind?: string; tag?: string[]; sort?: string } = {}) =>
    get<Page<PostSummary>>(`/api/users/${handle}/posts` + qs({ page, q, folder, kind: f.kind, tag: f.tag, sort: f.sort } as never)),
  /** Tags d'un profil public (posts publics uniquement). */
  userTags: (handle: string) => get<Tag[]>(`/api/users/${handle}/tags`),
  /** Dossiers d'un profil public : seulement ceux qui contiennent au moins un post public. */
  userFolders: (handle: string) => get<Folder[]>(`/api/users/${handle}/folders`),

  admin: {
    stats: () => get<AdminStats>("/api/admin/stats"),
    users: (q?: string, page = 1) => get<Page<AdminUser>>("/api/admin/users" + qs({ q, page })),
    linkAccount: (id: number, provider: string, providerUserId: string) =>
      request<void>("POST", `/api/admin/users/${id}/accounts`, { provider, providerUserId }),
    unlinkAccount: (id: number, provider: string) => request<void>("DELETE", `/api/admin/users/${id}/accounts/${provider}`),
    impersonate: (id: number) => request<void>("POST", `/api/admin/impersonate/${id}`),
    stopImpersonation: () => request<void>("POST", "/api/admin/impersonate/stop"),
    settings: () => get<{ allowNewUsers: boolean }>("/api/admin/settings"),
    setSettings: (s: { allowNewUsers: boolean }) => request<{ allowNewUsers: boolean }>("PUT", "/api/admin/settings", s),
    deleteUser: (id: number) => request<void>("DELETE", `/api/admin/users/${id}`),
    patchUser: (id: number, p: { banned?: boolean; role?: string }) => request<void>("PATCH", `/api/admin/users/${id}`, p),
    merge: (id: number, target: number) => request<void>("POST", `/api/admin/users/${id}/merge-into/${target}`),
    posts: (q?: string, page = 1) => get<Page<PostSummary>>("/api/admin/posts" + qs({ q, page })),
  },
};
