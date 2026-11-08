import { portalConfig } from "./config";

export class AdminApiError extends Error {
  constructor(public readonly status: number, public readonly code: string) {
    super(code);
  }
}

export async function adminRequest<T>(token: string, path: string, init?: RequestInit): Promise<T> {
  const config = portalConfig();
  if (!config) throw new AdminApiError(0, "portal_not_configured");
  const result = await fetch(`${config.apiUrl.replace(/\/$/, "")}${path}`, {
    ...init,
    cache: "no-store",
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
      ...(init?.headers || {}),
    },
  });
  const body = result.status === 204 ? {} : await result.json().catch(() => ({}));
  if (!result.ok) throw new AdminApiError(result.status, body.error || "request_failed");
  return body as T;
}
