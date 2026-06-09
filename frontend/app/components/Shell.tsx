"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { getToken, setToken } from "../lib/api";

const LINKS: [string, string][] = [
  ["Dashboard", "/dashboard"],
  ["Accounts", "/accounts"],
  ["Transfers", "/transfers"],
  ["Fraud Cases", "/fraud-cases"],
  ["Evidence", "/evidence"],
  ["Admin", "/admin"],
];

export default function Shell({ children, active }: { children: ReactNode; active: string }) {
  const router = useRouter();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (!getToken()) router.replace("/login");
    else setReady(true);
  }, [router]);

  if (!ready) return null;

  return (
    <main className="shell">
      <aside className="sidebar">
        <div className="brand">TrustLedger</div>
        <nav>
          {LINKS.map(([label, href]) => (
            <Link key={href} href={href} className={active === href ? "active" : ""}>
              {label}
            </Link>
          ))}
          <a
            style={{ cursor: "pointer" }}
            onClick={() => {
              setToken(null);
              router.replace("/login");
            }}
          >
            Log out
          </a>
        </nav>
      </aside>
      <section className="content">{children}</section>
    </main>
  );
}
