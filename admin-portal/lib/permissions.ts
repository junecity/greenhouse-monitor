import type { AdminRole } from "./types";

export function can(role: AdminRole | undefined, action: "customer" | "premium" | "device" | "recovery" | "read"): boolean {
  if (!role) return false;
  if (action === "read") return true;
  if (action === "device") return role === "Owner" || role === "Support";
  return role === "Owner";
}
