"use client";

import Link from "next/link";
import { useState } from "react";
import { usePathname } from "next/navigation";
import { Activity, BadgeDollarSign, ClipboardList, Gauge, Headphones, LogOut, Menu, X, Server, ShieldCheck, Users } from "lucide-react";
import { useAuth } from "./auth-provider";
import { useAdminQuery } from "@/hooks/use-admin-api";
import type { Session } from "@/lib/types";

const navigation = [
  { href: "/", label: "Overview", icon: Gauge },
  { href: "/customers/", label: "Customers", icon: Users },
  { href: "/subscriptions/", label: "Subscriptions", icon: BadgeDollarSign },
  { href: "/devices/", label: "Devices", icon: Server },
  { href: "/operations/", label: "Operations", icon: Activity },
  { href: "/support/", label: "Support", icon: Headphones },
  { href: "/audit/", label: "Audit", icon: ClipboardList },
];

export function AppShell({ title, eyebrow, children, actions }: { title: string; eyebrow?: string; children: React.ReactNode; actions?: React.ReactNode }) {
  const auth = useAuth();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);
  const session = useAdminQuery<Session>("/admin/v1/session");

  if (auth.state === "loading") return <FullState title="Checking administrator session" loading />;
  if (auth.state === "configuration-error") return <FullState title="Portal configuration required" detail="This private build needs its administrator API and identity settings before sign-in can be enabled." />;
  if (auth.state === "unauthenticated") return <SignIn onSignIn={auth.signIn} />;

  return (
    <div className="app-shell">
      <div className="mobile-header"><strong>Greenhouse Monitor</strong><button className="secondary-button" aria-label={menuOpen ? "Close navigation" : "Open navigation"} aria-expanded={menuOpen} aria-controls="admin-navigation" onClick={() => setMenuOpen(!menuOpen)}>{menuOpen ? <X size={20} /> : <Menu size={20} />}Menu</button></div>
      {menuOpen && <button className="menu-backdrop" aria-label="Close navigation" onClick={() => setMenuOpen(false)} />}
      <aside id="admin-navigation" className={`sidebar ${menuOpen ? "is-open" : ""}`}>
        <div className="brand"><span className="brand-mark"><ShieldCheck size={22} /></span><span><strong>Greenhouse Monitor</strong><small>Private administration</small></span></div>
        <nav aria-label="Primary navigation">
          {navigation.slice(0, 4).map(({ href, label, icon: Icon }) => {
            const active = href === "/" ? pathname === "/" : pathname.startsWith(href.replace(/\/$/, ""));
            return <Link key={href} href={href} onClick={() => setMenuOpen(false)} aria-current={active ? "page" : undefined} className={active ? "active" : ""}><Icon size={19} /><span>{label}</span></Link>;
          })}
          <details className="advanced-navigation" open={navigation.slice(4).some(({ href }) => pathname.startsWith(href.replace(/\/$/, "")))}><summary>Tools & diagnostics</summary>{navigation.slice(4).map(({ href, label, icon: Icon }) => <Link key={href} href={href} onClick={() => setMenuOpen(false)} className={pathname.startsWith(href.replace(/\/$/, "")) ? "active" : ""}><Icon size={19} /><span>{label}</span></Link>)}</details>
        </nav>
        <div className="session-card"><span className="status-dot" /><div><strong>{session.data?.role || "Administrator"}</strong><small>{session.data?.email || "Verified session"}</small></div><button className="icon-button" onClick={() => void auth.signOut()} aria-label="Sign out"><LogOut size={18} /></button></div>
      </aside>
      <main className="main-content">
        <header className="page-header"><div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h1>{title}</h1></div>{actions && <div className="header-actions">{actions}</div>}</header>
        {children}
      </main>
    </div>
  );
}

function SignIn({ onSignIn }: { onSignIn: () => Promise<void> }) {
  return <main className="auth-screen"><section className="auth-card"><div className="auth-icon"><ShieldCheck size={28} /></div><p className="eyebrow">Authorized personnel only</p><h1>Greenhouse Monitor Admin</h1><p>Customer, device, subscription, and system operations are protected by mandatory multi-factor authentication.</p><button className="primary-button" onClick={() => void onSignIn()}>Continue to secure sign-in</button><small>There is no public administrator registration.</small></section></main>;
}

function FullState({ title, detail, loading }: { title: string; detail?: string; loading?: boolean }) {
  return <main className="auth-screen"><section className="auth-card">{loading && <div className="spinner" />}<h1>{title}</h1>{detail && <p>{detail}</p>}</section></main>;
}
