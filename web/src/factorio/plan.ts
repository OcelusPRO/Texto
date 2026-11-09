import { decodePlan, encodePlan } from "@jensforstmann/factorio-blueprint-tools";
import type { PlanModel } from "./model";

type Json = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

/** Chaîne de plan d'un nœud (un plan d'un livre est ré-encodé seul, sans son index dans le livre). */
export function nodeString(node: PlanModel): string {
  const { index: _index, ...rest } = node.raw as Json;
  return encodePlan(rest as never);
}

/**
 * Remplace, dans le plan racine, le nœud situé à [path] par le plan décodé de [bpString] (export de FBE)
 * et renvoie la chaîne du plan racine complet. Chemin vide : la racine entière est remplacée.
 */
export function replaceNode(root: PlanModel, path: number[], bpString: string): string {
  const replacement = decodePlan(bpString.trim()) as unknown as Json;
  if (path.length === 0) return encodePlan(replacement as never);
  const copy = structuredClone(root.raw) as Json;
  let book: Json = copy.blueprint_book;
  for (let i = 0; i < path.length - 1; i++) {
    const child = (book.blueprints as Json[]).find((b) => b.index === path[i]);
    if (!child?.blueprint_book) throw new Error("path");
    book = child.blueprint_book;
  }
  const list = book.blueprints as Json[];
  const pos = list.findIndex((b) => b.index === path[path.length - 1]);
  if (pos < 0) throw new Error("path");
  list[pos] = { ...replacement, index: list[pos].index };
  return encodePlan(copy as never);
}
