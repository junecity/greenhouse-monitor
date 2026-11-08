export function formatNumber(value: number | null | undefined): string {
  return value === null || value === undefined ? "Unavailable" : new Intl.NumberFormat().format(value);
}

export function formatMoney(value: number | null | undefined): string {
  return value === null || value === undefined ? "Unavailable" : new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(value);
}

export function formatDate(epoch?: number | null): string {
  return epoch ? new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeStyle: "short" }).format(epoch * 1000) : "Not reported";
}

export function humanDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return hours ? `${hours}h ${minutes}m` : `${minutes}m`;
}
