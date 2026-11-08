"use client";

import { useCallback, useEffect, useState } from "react";
import { adminRequest } from "@/lib/api";
import { useAuth } from "@/components/auth-provider";

export function useAdminQuery<T>(path: string) {
  const auth = useAuth();
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const reload = useCallback(async () => {
    if (!auth.token) { setLoading(false); return; }
    setLoading(true); setError(null);
    try { setData(await adminRequest<T>(auth.token, path)); }
    catch (value) { setError(value instanceof Error ? value.message : "request_failed"); }
    finally { setLoading(false); }
  }, [auth.token, path]);
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      await Promise.resolve();
      if (!auth.token) { if (!cancelled) setLoading(false); return; }
      if (!cancelled) { setLoading(true); setError(null); }
      try {
        const value = await adminRequest<T>(auth.token, path);
        if (!cancelled) setData(value);
      } catch (value) {
        if (!cancelled) setError(value instanceof Error ? value.message : "request_failed");
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => { cancelled = true; };
  }, [auth.token, path]);
  return { data, error, loading, reload };
}

export function useAdminMutation() {
  const auth = useAuth();
  const [pending, setPending] = useState(false);
  const run = useCallback(async <T,>(path: string, init: RequestInit): Promise<T> => {
    if (!auth.token) throw new Error("session_required");
    setPending(true);
    try { return await adminRequest<T>(auth.token, path, init); }
    finally { setPending(false); }
  }, [auth.token]);
  return { run, pending };
}
