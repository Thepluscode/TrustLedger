"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { api, getSession, getToken, setSession, setToken } from "../lib/api";
import type { OrgUnit } from "../lib/types";
import CommandPalette from "./CommandPalette";
import PlatformProductionGate from "./PlatformProductionGate";

type IconName =
  | "overview"
  | "spark"
  | "accounts"
  | "transfer"
  | "ledger"
  | "reconcile"
  | "settlement"
  | "shield"
  | "risk"
  | "model"
  | "webhook"
  | "certificate"
  | "launch"
  | "evidence"
  | "audit"
  | "key"
  | "monitor"
  | "building"
  | "users"
  | "org"
  | "plus";

const ICON_PATHS: Record<IconName, ReactNode> = {
  overview: <><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/></>,
  spark: <><path d="m12 3 1.2 4.2L17 9l-3.8 1.8L12 15l-1.2-4.2L7 9l3.8-1.8L12 3Z"/><path d="m5 15 .7 2.3L8 18l-2.3.7L5 21l-.7-2.3L2 18l2.3-.7L5 15Z"/></>,
  accounts: <><path d="M3 9h18"/><path d="M5 9V6l7-3 7 3v3"/><path d="M6 9v8M10 9v8M14 9v8M18 9v8"/><path d="M3 21h18M4 17h16"/></>,
  transfer: <><path d="M4 7h13"/><path d="m14 4 3 3-3 3"/><path d="M20 17H7"/><path d="m10 14-3 3 3 3"/></>,
  ledger: <><path d="M5 3h12a2 2 0 0 1 2 2v16H7a2 2 0 0 1-2-2V3Z"/><path d="M7 3v18M10 8h6M10 12h6M10 16h4"/></>,
  reconcile: <><path d="M20 7h-7a4 4 0 0 0-4 4v1"/><path d="m17 4 3 3-3 3"/><path d="M4 17h7a4 4 0 0 0 4-4v-1"/><path d="m7 20-3-3 3-3"/></>,
  settlement: <><path d="M4 6h16v12H4z"/><path d="M8 10h8M8 14h5"/><path d="M7 3v3M17 3v3M7 18v3M17 18v3"/></>,
  shield: <><path d="M12 3 20 6v5c0 5-3.4 8.7-8 10-4.6-1.3-8-5-8-10V6l8-3Z"/><path d="m9 12 2 2 4-5"/></>,
  risk: <><path d="M12 3 2.8 20h18.4L12 3Z"/><path d="M12 9v4M12 17h.01"/></>,
  model: <><circle cx="7" cy="7" r="3"/><circle cx="17" cy="7" r="3"/><circle cx="12" cy="17" r="3"/><path d="m9.5 8.7 2 5M14.5 8.7l-2 5M10 7h4"/></>,
  webhook: <><path d="M6.5 7a4 4 0 1 1 6.8 2.8"/><path d="M17.5 7a4 4 0 1 1-4.8 6.2"/><path d="M12 19a4 4 0 1 1-2-7.5"/><circle cx="12" cy="12" r="2"/></>,
  certificate: <><path d="M6 3h12v14H6z"/><path d="M9 7h6M9 11h6"/><path d="m9 17-1 4 4-2 4 2-1-4"/></>,
  launch: <><path d="M14 4h6v6"/><path d="m20 4-9 9"/><path d="M19 13v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h6"/></>,
  evidence: <><path d="M6 3h9l3 3v15H6z"/><path d="M15 3v4h4M9 11h6M9 15h6"/></>,
  audit: <><circle cx="11" cy="11" r="7"/><path d="m16 16 5 5M11 7v4l3 2"/></>,
  key: <><circle cx="8" cy="12" r="4"/><path d="M12 12h9M17 12v3M20 12v2"/></>,
  monitor: <><path d="M3 4h18v13H3z"/><path d="M8 21h8M12 17v4"/><path d="m7 12 3-3 3 3 4-5"/></>,
  building: <><path d="M4 21V7l8-4 8 4v14"/><path d="M8 10h2M14 10h2M8 14h2M14 14h2M10 21v-3h4v3"/></>,
  users: <><circle cx="9" cy="8" r="4"/><path d="M3 21v-2a6 6 0 0 1 12 0v2"/><path d="M16 4a4 4 0 0 1 0 8M17 15a6 6 0 0 1 4 6"/></>,
  org: <><rect x="9" y="3" width="6" height="5" rx="1"/><rect x="3" y="16" width="6" height="5" rx="1"/><rect x="15" y="16" width="6" height="5" rx="1"/><path d="M12 8v4M6 16v-4h12v4"/></>,
  plus: <><path d="M12 5v14M5 12h14"/></>,
};

function Icon({ name }: { name: IconName }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      {ICON_PATHS[name]}
    </svg>
  );
}

const NAV: { label: string; links: [string, string, IconName][] }[] = [
  { label: "Overview", links: [["Dashboard", "/dashboard", "overview"], ["Getting Started", "/onboarding", "spark"]] },
  {
    label: "Money",
    links: [
      ["Accounts", "/accounts", "accounts"],
      ["Transfers", "/transfers", "transfer"],
      ["Ledger", "/ledger", "ledger"],
      ["Reconciliation", "/reconciliation", "reconcile"],
      ["Settlements", "/reconciliation/statements", "settlement"],
    ],
  },
  {
    label: "Fraud",
    links: [
      ["Cases", "/fraud-cases", "shield"],
      ["Risk Profiles", "/risk-profiles", "risk"],
      ["ML Monitoring", "/ml", "model"],
    ],
  },
  {
    label: "Payment Rails",
    links: [
      ["Webhooks", "/webhooks", "webhook"],
      ["Certifications", "/certifications", "certificate"],
      ["Production Readiness", "/production-readiness", "launch"],
    ],
  },
  {
    label: "Compliance",
    links: [
      ["Evidence", "/evidence", "evidence"],
      ["Audit Logs", "/audit-logs", "audit"],
    ],
  },
  { label: "Developer", links: [["API Keys", "/developer/api-keys", "key"], ["Monitoring", "/monitoring", "monitor"]] },
  { label: "Organisation", links: [["Tenant Admin", "/admin", "building"], ["Users & Roles", "/users", "users"], ["Org Units", "/org-units", "org"]] },
];

const ENVIRONMENT = (process.env.NEXT_PUBLIC_ENVIRONMENT ?? "sandbox").toLowerCase();

export default function Shell({ children, active }: { children: ReactNode; active: string }) {
  const router = useRouter();
  const [ready, setReady] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [session, setSess] = useState<{ email: string; role: string; tenantId: string } | null>(null);
  const [scopeUnits, setScopeUnits] = useState<OrgUnit[]>([]);
  const activeGroup = NAV.find((group) => group.links.some(([, href]) => href === active))?.label ?? "Overview";
  const [expandedGroup, setExpandedGroup] = useState(activeGroup);

  useEffect(() => {
    if (!getToken()) {
      router.replace("/login");
      return;
    }
    setSess(getSession());
    setReady(true);
    api.myScope().then((s) => setScopeUnits(s.scoped ? s.units : [])).catch(() => {});
  }, [router]);

  if (!ready) return null;

  function logout() {
    setToken(null);
    setSession(null);
    router.replace("/login");
  }

  return (
    <div className="shell">
      <a href="#main" className="skip-link">Skip to content</a>
      <aside className={`sidebar${menuOpen ? " open" : ""}`} aria-label="Main navigation">
        <div className="brand">
          <span className="brand-mark" aria-hidden>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <path d="M6 4h12v16H6z" />
              <path d="M9 8h6M9 12h6M9 16h3" />
              <path d="m14.5 15.5 1.5 1.5 3-3" />
            </svg>
          </span>
          <span className="brand-copy">
            <span className="brand-name">TrustLedger</span>
            <span className="brand-tagline">Financial control plane</span>
          </span>
        </div>

        <div className="sidebar-nav">
        {NAV.map((group) => {
          const groupOpen = expandedGroup === group.label;
          const groupActive = group.label === activeGroup;
          return (
          <div className={`navgroup${groupActive ? " active-group" : ""}`} key={group.label}>
            <button
              type="button"
              className="navlabel"
              aria-expanded={groupOpen}
              onClick={() => setExpandedGroup(groupOpen ? "" : group.label)}
            >
              <span>{group.label}</span><span aria-hidden>{groupOpen ? "−" : "+"}</span>
            </button>
            <nav className={`sidenav${groupOpen ? " open" : ""}`}>
              {group.links.map(([label, href, icon]) => (
                <Link
                  key={href}
                  href={href}
                  className={active === href ? "active" : ""}
                  aria-current={active === href ? "page" : undefined}
                  onClick={() => setMenuOpen(false)}
                >
                  <span className="nav-icon"><Icon name={icon} /></span>
                  <span className="nav-text">{label}</span>
                </Link>
              ))}
            </nav>
          </div>
        )})}
        </div>

        <div className="sidebar-footer">
          <div className="userline">
            <span className="avatar" aria-hidden>{(session?.email?.[0] ?? "?").toUpperCase()}</span>
            <span className="who">
              <span className="email">{session?.email ?? "signed in"}</span>
              <span className="role">{session?.role?.toLowerCase() ?? ""}</span>
            </span>
          </div>
          <button className="ghost" onClick={logout}>Log out</button>
        </div>
      </aside>

      <div className="content">
        <header className="topnav">
          <button className="ghost menu-btn" aria-label="Open navigation" onClick={() => setMenuOpen((v) => !v)}>☰</button>
          <span className="mobile-brand"><span className="brand-mark"><Icon name="ledger" /></span><b>TrustLedger</b></span>
          <span className={`envbadge ${ENVIRONMENT}`}>{ENVIRONMENT}</span>
          <div className="topnav-actions">
            <button className="cmdk-trigger" aria-label="Open command palette" onClick={() => window.dispatchEvent(new Event("trustledger:cmdk"))}>
              <span>Search transfers, cases and evidence</span> <kbd>⌘K</kbd>
            </button>
            {scopeUnits.length > 0 && (
              <span className="tenantchip" title="You see only accounts, transfers, ledger and fraud cases within these organisation units">
                Scope <b>{scopeUnits.map((u) => u.name).join(", ")}</b>
              </span>
            )}
            <Link href="/transfers/new" className="btn primary-action" style={{ textDecoration: "none" }}>
              <Icon name="plus" />
              <span className="action-label">Create transfer</span>
            </Link>
            {session && (
              <span className="topnav-user" title={`${session.email} · ${session.tenantId}`}>
                <span className="avatar" aria-hidden>{session.email[0].toUpperCase()}</span>
                <span><b>{session.email.split("@")[0]}</b><small>{session.role.toLowerCase()}</small></span>
              </span>
            )}
          </div>
        </header>
        <main className="page" id="main">
          {active === "/production-readiness" && <PlatformProductionGate />}
          {children}
        </main>
      </div>
      <nav className="mobile-tabbar" aria-label="Mobile navigation">
        {[["Overview", "/dashboard", "overview"], ["Money", "/transfers", "transfer"], ["Fraud", "/fraud-cases", "shield"]].map(([label, href, icon]) => (
          <Link key={href} href={href} className={active === href || (label === "Money" && activeGroup === "Money") ? "active" : ""}>
            <span className="nav-icon"><Icon name={icon as IconName} /></span><span>{label}</span>
          </Link>
        ))}
        <button type="button" onClick={() => setMenuOpen(true)}><span className="nav-icon">•••</span><span>More</span></button>
      </nav>
      <CommandPalette />
    </div>
  );
}
