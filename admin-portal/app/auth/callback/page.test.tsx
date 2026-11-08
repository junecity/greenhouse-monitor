import { render, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const completeSignIn = vi.fn(async () => undefined);

vi.mock("@/components/auth-provider", () => ({
  useAuth: () => ({ completeSignIn }),
}));

import CallbackPage from "./page";

describe("administrator sign-in callback", () => {
  beforeEach(() => completeSignIn.mockClear());

  it("consumes the one-time authorization code only once across rerenders", async () => {
    const view = render(<CallbackPage />);
    await waitFor(() => expect(completeSignIn).toHaveBeenCalledTimes(1));

    view.rerender(<CallbackPage />);
    await waitFor(() => expect(completeSignIn).toHaveBeenCalledTimes(1));
  });
});
