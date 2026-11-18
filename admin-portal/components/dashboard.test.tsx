import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DashboardPage, SubscriptionsPage } from "./pages";
import type { Dashboard } from "@/lib/types";

const response = vi.hoisted(() => ({ billing: undefined as Dashboard["billing"] }));
vi.mock("next/navigation", () => ({ usePathname: () => "/" }));
vi.mock("./auth-provider", () => ({ useAuth: () => ({ state: "authenticated", signOut: vi.fn() }) }));
vi.mock("@/hooks/use-admin-api", () => ({
  useAdminMutation: () => ({ run: vi.fn(), pending: false }),
  useAdminQuery: () => ({ loading: false, error: null, reload: vi.fn(), data: {
    ...response, subscriptions: [], playVerificationConfigured: false,
    addOns: [
      { kind: "GREENHOUSE_PLAN", accountId: "a", customerName: "North Farm", state: "Ending", extraSlots: 3, paidThrough: 2000000000, verifiedAt: 1, testPurchase: false },
      { kind: "CAMERA_STORAGE", accountId: "a", customerName: "North Farm", state: "Active", paidThrough: 2000000000, verifiedAt: 1, testPurchase: true },
    ],
    metrics: { totalCustomers: 15 }, trends: { customerGrowth: [], subscriptions: [], reliability: [] },
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
    render(<SubscriptionsPage />);
    expect(screen.getByText("Google Play purchase verification needs setup.")).toBeVisible();
    expect(screen.getByText("3 extra greenhouses", { exact: false })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: /Cloud recording/ }));
    expect(screen.getByText(/Paid through .*License test/)).toBeVisible();
    fireEvent.change(screen.getByPlaceholderText("Find a customer or account ID"), { target: { value: "South" } });
    expect(screen.getByText("No matching customers")).toBeVisible();
    fireEvent.click(screen.getByText("Plan totals"));
    const table = within(screen.getByRole("table", { name: "Verified subscription counts" }));
    expect(table.getByRole("columnheader", { name: "License tests" })).toBeVisible();
    const cells = (name: RegExp) => within(table.getByRole("row", { name })).getAllByRole("cell").map(cell => cell.textContent);
    expect(cells(/Additional greenhouse slots/)).toEqual(["5", "2"]);
    expect(cells(/Cloud recording subscribers/)).toEqual(["3", "0"]);
  });

  it("keeps older dashboard responses usable without inventing a zero count", () => {
    response.billing = undefined;
    render(<DashboardPage />);
    expect(screen.getByText("Counts unavailable")).toBeVisible();
    expect(screen.getByRole("link", { name: /Find an account/ })).toHaveAttribute("href", expect.stringMatching(/^\/customers\/?$/));
    expect(screen.queryByRole("table", { name: "Verified subscription counts" })).not.toBeInTheDocument();
  });
});
