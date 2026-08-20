import "./globals.css";
import "./aesthetic.css";
import type { ReactNode } from "react";

export const metadata = {
  title: "TrustLedger — Payment Reliability Operations",
  description: "Cross-provider reconciliation, exception management and operational evidence",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
