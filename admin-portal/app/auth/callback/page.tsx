"use client";

import { useEffect } from "react";
import { useAuth } from "@/components/auth-provider";

export default function CallbackPage() {
  const { completeSignIn } = useAuth();
  // The context value changes as authentication state initializes. Depend on the
  // stable callback itself so a one-time authorization code is never consumed twice.
  useEffect(() => { void completeSignIn(); }, [completeSignIn]);
  return <main className="auth-screen"><div className="auth-card"><div className="spinner" /><h1>Completing secure sign-in</h1><p>Please wait while your administrator session is verified.</p></div></main>;
}
