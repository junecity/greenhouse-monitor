import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { CustomersPage } from "./pages";
import { readFileSync, writeFileSync } from "node:fs";

const run = vi.fn().mockResolvedValue({});
const customer = { accountId: "fixture-customer", email: "owner@example.test", state: "ACTIVE", plan: "FREE", greenhouseCount: 1, serverCount: 1, createdAt: 1, setupComplete: true };
vi.mock("next/navigation", () => ({ usePathname: () => "/customers/" }));
vi.mock("./auth-provider", () => ({ useAuth: () => ({ state: "authenticated", signOut: vi.fn() }) }));
vi.mock("@/hooks/use-admin-api", () => ({
  useAdminMutation: () => ({ run, pending: false }),
  useAdminQuery: (path: string) => ({ loading: false, error: null, reload: vi.fn(), data:
    path.includes("/session") ? { role: "Owner" } : path.includes("?query=") ? { customers: [customer] } : {
      ...customer, devices: [], alerts: [], entitlement: { plan: "FREE", source: "FREE", videoAccessMode: "DISABLED" },
      greenhouses: [{ greenhouseId: "fixture-greenhouse", name: "North Greenhouse", serverDeviceId: "fixture-server", enabled: true, online: true, sensorCount: 3 }]
    }
  })
}));

describe("customer workspace", () => {
  it("shows a customer with its server and hides technical account details", () => {
    render(<CustomersPage />);
    fireEvent.click(screen.getByRole("button", { name: /owner@example.test/ }));
    expect(screen.getByText("North Greenhouse")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /owner@example.test/ })).toBeInTheDocument();
    expect(screen.getByText("Account details").closest("details")).not.toHaveAttribute("open");
    expect(screen.getByText("fixture-server").closest("details")).not.toHaveAttribute("open");
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    if (process.env.ADMIN_VISUAL_FIXTURE === "1") {
      writeFileSync("/tmp/greenhouse-admin-preview.html", `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>${readFileSync("app/globals.css", "utf8")}</style></head><body>${document.body.innerHTML}</body></html>`);
    }
    fireEvent.click(screen.getByRole("button", { name: "Change access" }));
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "COMPLIMENTARY" } });
    vi.spyOn(window, "confirm").mockReturnValueOnce(true);
    fireEvent.click(screen.getByRole("button", { name: "Save access" }));
    expect(run).toHaveBeenCalledWith("/admin/v1/customers/fixture-customer/video-access", {
      method: "POST", body: JSON.stringify({ mode: "COMPLIMENTARY", reason: "", expiresAt: null })
    });
    fireEvent.click(screen.getByText("Account details"));
    expect(screen.getByText("Account details").closest("details")).toHaveAttribute("open");
  });
});
