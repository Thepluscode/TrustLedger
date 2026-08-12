import "./globals.css";
import "./aesthetic.css";
import type { ReactNode } from "react";

export const metadata = {
  title: "TrustLedger — Financial Control Plane",
  description: "Ledger-first payment operations, fraud controls, reconciliation and production governance",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
