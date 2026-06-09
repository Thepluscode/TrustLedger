import './globals.css';
import type { ReactNode } from 'react';

export const metadata = {
  title: 'TrustLedger',
  description: 'Ledger-first fraud monitoring and transaction operations platform'
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
