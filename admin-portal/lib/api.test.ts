import { afterEach, describe, expect, it, vi } from "vitest";
import { AdminApiError, adminRequest } from "./api";

describe("admin API client", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("sends the bearer token and disables caching", async () => {
    vi.stubEnv("NEXT_PUBLIC_ADMIN_API_URL", "https://api.example.test");
    vi.stubEnv("NEXT_PUBLIC_COGNITO_AUTHORITY", "https://issuer.example.test/pool");
    vi.stubEnv("NEXT_PUBLIC_COGNITO_HOSTED_UI_BASE_URL", "https://login.example.test");
    vi.stubEnv("NEXT_PUBLIC_COGNITO_CLIENT_ID", "client");
    vi.stubEnv("NEXT_PUBLIC_COGNITO_REDIRECT_URI", "https://admin.example.test/auth/callback/");
    vi.stubEnv("NEXT_PUBLIC_COGNITO_LOGOUT_URI", "https://admin.example.test/");
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await adminRequest("token-value", "/admin/v1/session");
    expect(fetchMock).toHaveBeenCalledWith("https://api.example.test/admin/v1/session", expect.objectContaining({
      cache: "no-store",
      headers: expect.objectContaining({ authorization: "Bearer token-value" }),
    }));
  });

  it("returns a typed error for a denied request", async () => {
    vi.stubEnv("NEXT_PUBLIC_ADMIN_API_URL", "https://api.example.test");
    vi.stubEnv("NEXT_PUBLIC_COGNITO_AUTHORITY", "issuer");
    vi.stubEnv("NEXT_PUBLIC_COGNITO_HOSTED_UI_BASE_URL", "hosted-ui");
    vi.stubEnv("NEXT_PUBLIC_COGNITO_CLIENT_ID", "client");
    vi.stubEnv("NEXT_PUBLIC_COGNITO_REDIRECT_URI", "callback");
    vi.stubEnv("NEXT_PUBLIC_COGNITO_LOGOUT_URI", "logout");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: "insufficient_role" }), { status: 403 })));
    await expect(adminRequest("token", "/admin/v1/customers")).rejects.toEqual(expect.objectContaining<Partial<AdminApiError>>({ status: 403, code: "insufficient_role" }));
  });
});
