export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
let csrf: { token: string; headerName: string } | null = null;
export async function refreshCsrf() {
  const res = await fetch("/api/v1/auth/csrf", { credentials: "same-origin" });
  if (!res.ok) throw new Error("Cannot reach the banking service.");
  csrf = await res.json();
}
export async function api<T = unknown>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const method = options.method || "GET";
  const headers = new Headers(options.headers);
  if (method !== "GET") {
    if (!csrf) await refreshCsrf();
    headers.set(csrf!.headerName, csrf!.token);
    if (typeof options.body === "string" && !headers.has("Content-Type"))
      headers.set("Content-Type", "application/json");
  }
  let res: Response;
  try {
    res = await fetch("/api/v1" + path, {
      ...options,
      headers,
      credentials: "same-origin",
    });
  } catch {
    throw new ApiError(
      0,
      "NETWORK_UNCERTAIN",
      "Connection lost. A transfer may have completed. Retry the same request to check its result.",
    );
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(
      res.status,
      body.code || "REQUEST_FAILED",
      body.message || "The request could not be completed.",
    );
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}
export function money(value: string | number) {
  const text = String(value);
  const negative = text.startsWith("-");
  const [whole, fraction = ""] = text.replace(/^-/, "").split(".");
  return `${negative ? "-" : ""}₹${new Intl.NumberFormat("en-IN").format(BigInt(whole))}.${fraction.padEnd(2, "0")}`;
}
export function date(value: unknown) {
  return new Date(String(value)).toLocaleString("en-IN", {
    dateStyle: "medium",
    timeStyle: "short",
  });
}
export function sumDecimal(values: string[]): string {
  let paise = 0n;
  for (const value of values) {
    const [whole, fraction = ""] = value.split(".");
    paise += BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
  }
  return `${paise / 100n}.${(paise % 100n).toString().padStart(2, "0")}`;
}
