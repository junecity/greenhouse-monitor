import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DashboardPage } from "./pages";
import type { Dashboard } from "@/lib/types";

const response = vi.hoisted(() => ({ billing: undefined as Dashboard["billing"] }));
vi.mock("next/navigation", () => ({ usePathname: () => "/" }));
vi.mock("./auth-provider", () => ({ useAuth: () => ({ state: "authenticated", signOut: vi.fn() }) }));
vi.mock("@/hooks/use-admin-api", () => ({
  useAdminQuery: () => ({ loading: false, error: null, reload: vi.fn(), data: {
    ...response, metrics: { totalCustomers: 15 }, trends: { customerGrowth: [], subscriptions: [], reliability: [] },
    alerts: [], costs: {}, generatedAt: 1,
  } }),
}));

describe("billing overview", () => {
  afterEach(cleanup);
  it("separates customer capacity from Play license-test capacity", () => {
    response.billing = {
      production: { greenhouseAccounts: 2, extraSlots: 5, endingGreenhousePlans: 1, recordingAccounts: 3 },
      licenseTest: { greenhouseAccounts: 1, extraSlots: 2, endingGreenhousePlans: 0, recordingAccounts: 0 },
    };
    render(<DashboardPage />);
    const table = within(screen.getByRole("table", { name: "Verified subscription counts" }));
    expect(table.getByRole("columnheader", { name: "License tests" })).toBeVisible();
    const cells = (name: RegExp) => within(table.getByRole("row", { name })).getAllByRole("cell").map(cell => cell.textContent);
    expect(cells(/Additional greenhouse slots/)).toEqual(["5", "2"]);
    expect(cells(/Cloud recording subscribers/)).toEqual(["3", "0"]);
  });

  it("keeps older dashboard responses usable without inventing a zero count", () => {
    response.billing = undefined;
    render(<DashboardPage />);
    expect(screen.getByText("Subscription counts are currently unavailable.")).toBeVisible();
    expect(screen.getByText("Total customers")).toBeVisible();
    expect(screen.queryByRole("table", { name: "Verified subscription counts" })).not.toBeInTheDocument();
  });
});
