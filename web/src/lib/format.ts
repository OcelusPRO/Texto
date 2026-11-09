import i18n from "./i18n";

export function relativeTime(ms: number): string {
  const rtf = new Intl.RelativeTimeFormat(i18n.language, { numeric: "auto" });
  const diff = (ms - Date.now()) / 1000;
  const abs = Math.abs(diff);
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ["year", 31536000], ["month", 2592000], ["week", 604800], ["day", 86400], ["hour", 3600], ["minute", 60],
  ];
  for (const [unit, s] of units) if (abs >= s) return rtf.format(Math.round(diff / s), unit);
  return rtf.format(Math.round(diff), "second");
}

export function fullDate(ms: number): string {
  return new Date(ms).toLocaleString(i18n.language, { dateStyle: "medium", timeStyle: "short" });
}

export function bytes(n: number): string {
  if (n < 1024) return `${n} o`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} Ko`;
  return `${(n / 1024 / 1024).toFixed(1)} Mo`;
}

export const TAG_COLORS: Record<string, string> = {
  slate: "bg-zinc-500",
  red: "bg-red-400",
  orange: "bg-orange-400",
  amber: "bg-amber-400",
  lime: "bg-lime-400",
  emerald: "bg-emerald-400",
  cyan: "bg-cyan-400",
  sky: "bg-sky-400",
  indigo: "bg-indigo-400",
  violet: "bg-violet-400",
  pink: "bg-pink-400",
  rose: "bg-rose-400",
};
