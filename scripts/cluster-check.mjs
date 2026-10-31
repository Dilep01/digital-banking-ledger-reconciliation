import { writeFile } from "node:fs/promises";
import os from "node:os";
import { performance } from "node:perf_hooks";
const first = "http://127.0.0.1:8081",
  second = "http://127.0.0.1:8082";
const killPid = Number(
  process.argv.find((arg) => arg.startsWith("--kill-first="))?.split("=")[1],
);
function assert(condition, message) {
  if (!condition) throw new Error(message);
}
class Session {
  cookies = new Map();
  token = "";
  header = "X-CSRF-TOKEN";
  async request(base, path, method = "GET", body, headers = {}) {
    const h = {
      ...headers,
      Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; "),
    };
    if (method !== "GET") h[this.header] = this.token;
    if (body && typeof body !== "string") {
      h["Content-Type"] = "application/json";
      body = JSON.stringify(body);
    }
    const start = performance.now();
    const response = await fetch(base + "/api/v1" + path, {
      method,
      headers: h,
      body,
    });
    for (const raw of response.headers.getSetCookie()) {
      const pair = raw.split(";")[0];
      const split = pair.indexOf("=");
      this.cookies.set(pair.slice(0, split), pair.slice(split + 1));
    }
    const text = await response.text();
    return {
      status: response.status,
      body: text ? JSON.parse(text) : null,
      ms: performance.now() - start,
    };
  }
  async csrf(base) {
    const result = await this.request(base, "/auth/csrf");
    assert(result.status === 200, "CSRF request failed");
    this.token = result.body.token;
    this.header = result.body.headerName;
  }
  async login(base, user) {
    await this.csrf(base);
    const result = await this.request(
      base,
      "/auth/login",
      "POST",
      new URLSearchParams({
        email: user + "@ledger.local",
        password: "DemoBank!2026",
      }).toString(),
      { "Content-Type": "application/x-www-form-urlencoded" },
    );
    assert(result.status === 200, "Login failed");
    await this.csrf(base);
  }
  async post(base, payload, key) {
    for (let retry = 0; retry < 5; retry++) {
      const r = await this.request(base, "/transfers", "POST", payload, {
        "Idempotency-Key": key,
      });
      if (r.status !== 503) return r;
      await new Promise((resolve) => setTimeout(resolve, 100 + retry * 100));
    }
    throw new Error("Retry limit exceeded");
  }
}
const report = {
  environment: {
    platform: os.platform(),
    cpu: os.cpus()[0]?.model,
    logicalCPUs: os.cpus().length,
    memoryGB: Math.round(os.totalmem() / 1024 ** 3),
    node: process.version,
  },
  checks: [],
  measuredAt: new Date().toISOString(),
};
const alice = new Session();
await alice.login(first, "alice");
const shared = await alice.request(second, "/auth/me");
assert(
  shared.status === 200 && shared.body.email === "alice@ledger.local",
  "Session did not survive a change of application instance",
);
report.checks.push("Database-backed session works across two JVMs");
const accounts = await alice.request(first, "/accounts");
const source = accounts.body.find((a) => a.public_ref === "DEMO-ALICE");
const payload = {
  sourceAccountId: source.id,
  destinationAccountRef: "DEMO-BOB",
  amount: "1.00",
  currency: "INR",
  description: "Two-instance duplicate request test",
};
const duplicateStart = performance.now();
const duplicates = await Promise.all(
  Array.from({ length: 50 }, (_, i) =>
    alice.post(i % 2 ? first : second, payload, "cluster-identical"),
  ),
);
assert(
  duplicates.every((r) => r.status === 201),
  "Concurrent replay did not return success",
);
assert(
  new Set(duplicates.map((r) => r.body.id)).size === 1,
  "Duplicate money movement detected",
);
report.checks.push(
  "50 simultaneous same-key requests across two JVMs produce one transfer",
);
const sorted = duplicates.map((r) => r.ms).sort((a, b) => a - b);
report.duplicateBurst = {
  requests: 50,
  wallMs: Math.round(performance.now() - duplicateStart),
  p50Ms: Math.round(sorted[24]),
  p95Ms: Math.round(sorted[47]),
};
const setup = await alice.post(
  first,
  { ...payload, amount: "3999.00", description: "Set up bounded funds test" },
  "cluster-setup",
);
assert(setup.status === 201, "Setup transfer failed");
const spending = await Promise.all(
  Array.from({ length: 20 }, (_, i) =>
    alice.post(
      i % 2 ? first : second,
      { ...payload, amount: "100.00", description: "Concurrent spending " + i },
      "cluster-spend-" + i,
    ),
  ),
);
assert(
  spending.filter((r) => r.status === 201).length === 10 &&
    spending.filter((r) => r.status === 422).length === 10,
  "Overspending protection failed",
);
const ending = await alice.request(
  second,
  "/accounts/" + source.id + "/balance",
);
assert(ending.body.balance === "0.00", "Source balance is not zero");
report.checks.push(
  "20 competing debits from INR 1,000: ten post, ten reject, balance zero",
);
const bob = new Session();
await bob.login(second, "bob");
const bobAccounts = await bob.request(second, "/accounts");
const bobSource = bobAccounts.body.find((a) => a.public_ref === "DEMO-BOB");
const schedule = await bob.request(
  first,
  "/scheduled-transfers",
  "POST",
  {
    sourceAccountId: bobSource.id,
    destinationAccountRef: "DEMO-ALICE",
    amount: "5.00",
    description: "Worker restart recovery",
    dueAt: new Date(Date.now() + 8000).toISOString(),
  },
  { "Idempotency-Key": "cluster-schedule" },
);
assert(schedule.status === 201, "Schedule creation failed");
if (killPid) {
  process.kill(killPid);
  report.checks.push("First JVM stopped after committing the scheduled job");
}
let completed = false;
for (let i = 0; i < 40; i++) {
  const rows = await bob.request(second, "/scheduled-transfers");
  if (rows.body.find((r) => r.id === schedule.body.id)?.state === "COMPLETED") {
    completed = true;
    break;
  }
  await new Promise((resolve) => setTimeout(resolve, 500));
}
assert(
  completed,
  "Surviving worker did not complete durable scheduled transfer",
);
const recovered = await alice.request(
  second,
  "/accounts/" + source.id + "/balance",
);
assert(
  recovered.body.balance === "5.00",
  "Scheduled transfer produced the wrong balance",
);
report.checks.push(
  "Surviving JVM completes the persisted schedule once after the first JVM stops",
);
await alice.csrf(second);
const loggedOut = await alice.request(second, "/auth/logout", "POST");
assert(loggedOut.status === 204, "Logout failed");
assert(
  (await alice.request(second, "/auth/me")).status === 401,
  "Logged-out session was still accepted",
);
report.checks.push("Shared session is invalid after logout");
report.passed = true;
await writeFile(
  new URL("../.runtime/cluster-result.json", import.meta.url),
  JSON.stringify(report, null, 2),
);
console.log(JSON.stringify(report, null, 2));
