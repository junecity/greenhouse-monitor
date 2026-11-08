"use client";

import { AlertTriangle, LoaderCircle, Search } from "lucide-react";
import type { TrendPoint } from "@/lib/types";

export function Panel({ title, description, children, className = "" }: { title?: string; description?: string; children: React.ReactNode; className?: string }) {
  return <section className={`panel ${className}`}>{(title || description) && <header className="panel-header"><div>{title && <h2>{title}</h2>}{description && <p>{description}</p>}</div></header>}<div className="panel-body">{children}</div></section>;
}

export function MetricCard({ label, value, hint, tone = "default" }: { label: string; value: string | number; hint?: string; tone?: "default" | "positive" | "warning" | "info" }) {
  return <article className={`metric-card ${tone}`}><p>{label}</p><strong>{value}</strong>{hint && <small>{hint}</small>}</article>;
}

export function StatusBadge({ value }: { value?: string | boolean | null }) {
  const label = typeof value === "boolean" ? (value ? "Online" : "Offline") : value || "Unknown";
  const normalized = label.toString().toLowerCase();
  const tone = /active|online|verified|success|ok/.test(normalized) ? "success" : /offline|expired|suspended|revoked|failed|alarm/.test(normalized) ? "danger" : /pending|grace|unknown|unavailable/.test(normalized) ? "warning" : "neutral";
  return <span className={`badge ${tone}`}>{label}</span>;
}

export function LoadingPanel() {
  return <div className="state-panel" role="status"><LoaderCircle className="spin" /><strong>Loading current data</strong><span>Retrieving verified backend records.</span></div>;
}

export function ErrorPanel({ error, onRetry }: { error: string; onRetry?: () => void }) {
  return <div className="state-panel error" role="alert"><AlertTriangle /><strong>Data could not be loaded</strong><span>{friendlyError(error)}</span>{onRetry && <button className="secondary-button" onClick={onRetry}>Try again</button>}</div>;
}

export function EmptyState({ title = "No records yet", detail = "There is no backend data to display for this view." }: { title?: string; detail?: string }) {
  return <div className="state-panel"><strong>{title}</strong><span>{detail}</span></div>;
}

export function SearchField({ value, onChange, placeholder }: { value: string; onChange: (value: string) => void; placeholder: string }) {
  return <label className="search-field"><Search size={18} /><span className="sr-only">Search</span><input value={value} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} /></label>;
}

export function TrendChart({ points, label, suffix = "" }: { points: TrendPoint[]; label: string; suffix?: string }) {
  if (!points.length) return <EmptyState title={`No ${label.toLowerCase()} trend yet`} detail="The chart will appear after real records have been collected." />;
  const width = 420, height = 120, inset = 10;
  const values = points.map((point) => point.value);
  const min = Math.min(...values), max = Math.max(...values);
  const range = Math.max(1, max - min);
  const path = points.map((point, index) => {
    const x = inset + (index * (width - inset * 2)) / Math.max(1, points.length - 1);
    const y = height - inset - ((point.value - min) / range) * (height - inset * 2);
    return `${index ? "L" : "M"}${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(" ");
  return <div className="trend-chart"><div className="trend-label"><span>{label}</span><strong>{points.at(-1)?.value}{suffix}</strong></div><svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${label} trend`} preserveAspectRatio="none"><defs><linearGradient id={`fill-${label.replace(/\W/g, "")}`} x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#ffb347" stopOpacity=".34" /><stop offset="1" stopColor="#ffb347" stopOpacity="0" /></linearGradient></defs><path d={`${path} L${width - inset},${height - inset} L${inset},${height - inset} Z`} fill={`url(#fill-${label.replace(/\W/g, "")})`} /><path d={path} fill="none" stroke="#ffb347" strokeWidth="3" strokeLinecap="round" /></svg><div className="trend-axis"><span>{points[0]?.date}</span><span>{points.at(-1)?.date}</span></div></div>;
}

export function ConfirmButton({ children, message, onConfirm, disabled, danger = false }: { children: React.ReactNode; message: string; onConfirm: () => void; disabled?: boolean; danger?: boolean }) {
  return <button className={danger ? "danger-button" : "secondary-button"} disabled={disabled} onClick={() => { if (window.confirm(message)) onConfirm(); }}>{children}</button>;
}

function friendlyError(code: string): string {
  const values: Record<string, string> = {
    session_expired: "Your administrator session expired. Sign in again.",
    insufficient_role: "Your administrator role does not permit this action.",
    dependency_error: "A protected backend dependency is temporarily unavailable.",
    request_failed: "The request failed. Check the service health and try again.",
  };
  return values[code] || code.replaceAll("_", " ");
}
