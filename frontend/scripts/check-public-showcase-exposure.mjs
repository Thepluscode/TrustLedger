import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const pagePath = fileURLToPath(new URL("../app/showcase/page.tsx", import.meta.url));
const page = readFileSync(pagePath, "utf8");

// Everything the page imports from app/ is part of the public surface too: a component that fetched
// would be as exposed as the page. One level is scanned; the showcase imports only leaf modules.
const imported = [...page.matchAll(/from\s+"(\.{1,2}\/[^"]+)"/g)].map((m) => m[1]);
const importedSources = imported.map((rel) => {
  const base = fileURLToPath(new URL(`../app/showcase/${rel}`, import.meta.url));
  for (const candidate of [base, `${base}.ts`, `${base}.tsx`]) {
    try { return [rel, readFileSync(candidate, "utf8")]; } catch { /* try the next extension */ }
  }
  throw new Error(`Public showcase imports ${rel}, which could not be read`);
});

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
if (importedSources.length === 0) {
  throw new Error("Public showcase exposure guard found no imports to scan; the page imports at least the timeline component");
}
for (const [rel, source] of importedSources) {
  const found = findViolations(source);
  if (found.length > 0) throw new Error(`Public showcase exposure guard failed in imported ${rel}: ${found.join(", ")}`);
}

const missingBoundaries = required.filter((boundary) => !page.includes(boundary));
if (missingBoundaries.length > 0) {
  throw new Error(`Public showcase honesty boundary missing: ${missingBoundaries.join(" | ")}`);
}

// Negative control: prove the guard detects a direct API call rather than only passing clean source.
if (!findViolations(`${page}\nfetch("/api/v1/audit")`).includes("direct API path")) {
  throw new Error("Public showcase exposure guard negative control did not detect an injected API call");
}

// Negative control for the import scan: an imported module that fetched must be caught the same way.
if (!findViolations(`${importedSources[0][1]}\nconst r = await fetch(url)`).includes("network request")) {
  throw new Error("Public showcase exposure guard negative control did not detect an injected fetch in an imported module");
}

console.log(`Public showcase exposure guard passed: no auth, session, storage or API dependency in the page or its ${importedSources.length} imported module(s); honesty boundary present.`);
