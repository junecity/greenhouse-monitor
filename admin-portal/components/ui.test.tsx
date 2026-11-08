import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EmptyState, MetricCard, StatusBadge, TrendChart } from "./ui";

describe("admin portal visual states", () => {
  it("renders verified metric values without inventing a fallback", () => {
    render(<MetricCard label="Active customers" value={0} hint="Backend records" />);
    expect(screen.getByText("0")).toBeInTheDocument();
    expect(screen.getByText("Backend records")).toBeInTheDocument();
  });

  it("renders a clear empty trend state", () => {
    render(<TrendChart points={[]} label="Customer growth" />);
    expect(screen.getByText("No customer growth trend yet")).toBeInTheDocument();
  });

  it("labels status semantically", () => {
    render(<StatusBadge value="SUSPENDED" />);
    expect(screen.getByText("SUSPENDED")).toHaveClass("danger");
  });

  it("supports explicit empty data states", () => {
    render(<EmptyState title="No devices" detail="Nothing has reported." />);
    expect(screen.getByText("No devices")).toBeInTheDocument();
    expect(screen.getByText("Nothing has reported.")).toBeInTheDocument();
  });
});
