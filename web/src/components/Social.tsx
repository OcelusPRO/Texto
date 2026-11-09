import { Globe } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  siBluesky, siDiscord, siGithub, siInstagram, siMastodon, siReddit, siSpotify, siSteam, siTiktok, siTwitch, siX, siYoutube,
} from "simple-icons";
import clsx from "clsx";
import type { Social, SocialDef } from "../lib/api";
import { useConfig } from "../lib/session";
import { socialKey, socialLabel, validSocialUrl } from "../lib/socials";

export { socialHandle, socialKey, socialLabel, validSocialUrl } from "../lib/socials";

const ICONS: Record<string, { path: string; title: string }> = {
  twitch: siTwitch, x: siX, github: siGithub, discord: siDiscord, youtube: siYoutube, spotify: siSpotify, steam: siSteam,
  instagram: siInstagram, tiktok: siTiktok, reddit: siReddit, bluesky: siBluesky, mastodon: siMastodon,
};

export function SocialIcon({ name, className = "size-4" }: { name: string; className?: string }) {
  const icon = ICONS[socialKey(name)];
  if (!icon) return <Globe className={className} />;
  return (
    <svg viewBox="0 0 24 24" className={className} fill="currentColor" role="img" aria-label={icon.title}>
      <path d={icon.path} />
    </svg>
  );
}

function IconPicker({ value, defs, onChange }: { value: string; defs: SocialDef[]; onChange: (key: string) => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false);
    const esc = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", esc);
    return () => { document.removeEventListener("mousedown", close); document.removeEventListener("keydown", esc); };
  }, [open]);
  const current = defs.find((d) => d.key === value);
  return (
    <div ref={ref} className="relative">
      <button type="button" onClick={() => setOpen(!open)} aria-haspopup="listbox" aria-expanded={open}
        className="flex h-9 w-44 items-center gap-2 border border-zinc-300 px-2.5 text-sm hover:bg-zinc-200 dark:border-zinc-700 dark:hover:bg-zinc-800">
        <SocialIcon name={value} className="size-4 shrink-0" />
        <span className="truncate">{current?.label ?? socialLabel(value)}</span>
      </button>
      {open && (
        <div role="listbox" className="absolute left-0 top-full z-40 mt-1 grid w-72 grid-cols-3 gap-1 border border-zinc-300 bg-zinc-50 p-1.5 shadow dark:border-zinc-700 dark:bg-zinc-900">
          {defs.map((d) => (
            <button key={d.key} type="button" role="option" aria-selected={d.key === value} onClick={() => { onChange(d.key); setOpen(false); }}
              className={clsx("flex flex-col items-center gap-1 px-1 py-2 text-xs hover:bg-zinc-200 dark:hover:bg-zinc-800", d.key === value && "bg-zinc-200 dark:bg-zinc-800")}>
              <SocialIcon name={d.key} className="size-5" />
              {d.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** Éditeur de liens : sélecteur d'icônes, et un lien qui doit correspondre à l'icône choisie. */
export function SocialEditor({ value, onChange }: { value: Social[]; onChange: (v: Social[]) => void }) {
  const { t } = useTranslation();
  const { data: cfg } = useConfig();
  const defs = cfg?.socials ?? [];
  const byKey = new Map(defs.map((d) => [d.key, d]));
  return (
    <div className="space-y-3">
      {value.map((s, i) => {
        const key = socialKey(s.icon, s.url);
        const def = byKey.get(key);
        const empty = !s.url.trim();
        const invalid = !empty && !validSocialUrl(def, s.url);
        const set = (patch: Partial<Social>) => onChange(value.map((x, j) => (j === i ? { ...x, ...patch } : x)));
        return (
          <div key={i} className="flex items-start gap-2">
            <IconPicker value={key} defs={defs} onChange={(k) => set({ icon: k })} />
            <div className="min-w-0 flex-1">
              <input
                value={s.url}
                onChange={(e) => set({ url: e.target.value, icon: key })}
                placeholder={def?.placeholder ?? "https://…"}
                aria-invalid={invalid}
                className={clsx("h-9 w-full border bg-transparent px-3 text-sm outline-none", invalid ? "border-red-500" : "border-zinc-300 focus:border-zinc-500 dark:border-zinc-700")}
              />
              {invalid && <p className="mt-1 text-xs text-red-500">{t("settings.socialHint", { site: def?.label ?? key, example: def?.placeholder ?? "https://…" })}</p>}
            </div>
            <button type="button" onClick={() => onChange(value.filter((_, j) => j !== i))} className="flex h-9 items-center px-2 text-zinc-500 hover:text-red-500" aria-label={t("common.delete")}>
              ×
            </button>
          </div>
        );
      })}
      <button type="button" onClick={() => onChange([...value, { icon: "website", url: "" }])} className="border border-zinc-300 px-3 py-1.5 text-sm hover:bg-zinc-200 dark:border-zinc-700 dark:hover:bg-zinc-800">
        + {t("settings.addSocial")}
      </button>
    </div>
  );
}

/** Tous les liens sont-ils valides pour leur icône ? (bloque l'enregistrement sinon) */
export function socialsValid(list: Social[], defs: SocialDef[]): boolean {
  const byKey = new Map(defs.map((d) => [d.key, d]));
  return list.every((s) => !s.url.trim() || validSocialUrl(byKey.get(socialKey(s.icon, s.url)), s.url));
}
