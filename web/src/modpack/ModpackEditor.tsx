import clsx from "clsx";
import { FolderOpen, FileUp, History, Plus, RefreshCw, RotateCcw, Trash2, Upload, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type DragEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Input, Label, Spinner, Textarea, useToast } from "../components/ui";
import { ModpackChanges } from "./ModpackDiff";
import { ModpackDisplay } from "./ModpackView";
import { filesFromDataTransfer, importableGames, parseSaves, relevantAny, type GameSave, type ImportResult } from "./importers";
import { gameLabel, type ModpackGame } from "./games";
import { BUILTIN, parseModpack, safeUrl, serializeModpack, syncMods, type Modpack, type ModpackMod } from "./model";

/** Éditeur d'un post modpack : le contenu reste la chaîne JSON du post. */
/** Sauvegardes déjà lues ailleurs (glisser-déposer / collage sur la page) ; [id] change à chaque nouvel envoi. */
export interface IncomingSaves extends ImportResult { id: number }

/** Version publiée d'un modpack qu'on met à jour. */
export interface ModpackBase { content: string; revision: number }

/** « sync » : la liste devient celle de la sauvegarde (liens et notes gardés) ; « merge » : ajoute sans rien retirer. */
type ImportMode = "sync" | "merge";

export function ModpackEditor({ content, onChange, incoming, game, onGameChange, base }: {
  content: string; onChange: (v: string) => void; incoming?: IncomingSaves | null;
  /** Jeu du modpack ; une sauvegarde d'un autre jeu le change. */
  game: ModpackGame; onGameChange: (g: ModpackGame) => void;
  /** Mise à jour d'un modpack existant : les changements sont montrés par rapport à cette version. */
  base?: ModpackBase;
}) {
  const { t } = useTranslation();
  const pack = useMemo(() => parseModpack(content), [content]);
  const [tab, setTab] = useState<"edit" | "preview">("edit");
  // Une sauvegarde déposée sur la page s'importe dans l'onglet d'édition.
  useEffect(() => { if (incoming) setTab("edit"); }, [incoming]);
  const set = (p: Partial<Modpack>) => onChange(serializeModpack({ ...pack, ...p }));
  const setMods = (mods: ModpackMod[]) => set({ mods });
  const basePack = useMemo(() => (base ? parseModpack(base.content) : null), [base]);
  const dirty = !!base && content.trimEnd() !== base.content.trimEnd();

  return (
    <div className="space-y-4">
      <div className="flex gap-1 border-b border-zinc-300 dark:border-zinc-800">
        {(["edit", "preview"] as const).map((k) => (
          <button key={k} type="button" onClick={() => setTab(k)}
            className={clsx("-mb-px border-b-2 px-3 py-1.5 text-sm", tab === k ? "border-zinc-700 font-medium dark:border-zinc-300" : "border-transparent text-zinc-500 hover:text-zinc-800 dark:hover:text-zinc-200")}>
            {t(`modpack.${k}`)}
          </button>
        ))}
      </div>

      {tab === "preview" ? <ModpackDisplay pack={pack} game={game} /> : (
        <>
          <SaveImport
            incoming={incoming}
            current={pack.mods.length}
            update={!!base}
            onImport={(save, mode) => {
              const mods = mode === "sync" ? syncMods(pack.mods, save.mods) : mergeMods(pack.mods, save.mods);
              if (save.game !== game) onGameChange(save.game);
              set({ mods, factorioVersion: pack.factorioVersion && mode === "merge" ? pack.factorioVersion : save.gameVersion || pack.factorioVersion });
            }}
          />

          {base && basePack && (
            <section className="space-y-2 border border-zinc-300 p-3 dark:border-zinc-700">
              <div className="flex flex-wrap items-center gap-2">
                <History className="size-4 text-zinc-400" />
                <span className="text-sm font-medium">{t("modpack.changesSince", { n: base.revision })}</span>
                {dirty && <span className="text-xs text-zinc-500">{t("modpack.nextVersion", { n: base.revision + 1 })}</span>}
                {dirty && (
                  <Button size="sm" variant="ghost" className="ml-auto" onClick={() => onChange(base.content)}>
                    <RotateCcw className="size-4" />{t("modpack.discard")}
                  </Button>
                )}
              </div>
              <ModpackChanges before={basePack} after={pack} game={game} />
            </section>
          )}

          <div className="grid gap-3 sm:grid-cols-[1fr_12rem]">
            <label className="block">
              <Label>{t("modpack.image")}</Label>
              <Input value={pack.image} onChange={(e) => set({ image: e.target.value.trim() })} placeholder="https://…" className={clsx(pack.image && !safeUrl(pack.image) && "border-red-500")} />
            </label>
            <label className="block">
              <Label>{t("modpack.gameVersion", { game: gameLabel(game) })}</Label>
              <Input value={pack.factorioVersion} onChange={(e) => set({ factorioVersion: e.target.value.trim() })} placeholder="2.0.72" />
            </label>
          </div>
          <label className="block">
            <Label>{t("modpack.readme")}</Label>
            <Textarea value={pack.readme} onChange={(e) => set({ readme: e.target.value })} rows={6} className="font-mono text-xs" />
          </label>

          <ModTable mods={pack.mods} onChange={setMods} />
        </>
      )}
    </div>
  );
}

function mergeMods(current: ModpackMod[], added: { name: string; version: string }[]): ModpackMod[] {
  const out = current.map((m) => ({ ...m }));
  const idx = new Map(out.map((m, i) => [m.name, i]));
  for (const a of added) {
    const i = idx.get(a.name);
    if (i == null) { idx.set(a.name, out.length); out.push({ ...a }); } else if (a.version) out[i].version = a.version;
  }
  return out;
}

// ---------------------------------------------------------------- import depuis une sauvegarde (local)

function SaveImport({ current, onImport, incoming, update }: {
  current: number; onImport: (s: GameSave, mode: ImportMode) => void; incoming?: IncomingSaves | null;
  /** Mise à jour d'un modpack publié : la sauvegarde remplace la liste sans confirmation (les changements sont affichés et annulables). */
  update: boolean;
}) {
  const { t } = useTranslation();
  const toast = useToast();
  const filesRef = useRef<HTMLInputElement>(null);
  const dirRef = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [drag, setDrag] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);

  const show = (r: ImportResult) => {
    r.saves.sort((a, b) => (b.mods.length - a.mods.length) || a.name.localeCompare(b.name));
    setResult(r);
    if (r.saves.length === 1) apply(r.saves[0], "sync");
  };

  const run = async (files: File[]) => {
    if (!files.length) return;
    setBusy(true);
    try { show(await parseSaves(files)); } finally { setBusy(false); }
  };

  const seen = useRef<number | null>(null);
  useEffect(() => {
    if (!incoming || seen.current === incoming.id) return;
    seen.current = incoming.id;
    show({ saves: [...incoming.saves], failed: incoming.failed });
  }, [incoming]);

  const apply = (s: GameSave, mode: ImportMode) => {
    if (mode === "sync" && !update && current > 0 && !confirm(t("modpack.replaceConfirm", { count: current }))) return;
    onImport(s, mode);
    toast(update && mode === "sync" ? t("modpack.updatedFromSave", { name: s.name }) : t("modpack.imported", { count: s.mods.filter((m) => !BUILTIN.has(m.name)).length }));
  };

  // Glisser-déposer : fichiers et dossiers.
  const onDrop = async (e: DragEvent) => {
    e.preventDefault();
    setDrag(false);
    const pending = filesFromDataTransfer(e.dataTransfer);
    setBusy(true);
    let files: File[] = [];
    try { files = await pending; } finally { setBusy(false); }
    run(files);
  };

  return (
    <div
      data-modpack-drop
      onDragOver={(e) => { e.preventDefault(); setDrag(true); }}
      onDragLeave={() => setDrag(false)}
      onDrop={onDrop}
      className={clsx("space-y-3 border border-dashed p-3 transition-colors", drag ? "border-zinc-500 bg-zinc-100 dark:bg-zinc-900" : "border-zinc-300 dark:border-zinc-700")}
    >
      <div className="flex flex-wrap items-center gap-2">
        {update ? <RefreshCw className="size-4 text-zinc-400" /> : <Upload className="size-4 text-zinc-400" />}
        <span className="text-sm font-medium">{t(update ? "modpack.updateTitle" : "modpack.importTitle")}</span>
        <Button size="sm" onClick={() => filesRef.current?.click()} disabled={busy}><FileUp className="size-4" />{t("modpack.pickFiles")}</Button>
        <Button size="sm" onClick={() => dirRef.current?.click()} disabled={busy}><FolderOpen className="size-4" />{t("modpack.pickFolder")}</Button>
        <span className="text-xs text-zinc-500">{t("modpack.drop")}</span>
        {busy && <span className="inline-flex items-center gap-1 text-xs text-zinc-500"><Spinner className="size-3.5" />{t("modpack.reading")}</span>}
      </div>
      <p className="text-xs text-zinc-500">{update && <>{t("modpack.updateHint")} </>}{t("modpack.importHint", { games: importableGames() })}</p>
      <input ref={filesRef} type="file" multiple accept=".zip,.json,application/zip,application/json" className="hidden"
        onChange={(e) => { run(Array.from(e.target.files ?? [])); e.target.value = ""; }} />
      <input ref={dirRef} type="file" className="hidden" {...{ webkitdirectory: "", directory: "" }}
        onChange={(e) => { run(Array.from(e.target.files ?? []).filter(relevantAny)); e.target.value = ""; }} />

      {result && (
        <div className="space-y-1">
          <div className="flex items-center justify-between text-xs text-zinc-500">
            <span>{result.saves.length ? t("modpack.found", { count: result.saves.length }) : t("modpack.noneFound")}</span>
            <button type="button" onClick={() => setResult(null)} className="rounded p-0.5 hover:bg-zinc-200 dark:hover:bg-zinc-800"><X className="size-3.5" /></button>
          </div>
          {result.saves.length > 1 && (
            <ul className="max-h-64 divide-y divide-zinc-200 overflow-y-auto border border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
              {result.saves.map((s, i) => {
                const n = s.mods.filter((m) => !BUILTIN.has(m.name)).length;
                return (
                  <li key={i} className="flex items-center gap-2 px-2 py-1.5 text-sm">
                    <span className="min-w-0 flex-1 truncate">{s.name}</span>
                    <span className="text-xs text-zinc-500">{`${gameLabel(s.game)}${s.gameVersion ? " " + s.gameVersion : ""} · `}{n ? t("modpack.mods", { count: n }) : t("modpack.builtinOnly")}</span>
                    <Button size="sm" variant="ghost" onClick={() => apply(s, "merge")}>{t("modpack.merge")}</Button>
                    <Button size="sm" onClick={() => apply(s, "sync")}>{t(update ? "modpack.updateWith" : "modpack.use")}</Button>
                  </li>
                );
              })}
            </ul>
          )}
          {result.failed.length > 0 && <p className="text-xs text-orange-600">{t("modpack.failed", { files: result.failed.slice(0, 5).join(", ") + (result.failed.length > 5 ? "…" : "") })}</p>}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- liste éditable

function ModTable({ mods, onChange }: { mods: ModpackMod[]; onChange: (m: ModpackMod[]) => void }) {
  const { t } = useTranslation();
  const [name, setName] = useState("");
  const patch = (i: number, p: Partial<ModpackMod>) => onChange(mods.map((m, j) => (j === i ? clean({ ...m, ...p }) : m)));
  const add = () => {
    const n = name.trim();
    if (!n || mods.some((m) => m.name === n)) return;
    onChange([...mods, { name: n, version: "" }]);
    setName("");
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <Label>{t("modpack.mods", { count: mods.filter((m) => !BUILTIN.has(m.name)).length })}</Label>
        {mods.length > 0 && <Button size="sm" variant="ghost" className="ml-auto" onClick={() => confirm(t("modpack.clear") + " ?") && onChange([])}>{t("modpack.clear")}</Button>}
      </div>
      {mods.length > 0 && (
        <div className="max-h-[50vh] overflow-auto border border-zinc-200 dark:border-zinc-800">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-zinc-50 text-left text-xs text-zinc-500 dark:bg-zinc-900">
              <tr>
                <th className="px-2 py-1 font-medium">{t("modpack.name")}</th>
                <th className="w-28 px-2 py-1 font-medium">{t("modpack.version")}</th>
                <th className="px-2 py-1 font-medium">{t("modpack.url")}</th>
                <th className="px-2 py-1 font-medium">{t("modpack.note")}</th>
                <th className="w-8" />
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
              {mods.map((m, i) => (
                <tr key={m.name}>
                  <td className="px-2 py-0.5 font-mono text-xs">{m.name}</td>
                  <td className="px-1 py-0.5"><input value={m.version} onChange={(e) => patch(i, { version: e.target.value.trim() })} placeholder={t("modpack.anyVersion")} className={cell} /></td>
                  <td className="px-1 py-0.5"><input value={m.url ?? ""} onChange={(e) => patch(i, { url: e.target.value.trim() })} placeholder="https://…" className={clsx(cell, m.url && !safeUrl(m.url) && "text-red-600")} /></td>
                  <td className="px-1 py-0.5"><input value={m.note ?? ""} onChange={(e) => patch(i, { note: e.target.value })} className={cell} /></td>
                  <td className="px-1 py-0.5">
                    <button type="button" onClick={() => onChange(mods.filter((_, j) => j !== i))} title={t("modpack.remove")} className="rounded p-1 text-zinc-400 hover:bg-zinc-200 hover:text-red-600 dark:hover:bg-zinc-800"><Trash2 className="size-3.5" /></button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); add(); }}>
        <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("modpack.addMod")} className="flex-1 font-mono" />
        <Button type="submit" disabled={!name.trim()}><Plus className="size-4" />{t("modpack.add")}</Button>
      </form>
    </div>
  );
}

const cell = "w-full rounded border border-transparent bg-transparent px-1.5 py-1 text-xs outline-none hover:border-zinc-200 focus:border-zinc-400 dark:hover:border-zinc-700";

const clean = (m: ModpackMod): ModpackMod => {
  const out: ModpackMod = { name: m.name, version: m.version };
  if (m.url) out.url = m.url;
  if (m.note) out.note = m.note;
  return out;
};
