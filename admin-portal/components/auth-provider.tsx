"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { User, UserManager, WebStorageStateStore } from "oidc-client-ts";
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
    manager = new UserManager({
      authority: config.authority,
      client_id: config.clientId,
      redirect_uri: config.redirectUri,
      post_logout_redirect_uri: config.logoutUri,
      response_type: "code",
      scope: "openid email profile",
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
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
    void instance.getUser().then((value) => {
      if (value && !value.expired) { setUser(value); setState("authenticated"); }
      else { setUser(null); setState("unauthenticated"); }
    }).catch(() => setState("unauthenticated"));
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
