import clsx from "clsx";
import { Loader2, X } from "lucide-react";
import { createContext, useCallback, useContext, useEffect, useState, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { TAG_COLORS } from "../lib/format";

type Variant = "primary" | "secondary" | "ghost" | "danger";

export function Button({
  variant = "secondary", size = "md", loading, className, children, ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: "sm" | "md"; loading?: boolean }) {
  return (
    <button
      {...props}
      disabled={props.disabled || loading}
      className={clsx(
        "inline-flex items-center justify-center gap-1.5 rounded font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-400",
        size === "sm" ? "h-8 px-2.5 text-sm" : "h-9 px-3.5 text-sm",
        variant === "primary" && "bg-zinc-800 text-zinc-100 hover:bg-zinc-700 dark:bg-zinc-200 dark:text-zinc-900 dark:hover:bg-white",
        variant === "secondary" && "border border-zinc-200 bg-white text-zinc-700 hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200 dark:hover:bg-zinc-800",
        variant === "ghost" && "text-zinc-600 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800",
        variant === "danger" && "bg-red-600 text-white hover:bg-red-500",
        className,
      )}
    >
      {loading && <Loader2 className="size-4 animate-spin" />}
      {children}
    </button>
  );
}

// Largeur pleine par défaut, sauf si l'appelant fixe sa propre largeur (w-auto, w-40...).
const width = (className?: string) => (/(^|\s)w-/.test(className ?? "") ? "" : "w-full");
const field = "rounded border border-zinc-200 bg-white px-3 py-2 text-sm outline-none transition focus:border-zinc-500 focus:ring-2 focus:ring-zinc-500/20 dark:border-zinc-700 dark:bg-zinc-900";

export function Input({ className, ...p }: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...p} className={clsx(field, width(className), "h-9", className)} />;
}

export function Textarea({ className, ...p }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...p} className={clsx(field, width(className), className)} />;
}

export function Select({ className, children, ...p }: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...p} className={clsx(field, width(className), "h-9 pr-8", className)}>{children}</select>;
}

export function Label({ children, hint }: { children: ReactNode; hint?: ReactNode }) {
  return (
    <span className="mb-1 block text-xs font-medium text-zinc-600 dark:text-zinc-400">
      {children}
      {hint && <span className="ml-1 font-normal text-zinc-400">{hint}</span>}
    </span>
  );
}

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={clsx("size-5 animate-spin text-zinc-400", className)} />;
}

export function PageSpinner() {
  return <div className="flex justify-center py-24"><Spinner className="size-8" /></div>;
}

export function Avatar({ src, name, size = 32 }: { src?: string | null; name: string; size?: number }) {
  const [broken, setBroken] = useState(false);
  if (src && !broken) {
    return <img src={src} alt="" width={size} height={size} onError={() => setBroken(true)} className="shrink-0 rounded-full object-cover" style={{ width: size, height: size }} />;
  }
  return (
    <span className="inline-flex shrink-0 items-center justify-center rounded-full bg-zinc-300 font-semibold text-zinc-700 dark:bg-zinc-700 dark:text-zinc-200" style={{ width: size, height: size, fontSize: size * 0.42 }}>
      {name.slice(0, 1).toUpperCase()}
    </span>
  );
}

export function TagChip({ name, color, onRemove, onClick, active }: { name: string; color: string; onRemove?: () => void; onClick?: () => void; active?: boolean }) {
  return (
    <span
      onClick={onClick ? (e) => { e.stopPropagation(); onClick(); } : undefined}
      className={clsx(
        "inline-flex items-center gap-1.5 rounded-sm bg-zinc-200 px-1.5 py-px text-xs text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300",
        onClick && "cursor-pointer hover:bg-zinc-300 dark:hover:bg-zinc-700",
        active && "outline outline-1 outline-zinc-500",
      )}
    >
      <span className={clsx("size-1.5 rounded-full", TAG_COLORS[color] ?? TAG_COLORS.slate)} />
      {name}
      {onRemove && (
        <button type="button" onClick={(e) => { e.stopPropagation(); onRemove(); }} className="opacity-60 hover:opacity-100">
          <X className="size-3" />
        </button>
      )}
    </span>
  );
}

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={clsx("border border-zinc-300 dark:border-zinc-800", className)}>{children}</div>;
}

export function Modal({ open, onClose, title, children, footer, wide }: { open: boolean; onClose: () => void; title: ReactNode; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-950/50 p-4 backdrop-blur-sm" onMouseDown={onClose}>
      <div onMouseDown={(e) => e.stopPropagation()} className={clsx("w-full rounded-md bg-white shadow dark:bg-zinc-900 dark:ring-1 dark:ring-zinc-800", wide ? "max-w-2xl" : "max-w-md")}>
        <div className="flex items-center justify-between border-b border-zinc-200 px-5 py-3 dark:border-zinc-800">
          <h2 className="font-semibold">{title}</h2>
          <button onClick={onClose} className="rounded p-1 text-zinc-400 hover:bg-zinc-100 dark:hover:bg-zinc-800"><X className="size-4" /></button>
        </div>
        <div className="px-5 py-4">{children}</div>
        {footer && <div className="flex justify-end gap-2 border-t border-zinc-200 px-5 py-3 dark:border-zinc-800">{footer}</div>}
      </div>
    </div>
  );
}

export function EmptyState({ icon, title, children }: { icon?: ReactNode; title: ReactNode; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 py-16 text-center text-zinc-500">
      {icon && <div className="text-zinc-300 dark:text-zinc-700">{icon}</div>}
      <p className="font-medium text-zinc-700 dark:text-zinc-300">{title}</p>
      {children}
    </div>
  );
}

// ---------------------------------------------------------------- toasts

interface Toast { id: number; text: string; tone: "ok" | "error" }
const ToastCtx = createContext<(text: string, tone?: Toast["tone"]) => void>(() => {});
export const useToast = () => useContext(ToastCtx);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((text: string, tone: Toast["tone"] = "ok") => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, text, tone }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3500);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-[60] flex flex-col gap-2">
        {toasts.map((t) => (
          <div key={t.id} className={clsx("rounded px-4 py-2.5 text-sm font-medium shadow", t.tone === "ok" ? "bg-zinc-900 text-white dark:bg-white dark:text-zinc-900" : "bg-red-600 text-white")}>
            {t.text}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export function useCopy() {
  const toast = useToast();
  return async (text: string, message: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast(message);
    } catch {
      toast("Clipboard error", "error");
    }
  };
}
