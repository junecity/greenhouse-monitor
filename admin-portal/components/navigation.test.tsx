import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AppShell } from "./app-shell";

vi.mock("next/navigation", () => ({ usePathname: () => "/customers/" }));
vi.mock("./auth-provider", () => ({ useAuth: () => ({ state: "authenticated", signOut: vi.fn() }) }));
vi.mock("@/hooks/use-admin-api", () => ({ useAdminQuery: () => ({ data: { role: "Owner" } }) }));

describe("administrator navigation", () => {
  it("opens and closes the mobile menu and closes it after choosing a page", () => {
    render(<AppShell title="Customers"><p>Customer workspace</p></AppShell>);
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    expect(screen.getByRole("button", { name: "Close navigation", expanded: true })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("link", { name: "Devices" }));
    expect(screen.getByRole("button", { name: "Open navigation" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByRole("link", { name: "Customers" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByText("Tools & diagnostics").closest("details")).not.toHaveAttribute("open");
  });
});
