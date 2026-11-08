import { describe, expect, it } from "vitest";
import { formatMoney, formatNumber, humanDuration } from "./format";

describe("report formatting", () => {
  it("distinguishes unavailable costs from zero billed cost", () => {
    expect(formatMoney(null)).toBe("Unavailable");
    expect(formatMoney(0)).toBe("$0.00");
  });

  it("formats actual counts and duration", () => {
    expect(formatNumber(1200)).toMatch(/1,200/);
    expect(humanDuration(3720)).toBe("1h 2m");
  });
});
