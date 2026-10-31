import { describe, it, expect, vi, afterEach } from "vitest";
import { api, ApiError, sumDecimal, money } from "./api";
describe("exact balance display totals", () => {
  it("adds decimal strings using integer paise", () => {
    expect(sumDecimal(["0.10", "0.20", "5000.01"])).toBe("5000.31");
  });
  it("preserves values beyond JavaScript integer precision", () => {
    expect(sumDecimal(["9007199254740993.01", "0.99"])).toBe(
      "9007199254740994.00",
    );
  });
  it("handles empty accounts", () => {
    expect(sumDecimal([])).toBe("0.00");
  });
  it("formats large decimal strings without rounding paise", () => {
    expect(money("9007199254740993.01")).toBe("₹9,00,71,99,25,47,40,993.01");
  });
});
describe("request outcomes", () => {
  afterEach(() => vi.unstubAllGlobals());
  it("preserves business error codes", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "INSUFFICIENT_FUNDS",
            message: "Not enough funds",
          }),
          { status: 422 },
        ),
      ),
    );
    await expect(api("/accounts")).rejects.toMatchObject({
      status: 422,
      code: "INSUFFICIENT_FUNDS",
    });
  });
  it("marks network failures as uncertain", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    await expect(api("/accounts")).rejects.toBeInstanceOf(ApiError);
    await expect(api("/accounts")).rejects.toMatchObject({
      status: 0,
      code: "NETWORK_UNCERTAIN",
    });
  });
  it("does not invent a response for a successful logout", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    expect(await api("/auth/logout")).toBeUndefined();
  });
});
