"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { getSession, getToken, setSession, setToken } from "../lib/api";

/** Grouped navigation (design.md §4.1), scoped to routes that exist and are live-wired. */
const NAV: { label: string; links: [string, string][] }[] = [
  { label: "Overview", links: [["Dashboard", "/dashboard"]] },
  {
    label: "Money",
    links: [
      ["Accounts", "/accounts"],
      ["Transfers", "/transfers"],
      ["Ledger", "/ledger"],
      ["Reconciliation", "/reconciliation"],
    ],
  },
  {
    label: "Fraud",
    links: [
      ["Cases", "/fraud-cases"],
      ["Risk Profiles", "/risk-profiles"],
      ["ML Monitoring", "/ml"],
    ],
  },
  { label: "Payment Rails", links: [["Webhooks", "/webhooks"]] },
  {
    label: "Compliance",
    links: [
      ["Evidence", "/evidence"],
      ["Audit Logs", "/audit-logs"],
    ],
  },
  { label: "Organisation", links: [["Tenant Admin", "/admin"]] },
];

/** Environment badge (§6.2). Sandbox unless the deployment says otherwise. */
const ENVIRONMENT = (process.env.NEXT_PUBLIC_ENVIRONMENT ?? "sandbox").toLowerCase();

export default function Shell({ children, active }: { children: ReactNode; active: string }) {
  const router = useRouter();
  const [ready, setReady] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [session, setSess] = useState<{ email: string; role: string; tenantId: string } | null>(null);

  useEffect(() => {
    if (!getToken()) {
      router.replace("/login");
      return;
    }
    setSess(getSession());
    setReady(true);
  }, [router]);

  if (!ready) return null;

  function logout() {
    setToken(null);
    setSession(null);
    router.replace("/login");
  }

  const activeTitle =
    NAV.flatMap((g) => g.links).find(([, href]) => href === active)?.[0] ?? "TrustLedger";

  return (
    <div className="shell">
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <aside className={`sidebar${menuOpen ? " open" : ""}`} aria-label="Main navigation">
        <div className="brand">
          <span className="logo" aria-hidden>
            TL
          </span>
          TrustLedger
        </div>
        {NAV.map((group) => (
          <div className="navgroup" key={group.label}>
            <div className="navlabel">{group.label}</div>
            <nav className="sidenav">
              {group.links.map(([label, href]) => (
                <Link
                  key={href}
                  href={href}
                  className={active === href ? "active" : ""}
                  aria-current={active === href ? "page" : undefined}
                  onClick={() => setMenuOpen(false)}
                >
                  {label}
                </Link>
              ))}
            </nav>
          </div>
        ))}
        <div className="sidebar-footer">
          <div className="userline">
            <span className="avatar" aria-hidden>
              {(session?.email?.[0] ?? "?").toUpperCase()}
            </span>
            <span className="who">
              <span className="email">{session?.email ?? "signed in"}</span>
              <span className="role">{session?.role?.toLowerCase() ?? ""}</span>
            </span>
          </div>
          <button className="ghost" onClick={logout}>
            Log out
          </button>
        </div>
      </aside>

      <div className="content">
        <header className="topnav">
          <button
            className="ghost menu-btn"
            aria-label="Open navigation"
            onClick={() => setMenuOpen((v) => !v)}
          >
            ☰
          </button>
          <span className="crumb">{activeTitle}</span>
          <span className={`envbadge ${ENVIRONMENT}`}>{ENVIRONMENT}</span>
          <span className="spacer" />
          {session && (
            <span className="tenantchip" title={session.tenantId}>
              Tenant <b className="mono">{session.tenantId.slice(0, 8)}…</b>
            </span>
          )}
          <Link href="/transfers/new" className="btn" style={{ textDecoration: "none" }}>
            Create transfer
          </Link>
        </header>
        <main className="page" id="main">
          {children}
        </main>
      </div>
    </div>
  );
}
