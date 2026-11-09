import clsx from "clsx";
import { Moon, Plus, Sun } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, NavLink, Outlet, useNavigate } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { useConfig, useMe } from "../lib/session";
import { Avatar } from "./ui";
import { useDark } from "./Viewers";

const iconBtn = "rounded p-1.5 text-zinc-500 hover:bg-zinc-200 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-zinc-100";

function ThemeToggle() {
  const dark = useDark();
  return (
    <button
      className={iconBtn}
      onClick={() => {
        document.documentElement.classList.toggle("dark", !dark);
        try { localStorage.setItem("texto-theme", dark ? "light" : "dark"); } catch { /* stockage indisponible */ }
      }}
    >
      {dark ? <Sun className="size-4" /> : <Moon className="size-4" />}
    </button>
  );
}

function UserMenu() {
  const { data: me } = useMe();
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const nav = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const h = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false);
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, []);
  if (!me) return null;
  const item = "block w-full px-3 py-1.5 text-left text-sm hover:bg-zinc-100 dark:hover:bg-zinc-800";
  return (
    <div ref={ref} className="relative">
      <button onClick={() => setOpen(!open)} className="flex items-center rounded p-1 hover:bg-zinc-200 dark:hover:bg-zinc-800">
        <Avatar src={me.avatarUrl} name={me.displayName} size={24} />
      </button>
      {open && (
        <div className="absolute right-0 z-40 mt-1 w-44 border border-zinc-300 bg-white py-1 dark:border-zinc-700 dark:bg-zinc-900" onClick={() => setOpen(false)}>
          <div className="truncate px-3 py-1 text-xs text-zinc-500">@{me.handle}</div>
          <Link to={`/u/${me.handle}`} className={item}>{t("nav.profile")}</Link>
          <Link to="/settings" className={item}>{t("nav.settings")}</Link>
          {me.role === "admin" && <Link to="/admin" className={item}>{t("nav.admin")}</Link>}
          <button className={item} onClick={() => i18n.changeLanguage(i18n.language.startsWith("fr") ? "en" : "fr")}>
            {i18n.language.startsWith("fr") ? "English" : "Français"}
          </button>
          <button className={clsx(item, "text-red-500")} onClick={async () => { await api.logout(); qc.clear(); qc.setQueryData(["me"], null); nav("/login"); }}>
            {t("nav.logout")}
          </button>
        </div>
      )}
    </div>
  );
}

export function Layout() {
  const { t } = useTranslation();
  const { data: cfg } = useConfig();
  const { data: me } = useMe();
  const qc = useQueryClient();
  const nav = useNavigate();
  const link = ({ isActive }: { isActive: boolean }) =>
    clsx("px-2 py-1 text-sm", isActive ? "text-zinc-900 dark:text-zinc-100" : "text-zinc-500 hover:text-zinc-900 dark:hover:text-zinc-100");
  return (
    <div className="flex min-h-screen flex-col">
      {me?.impersonatedBy && (
        <div role="status" className="flex flex-wrap items-center justify-center gap-3 bg-zinc-800 px-3 py-1.5 text-sm text-zinc-100 dark:bg-zinc-200 dark:text-zinc-900">
          <span>{t("impersonation.banner", { handle: me.handle, admin: me.impersonatedBy.handle })}</span>
          <button
            onClick={async () => { await api.admin.stopImpersonation(); qc.clear(); nav("/admin"); }}
            className="border border-current px-2 py-0.5 font-semibold hover:opacity-80"
          >
            {t("impersonation.stop")}
          </button>
        </div>
      )}
      <header className="border-b border-zinc-300 dark:border-zinc-800">
        <div className="flex h-11 items-center gap-1 px-3">
          <Link to="/" className="mr-3 text-sm font-bold">{(cfg?.siteName ?? "texto").toLowerCase()}</Link>
          {me && <NavLink to="/dashboard" className={link}>{t("nav.dashboard")}</NavLink>}
          <div className="flex-1" />
          {me && <Link to="/new" title={t("nav.new")} className={iconBtn}><Plus className="size-4" /></Link>}
          <ThemeToggle />
          <UserMenu />
        </div>
      </header>
      <main className="w-full flex-1 px-3 py-4 sm:px-6">
        <Outlet />
      </main>
    </div>
  );
}
