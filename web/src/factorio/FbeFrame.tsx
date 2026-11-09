import { useEffect, useImperativeHandle, useMemo, useRef, useState, type Ref } from "react";
import { useConfig } from "../lib/session";

export interface FbeHandle {
  /** Demande à FBE une capture du plan affiché (réessaie tant que FBE n'a pas fini de charger). */
  picture(resolution: number): Promise<Blob | null>;
}

/**
 * Factorio Blueprint Editor auto-hébergé (service `texto_fbe`, patché par fbe/texto-embed.patch) en iframe :
 * rendu fidèle au jeu et édition en direct. Il remplit son conteneur (position absolue).
 *
 * - Le plan initial est passé par le fragment `#source=<chaîne>`.
 * - FBE est ouvert avec `?embedder=<notre origine>` : à chaque export (Ctrl+C) il envoie
 *   `{ type: "fbe:export", blueprint }` par postMessage, et seulement à notre origine.
 * - Un plan modifié côté Texto est rechargé par `{ type: "fbe:load", blueprint }`.
 * - `{ type: "fbe:picture", id, resolution }` demande une capture, renvoyée en `{ type: "fbe:picture", id, blob }`.
 */
export function FbeFrame({ content, onExport, visible = true, className, handle }: {
  content: string;
  onExport?: (bp: string) => void;
  visible?: boolean;
  className?: string;
  handle?: Ref<FbeHandle>;
}) {
  const { data: cfg } = useConfig();
  const ref = useRef<HTMLIFrameElement>(null);
  const exportRef = useRef(onExport);
  exportRef.current = onExport;
  // Dernier plan connu de FBE (chargé ou exporté) : évite de lui renvoyer ce qu'il vient de produire.
  const current = useRef<string | null>(null);
  const pictures = useRef(new Map<number, (blob: Blob | null) => void>());
  const base = cfg?.fbeUrl ?? null;
  const fbeOrigin = useMemo(() => (base ? new URL(base, location.href).origin : null), [base]);
  const [ready, setReady] = useState(false);

  // L'URL de l'iframe n'est calculée qu'une fois : les changements suivants passent par postMessage.
  const [src] = useState(() => {
    const bp = content.trim();
    current.current = bp || null;
    return base ? `${base}/?embedder=${encodeURIComponent(location.origin)}${bp ? `#source=${bp}` : ""}` : "";
  });

  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      if (e.origin !== fbeOrigin || e.source !== ref.current?.contentWindow) return;
      const data = e.data as { type?: unknown; blueprint?: unknown; id?: unknown; blob?: unknown; error?: unknown } | null;
      if (data?.type === "fbe:export" && typeof data.blueprint === "string") {
        current.current = data.blueprint.trim();
        exportRef.current?.(current.current);
      } else if (data?.type === "fbe:picture" && typeof data.id === "number") {
        pictures.current.get(data.id)?.(data.blob instanceof Blob ? data.blob : null);
        pictures.current.delete(data.id);
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [fbeOrigin]);

  useEffect(() => {
    const bp = content.trim();
    if (!bp || bp === current.current || !fbeOrigin || !ready) return;
    current.current = bp;
    ref.current?.contentWindow?.postMessage({ type: "fbe:load", blueprint: bp }, fbeOrigin);
  }, [content, fbeOrigin, ready]);

  useImperativeHandle(handle, () => ({
    async picture(resolution: number) {
      if (!fbeOrigin) return null;
      for (let attempt = 0; attempt < 8; attempt++) {
        const id = Date.now() + attempt;
        const blob = await new Promise<Blob | null>((resolve) => {
          pictures.current.set(id, resolve);
          ref.current?.contentWindow?.postMessage({ type: "fbe:picture", id, resolution }, fbeOrigin);
          setTimeout(() => { if (pictures.current.delete(id)) resolve(null); }, 3500);
        });
        if (blob) return blob;
      }
      return null;
    },
  }), [fbeOrigin]);

  if (!src) return null;
  return (
    <iframe
      ref={ref}
      src={src}
      title="Factorio Blueprint Editor"
      onLoad={() => setReady(true)}
      // Masquée sans être démontée ni redimensionnée : FBE garde son état (pas de rechargement).
      className={className ?? "absolute inset-0 h-full w-full border border-zinc-300 dark:border-zinc-800"}
      style={visible ? undefined : { visibility: "hidden", pointerEvents: "none" }}
      allow="clipboard-read; clipboard-write"
    />
  );
}
