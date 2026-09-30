import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EmptyState, SeverityPill } from "./ui";

// First test in the console. It exists so CI can prove the runner executes, not to cover ui.tsx.
describe("shared ui primitives", () => {
  it("renders the severity as a word, not as colour alone", () => {
    render(<SeverityPill value="CRITICAL" />);
    expect(screen.getByText("CRITICAL")).toBeInTheDocument();
  });

  it("an empty state always carries a hint, never a bare 'no data'", () => {
    render(<EmptyState title="No exceptions" hint="Nothing disagrees in this period." />);
    expect(screen.getByText("No exceptions")).toBeInTheDocument();
    expect(screen.getByText("Nothing disagrees in this period.")).toBeInTheDocument();
  });
});
