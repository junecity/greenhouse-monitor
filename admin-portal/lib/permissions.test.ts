import { describe, expect, it } from "vitest";
import { can } from "./permissions";

describe("administrator role permissions", () => {
  it("gives the owner all supported controls", () => {
    expect(can("Owner", "premium")).toBe(true);
    expect(can("Owner", "customer")).toBe(true);
    expect(can("Owner", "device")).toBe(true);
  });

  it("limits support to safe troubleshooting and device revocation", () => {
    expect(can("Support", "read")).toBe(true);
    expect(can("Support", "device")).toBe(true);
    expect(can("Support", "premium")).toBe(false);
    expect(can("Support", "customer")).toBe(false);
  });

  it("keeps read-only sessions non-mutating", () => {
    expect(can("ReadOnly", "read")).toBe(true);
    expect(can("ReadOnly", "device")).toBe(false);
    expect(can("ReadOnly", "recovery")).toBe(false);
  });
});
