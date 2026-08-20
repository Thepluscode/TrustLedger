import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const pagePath = fileURLToPath(new URL("../app/showcase/page.tsx", import.meta.url));
const page = readFileSync(pagePath, "utf8");

const forbidden = [
  ["authenticated shell", /components\/Shell/],
  ["API client", /lib\/api/],
  ["direct API path", /["'`]\/api\//],
  ["network request", /\bfetch\s*\(/],
  ["session token helper", /\bgetToken\b|\bgetSession\b/],
  ["browser session storage", /\blocalStorage\b|\bsessionStorage\b/],
];

const required = [
  "Every record below is fictional",
  "NO CUSTOMER DATA · NO MONEY MOVEMENT",
  "NOT YET ESTABLISHED",
  "Not yet customer-proven",
];

function findViolations(source) {
  return forbidden.filter(([, pattern]) => pattern.test(source)).map(([label]) => label);
}

const violations = findViolations(page);
if (violations.length > 0) {
  throw new Error(`Public showcase exposure guard failed: ${violations.join(", ")}`);
}

const missingBoundaries = required.filter((boundary) => !page.includes(boundary));
if (missingBoundaries.length > 0) {
  throw new Error(`Public showcase honesty boundary missing: ${missingBoundaries.join(" | ")}`);
}

// Negative control: prove the guard detects a direct API call rather than only passing clean source.
if (!findViolations(`${page}\nfetch("/api/v1/audit")`).includes("direct API path")) {
  throw new Error("Public showcase exposure guard negative control did not detect an injected API call");
}

console.log("Public showcase exposure guard passed: no auth, session, storage or API dependency; honesty boundary present.");
