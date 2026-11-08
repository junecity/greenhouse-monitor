"use client";

import { useEffect } from "react";
import { useAuth } from "@/components/auth-provider";

export default function CallbackPage() {
  const auth = useAuth();
  useEffect(() => { void auth.completeSignIn(); }, [auth]);
  return <main className="auth-screen"><div className="auth-card"><div className="spinner" /><h1>Completing secure sign-in</h1><p>Please wait while your administrator session is verified.</p></div></main>;
}
