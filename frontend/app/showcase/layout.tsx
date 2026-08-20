import type { ReactNode } from "react";

export const metadata = {
  title: "TrustLedger — Executive Showcase",
  description: "A public synthetic replay of test-backed payment-reliability controls",
  robots: {
    index: false,
    follow: false,
  },
};

export default function ShowcaseLayout({ children }: { children: ReactNode }) {
  return children;
}
