"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

type Command = { label: string; hint: string; href: string; keywords?: string };

/** Static navigation + quick actions (design.md §23.1). */
const COMMANDS: Command[] = [
  { label: "Dashboard", hint: "Overview", href: "/dashboard", keywords: "home overview cockpit" },
  { label: "Create transfer", hint: "New money movement", href: "/transfers/new", keywords: "send pay new" },
  { label: "Transfers", hint: "List & detail", href: "/transfers", keywords: "payments money list" },
  { label: "Accounts", hint: "Balances", href: "/accounts", keywords: "balance funds" },
  { label: "Ledger", hint: "Double-entry explorer", href: "/ledger", keywords: "debit credit entries" },
  { label: "Reconciliation", hint: "Issues", href: "/reconciliation", keywords: "mismatch unbalanced issues" },
  { label: "Fraud cases", hint: "Case queue", href: "/fraud-cases", keywords: "fraud review approve reject" },
  { label: "Risk profiles", hint: "Devices / payees / users", href: "/risk-profiles", keywords: "device beneficiary mule trust" },
  { label: "ML monitoring", hint: "Model scores", href: "/ml", keywords: "model shadow score" },
  { label: "Webhooks", hint: "Provider callbacks", href: "/webhooks", keywords: "payment rail provider events" },
  {
    label: "Production readiness",
    hint: "Canaries and circuit breakers",
    href: "/production-readiness",
    keywords: "provider rollout approval canary exposure pause production live payments",
  },
  { label: "Evidence", hint: "Exports", href: "/evidence", keywords: "export pack checksum" },
  { label: "Audit logs", hint: "Activity", href: "/audit-logs", keywords: "audit history actions" },
  { label: "Tenant admin", hint: "Plan, quotas, fraud policy", href: "/admin", keywords: "settings policy plan quota org" },
];

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function CommandPalette() {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen((v) => !v);
      } else if (e.key === "Escape") {
        setOpen(false);
      }
    }
    function onOpen() { setOpen(true); }
    document.addEventListener("keydown", onKey);
    window.addEventListener("trustledger:cmdk", onOpen);
    return () => {
      document.removeEventListener("keydown", onKey);
      window.removeEventListener("trustledger:cmdk", onOpen);
    };
  }, []);

  const q = query.trim().toLowerCase();
  const items = useMemo(() => {
    const matches = COMMANDS.filter(
      (c) => !q || c.label.toLowerCase().includes(q) || (c.keywords ?? "").includes(q),
    );
    if (UUID_RE.test(query.trim())) {
      matches.unshift({ label: `Open transfer ${query.trim().slice(0, 8)}…`, hint: "Jump to transaction", href: `/transfers/${query.trim()}` });
    }
    return matches;
  }, [q, query]);

  useEffect(() => setActive(0), [query, open]);

  if (!open) return null;

  function run(href: string) {
    setOpen(false);
    setQuery("");
    router.push(href);
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="Command palette" onClick={() => setOpen(false)}>
      <div className="cmdk" onClick={(e) => e.stopPropagation()}>
        <input
          autoFocus
          className="cmdk-input"
          placeholder="Search pages, actions, or paste a transaction id…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => Math.min(a + 1, items.length - 1)); }
            else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
            else if (e.key === "Enter" && items[active]) { e.preventDefault(); run(items[active].href); }
          }}
        />
        <ul className="cmdk-list">
          {items.map((c, i) => (
            <li
              key={c.href}
              className={i === active ? "active" : ""}
              onMouseEnter={() => setActive(i)}
              onClick={() => run(c.href)}
            >
              <span>{c.label}</span>
              <span className="muted">{c.hint}</span>
            </li>
          ))}
          {items.length === 0 && <li className="muted" style={{ cursor: "default" }}>No matches</li>}
        </ul>
        <div className="cmdk-foot muted">
          <span><kbd>↑</kbd><kbd>↓</kbd> navigate · <kbd>↵</kbd> open · <kbd>esc</kbd> close</span>
        </div>
      </div>
    </div>
  );
}
