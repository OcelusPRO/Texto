// Changements entre deux versions d'un modpack, lisibles mod par mod (éditeur et historique).
import clsx from "clsx";
import { ArrowDown, ArrowUp, Minus, Plus } from "lucide-react";
import { useMemo, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { DiffView } from "../components/Viewers";
import { gameLabel, type ModpackGame } from "./games";
import { BUILTIN_TITLES, diffModpacks, portalUrl, BUILTIN, type Modpack } from "./model";
import { cmpVersion } from "./saveMods";

export function ModpackChanges({ before, after, game }: { before: Modpack; after: Modpack; game: ModpackGame }) {
  const { t } = useTranslation();
  const d = useMemo(() => diffModpacks(before, after), [before, after]);
  if (d.empty) return <p className="text-sm text-zinc-500">{t("modpack.noChanges")}</p>;
  const any = (v: string) => v || t("modpack.anyVersion");

  return (
    <div className="space-y-3 text-sm">
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
        {d.added.length > 0 && <span className="text-emerald-600">{t("modpack.diffAdded", { count: d.added.length })}</span>}
        {d.removed.length > 0 && <span className="text-red-600">{t("modpack.diffRemoved", { count: d.removed.length })}</span>}
        {d.updated.length > 0 && <span className="text-sky-600">{t("modpack.diffUpdated", { count: d.updated.length })}</span>}
      </div>

      {d.gameVersion && (
        <p>{t("modpack.gameVersion", { game: gameLabel(game) })} : <del className="text-red-600">{any(d.gameVersion[0])}</del> → <ins className="text-emerald-600 no-underline">{any(d.gameVersion[1])}</ins></p>
      )}

      {d.added.length > 0 && (
        <Group>{d.added.map((m) => <Row key={m.name} icon={<Plus className="size-3.5 text-emerald-600" />} name={m.name} game={game}>{m.version && <span className="text-zinc-500">{m.version}</span>}</Row>)}</Group>
      )}
      {d.removed.length > 0 && (
        <Group>{d.removed.map((m) => <Row key={m.name} icon={<Minus className="size-3.5 text-red-600" />} name={m.name} game={game} struck>{m.version && <span className="text-zinc-500">{m.version}</span>}</Row>)}</Group>
      )}
      {d.updated.length > 0 && (
        <Group>{d.updated.map((u) => {
          const up = u.from && u.to ? cmpVersion(u.to, u.from) > 0 : true;
          return (
            <Row key={u.name} icon={up ? <ArrowUp className="size-3.5 text-sky-600" /> : <ArrowDown className="size-3.5 text-orange-600" />} name={u.name} game={game}>
              <span className="font-mono text-xs text-zinc-500">{any(u.from)} → <span className={up ? "text-sky-600" : "text-orange-600"}>{any(u.to)}</span></span>
            </Row>
          );
        })}</Group>
      )}
      {d.annotated.length > 0 && (
        <div>
          <p className="mb-1 text-xs text-zinc-500">{t("modpack.diffAnnotated")}</p>
          <Group>{d.annotated.map((a) => (
            <Row key={a.name} icon={<span className="size-3.5" />} name={a.name} game={game}>
              <span className="min-w-0 truncate text-xs text-zinc-500">
                {a.url && <>{t("modpack.url")} : {a.url[1] || "—"} </>}
                {a.note && <>{t("modpack.note")} : {a.note[1] || "—"}</>}
              </span>
            </Row>
          ))}</Group>
        </div>
      )}
      {d.reordered && <p className="text-xs text-zinc-500">{t("modpack.diffReordered")}</p>}
      {d.image && <p className="truncate">{t("modpack.image")} : <span className="text-zinc-500">{d.image[1] || "—"}</span></p>}
      {d.readme && (
        <div>
          <p className="mb-1 text-xs text-zinc-500">{t("modpack.readme")}</p>
          <DiffView before={d.readme[0]} after={d.readme[1]} />
        </div>
      )}
    </div>
  );
}

const Group = ({ children }: { children: ReactNode }) => (
  <ul className="divide-y divide-zinc-200 border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">{children}</ul>
);

function Row({ icon, name, game, struck, children }: { icon: ReactNode; name: string; game: ModpackGame; struck?: boolean; children?: ReactNode }) {
  const label = BUILTIN_TITLES[name] ?? name;
  const link = game === "factorio" && !BUILTIN.has(name);
  return (
    <li className="flex items-center gap-2 px-2 py-1">
      {icon}
      {link
        ? <a href={portalUrl(name)} target="_blank" rel="noreferrer" className={clsx("font-mono text-xs hover:underline", struck && "line-through text-zinc-500")}>{label}</a>
        : <span className={clsx("font-mono text-xs", struck && "line-through text-zinc-500")}>{label}</span>}
      <span className="ml-auto flex min-w-0 items-center gap-2">{children}</span>
    </li>
  );
}
