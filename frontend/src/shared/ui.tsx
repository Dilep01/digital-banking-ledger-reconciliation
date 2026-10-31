import { useEffect, useRef } from "react";
import type { ReactNode } from "react";
import { Layers3, RefreshCw, X, AlertCircle } from "lucide-react";
import { message } from "./data";

export function Status({ value }: { value: string }) {
  return (
    <span
      className={
        "status " +
        ([
          "ACTIVE",
          "POSTED",
          "COMPLETED",
          "DELIVERED",
          "APPROVED",
          "RESOLVED",
          "SUCCESS",
        ].includes(value)
          ? "positive"
          : ["REJECTED", "FAILED", "FROZEN"].includes(value)
            ? "negative"
            : "neutral")
      }
    >
      <i />
      {value.replaceAll("_", " ")}
    </span>
  );
}

export function Empty({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="empty">
      <Layers3 size={30} />
      <h3>{title}</h3>
      <p>{detail}</p>
    </div>
  );
}

export function ErrorBox({ error }: { error: unknown }) {
  return (
    <div className="error-box" role="alert">
      <AlertCircle size={18} />
      {message(error)}
    </div>
  );
}

export function Loading() {
  return (
    <div className="loading">
      <RefreshCw size={18} className="spin" />
      Loading workspace…
    </div>
  );
}

export function Table<T extends object>({
  rows,
  columns,
  empty = "No records yet.",
}: {
  rows: T[];
  columns: { label: string; cell: (row: T) => ReactNode }[];
  empty?: string;
}) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.label}>{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={String((r as { id?: string }).id || i)}>
              {columns.map((c) => (
                <td key={c.label}>{c.cell(r)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length === 0 && <p className="table-empty">{empty}</p>}
    </div>
  );
}

export function Modal({
  title,
  children,
  onClose,
  wide = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement;
    ref.current?.focus();
    const close = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeRef.current();
      if (e.key === "Tab") {
        const nodes = ref.current?.querySelectorAll<HTMLElement>(
          "button,input,select,textarea,a[href]",
        );
        if (!nodes?.length) return;
        const first = nodes[0],
          last = nodes[nodes.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("keydown", close);
      previous?.focus();
    };
  }, []);
  return (
    <div className="modal-backdrop">
      <div
        className={"modal " + (wide ? "wide" : "")}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        ref={ref}
      >
        <div className="panel-heading">
          <h2>{title}</h2>
          <button
            className="icon-button"
            aria-label="Close dialog"
            onClick={onClose}
          >
            <X size={20} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

export function Evidence({ data }: { data: unknown }) {
  return <pre className="evidence">{JSON.stringify(data, null, 2)}</pre>;
}
