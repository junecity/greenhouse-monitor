"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { InMemoryWebStorage, User, UserManager, WebStorageStateStore } from "oidc-client-ts";
import { portalConfig } from "@/lib/config";

type AuthState = "loading" | "authenticated" | "unauthenticated" | "configuration-error";
type AuthContextValue = {
  state: AuthState;
  user: User | null;
  token: string | null;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
  completeSignIn: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);
let manager: UserManager | null = null;

function userManager(): UserManager | null {
  const config = portalConfig();
  if (!config || typeof window === "undefined") return null;
  if (!manager) {
    let browserStore: Storage;
    try {
      browserStore = window.sessionStorage || new InMemoryWebStorage();
    } catch {
      browserStore = new InMemoryWebStorage();
    }
    manager = new UserManager({
      authority: config.authority,
      // Cognito's issuer discovery document does not include browser CORS headers.
      // Supplying the same stack-owned endpoints avoids a cross-origin discovery
      // fetch while preserving authorization-code + PKCE and issuer validation.
      metadata: {
        issuer: config.authority,
        authorization_endpoint: `${config.hostedUiBaseUrl}/oauth2/authorize`,
        token_endpoint: `${config.hostedUiBaseUrl}/oauth2/token`,
        userinfo_endpoint: `${config.hostedUiBaseUrl}/oauth2/userInfo`,
        end_session_endpoint: `${config.hostedUiBaseUrl}/logout`,
        jwks_uri: `${config.authority}/.well-known/jwks.json`,
        revocation_endpoint: `${config.hostedUiBaseUrl}/oauth2/revoke`,
      },
      client_id: config.clientId,
      redirect_uri: config.redirectUri,
      post_logout_redirect_uri: config.logoutUri,
      response_type: "code",
      scope: "openid email profile",
      userStore: new WebStorageStateStore({ store: browserStore }),
      automaticSilentRenew: false,
      loadUserInfo: false,
    });
  }
  return manager;
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>(() => portalConfig() ? "loading" : "configuration-error");
  const [user, setUser] = useState<User | null>(null);

  useEffect(() => {
    const instance = userManager();
    if (!instance) return;
    let active = true;
    const timeout = window.setTimeout(() => {
      if (active) setState("unauthenticated");
    }, 2000);
    void instance.getUser().then((value) => {
      if (!active) return;
      window.clearTimeout(timeout);
      if (value && !value.expired) { setUser(value); setState("authenticated"); }
      else { setUser(null); setState("unauthenticated"); }
    }).catch(() => {
      if (active) { window.clearTimeout(timeout); setState("unauthenticated"); }
    });
    return () => { active = false; window.clearTimeout(timeout); };
  }, []);

  const signIn = useCallback(async () => {
    const instance = userManager();
    if (!instance) { setState("configuration-error"); return; }
    await instance.signinRedirect();
  }, []);

  const signOut = useCallback(async () => {
    const instance = userManager();
    if (!instance) return;
    await instance.removeUser();
    setUser(null);
    setState("unauthenticated");
    await instance.signoutRedirect();
  }, []);

  const completeSignIn = useCallback(async () => {
    const instance = userManager();
    if (!instance) { setState("configuration-error"); return; }
    try {
      const value = await instance.signinRedirectCallback();
      setUser(value);
      setState("authenticated");
      window.location.replace("/");
    } catch {
      setState("unauthenticated");
      window.location.replace("/?auth=failed");
    }
  }, []);

  const value = useMemo<AuthContextValue>(() => ({
    state, user, token: user?.access_token || null, signIn, signOut, completeSignIn,
  }), [state, user, signIn, signOut, completeSignIn]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
