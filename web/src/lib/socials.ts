// Logique pure des liens sociaux (sans React) : clés d'icônes, libellés, nom d'utilisateur extrait d'un lien, règles.
import type { SocialDef } from "./api";

/** Icônes du catalogue (le serveur en est l'autorité). Toute autre valeur est traitée comme un lien web classique. */
export const KNOWN_ICONS = new Set([
  "website", "twitch", "x", "github", "discord", "youtube", "spotify", "steam", "instagram", "tiktok", "reddit", "bluesky", "mastodon",
]);

const ALIASES: Record<string, string> = { link: "website", globe: "website", web: "website", "x-twitter": "x", twitter: "x", "twitter-x": "x" };
const LABELS: Record<string, string> = {
  website: "Site web", x: "X", github: "GitHub", youtube: "YouTube", tiktok: "TikTok", twitch: "Twitch", discord: "Discord",
  spotify: "Spotify", steam: "Steam", instagram: "Instagram", reddit: "Reddit", bluesky: "Bluesky", mastodon: "Mastodon",
};

/** "fa-brands fa-x-twitter" -> "x" ; une icône inconnue devient "website" (même règle que le backend). */
export function socialKey(raw: string, url?: string): string {
  const FA = new Set(["fa", "fab", "fas", "far", "fa-brands", "fa-solid", "fa-regular"]);
  const k = raw.trim().toLowerCase().split(/\s+/).filter((p) => p && !FA.has(p)).map((p) => p.replace(/^fa-/, ""))[0];
  let base = k;
  if (!base) {
    try { base = new URL(url ?? "").hostname.replace(/^www\./, "").split(".")[0] || "website"; } catch { base = "website"; }
  }
  const key = ALIASES[base] ?? base;
  return KNOWN_ICONS.has(key) ? key : "website";
}

export function socialLabel(key: string): string {
  return LABELS[key] ?? "Site web";
}

/** Adresse lisible d'un site : sans schéma, sans « www. », sans « / » final. */
function readableUrl(u: URL): string {
  const host = u.hostname.replace(/^(www\.|m\.|mobile\.)/, "");
  const path = u.pathname.replace(/\/+$/, "");
  return host + path + u.search;
}

/**
 * Texte affiché pour un lien : le nom d'utilisateur sur la plateforme (« @OcelusPRO », « arftnl »…),
 * ou l'adresse pour un site web (« ftnl.fr »).
 */
export function socialHandle(key: string, url: string): string {
  let u: URL;
  try { u = new URL(url.trim()); } catch { return url; }
  const host = u.hostname.replace(/^(www\.|m\.|mobile\.)/, "");
  const segs = u.pathname.split("/").filter(Boolean).map((s) => { try { return decodeURIComponent(s); } catch { return s; } });
  const first = segs[0] ?? "";
  const at = (s: string) => "@" + s.replace(/^@/, "");
  switch (key) {
    case "twitch":
    case "github":
      return first || host;
    case "x":
    case "instagram":
      return first ? at(first) : host;
    case "tiktok":
      return first ? at(first) : host;
    case "youtube":
      if (first.startsWith("@")) return first;
      if (["channel", "c", "user"].includes(first) && segs[1]) return segs[1];
      return readableUrl(u);
    case "reddit":
      return ["user", "u"].includes(first) && segs[1] ? `u/${segs[1]}` : readableUrl(u);
    case "bluesky":
      return first === "profile" && segs[1] ? at(segs[1]) : readableUrl(u);
    case "steam":
      return ["id", "profiles"].includes(first) && segs[1] ? segs[1] : readableUrl(u);
    case "spotify":
      return first === "user" && segs[1] ? segs[1] : readableUrl(u);
    case "mastodon":
      return first.startsWith("@") ? `${first}@${host}` : readableUrl(u);
    case "discord":
    default:
      return readableUrl(u);
  }
}

/** Le lien [url] convient-il à l'icône [def] ? (même règles que le serveur, qui reste l'autorité) */
export function validSocialUrl(def: SocialDef | undefined, url: string): boolean {
  if (!def) return false;
  let u: URL;
  try { u = new URL(url.trim()); } catch { return false; }
  const scheme = u.protocol.replace(":", "");
  if (scheme !== "https" && !(def.allowHttp && scheme === "http")) return false;
  const host = u.hostname.toLowerCase().replace(/^www\./, "").replace(/^m\./, "").replace(/^mobile\./, "");
  if (!host.includes(".")) return false;
  return def.rules.some((r) => {
    const hostOk = r.host === "*" || host === r.host || host.endsWith("." + r.host);
    const pathOk = r.pathPrefix ? u.pathname.startsWith(r.pathPrefix) && u.pathname.length > r.pathPrefix.length : def.key === "website" || u.pathname.length > 1;
    return hostOk && pathOk;
  });
}
