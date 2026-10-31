import { mkdir, writeFile } from "node:fs/promises";
const base = "http://127.0.0.1:8080/api/v1";
class Client {
  cookies = new Map();
  token = "";
  header = "X-CSRF-TOKEN";
  async request(path, method = "GET", body, headers = {}) {
    const h = {
      ...headers,
      Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; "),
    };
    if (method !== "GET") h[this.header] = this.token;
    if (body && typeof body !== "string" && !(body instanceof FormData)) {
      body = JSON.stringify(body);
      h["Content-Type"] = "application/json";
    }
    const response = await fetch(base + path, { method, headers: h, body });
    for (const raw of response.headers.getSetCookie()) {
      const pair = raw.split(";")[0],
        index = pair.indexOf("=");
      this.cookies.set(pair.slice(0, index), pair.slice(index + 1));
    }
    const result = await response.json();
    if (!response.ok)
      throw new Error(
        `${response.status}: ${result.message || JSON.stringify(result)}`,
      );
    return result;
  }
  async csrf() {
    const r = await this.request("/auth/csrf");
    this.token = r.token;
    this.header = r.headerName;
  }
  async login(user) {
    await this.csrf();
    await this.request(
      "/auth/login",
      "POST",
      new URLSearchParams({
        email: user + "@ledger.local",
        password: "DemoBank!2026",
      }).toString(),
      { "Content-Type": "application/x-www-form-urlencoded" },
    );
    await this.csrf();
  }
}
// Define expected values before posting; never obtain expected amounts from ledger queries.
const nonce = Date.now().toString();
const manifest = {
  batch: nonce,
  expected: [
    { source: "DEMO-ALICE", destination: "DEMO-BOB", amount: "500.00" },
    { source: "DEMO-ALICE", destination: "DEMO-BOB", amount: "250.00" },
  ],
  anomalyCategories: [
    "AMOUNT_MISMATCH",
    "DUPLICATE_STATEMENT_LEG",
    "MISSING_STATEMENT_LEG",
    "STATEMENT_ONLY",
  ],
};
const customer = new Client();
await customer.login("alice");
const accounts = await customer.request("/accounts");
const source = accounts.find((a) => a.public_ref === "DEMO-ALICE");
const start = new Date().toISOString();
const legs = [];
for (let i = 0; i < manifest.expected.length; i++) {
  const expected = manifest.expected[i];
  const result = await customer.request(
    "/transfers",
    "POST",
    {
      sourceAccountId: source.id,
      destinationAccountRef: expected.destination,
      amount: expected.amount,
      currency: "INR",
      description: `Golden reconciliation batch ${nonce} / ${i + 1}`,
    },
    { "Idempotency-Key": `golden-${nonce}-${i}` },
  );
  const booking = new Date().toISOString();
  expected.reference = result.reference;
  legs.push(
    `${result.reference},${expected.source},${booking},-${expected.amount},INR`,
    `${result.reference},${expected.destination},${booking},${expected.amount},INR`,
  );
}
const end = new Date(Date.now() + 1).toISOString();
const header =
  "external_reference,account_ref,booking_time,signed_amount,currency\n";
const clean = header + legs.join("\n") + "\n";
const anomalies =
  header +
  [
    legs[0].replace("-500.00", "-499.00"),
    legs[1],
    legs[1],
    legs[2],
    `EXTERNAL-ONLY-${nonce},DEMO-ALICE,${new Date().toISOString()},-10.00,INR`,
  ].join("\n") +
  "\n";
// Keep every statement booking timestamp inside the recorded comparison interval.
const intervalEnd = new Date(Date.now() + 10).toISOString();
const directory = new URL(
  `../.runtime/generated-statements/${nonce}/`,
  import.meta.url,
);
await mkdir(directory, { recursive: true });
await writeFile(
  new URL("expected-manifest.json", directory),
  JSON.stringify({ ...manifest, start, end: intervalEnd }, null, 2),
);
await writeFile(new URL("clean.csv", directory), clean);
await writeFile(new URL("anomalies.csv", directory), anomalies);
const operations = new Client();
await operations.login("operator");
const runs = [];
for (const [name, csv] of [
  ["clean", clean],
  ["anomalies", anomalies],
]) {
  const form = new FormData();
  form.append("source", "GOLDEN-" + nonce);
  form.append(
    "file",
    new Blob([csv], { type: "text/csv" }),
    name + "-" + nonce + ".csv",
  );
  const imported = await operations.request(
    "/ops/statement-imports",
    "POST",
    form,
  );
  const run = await operations.request("/ops/reconciliation-runs", "POST", {
    importId: imported.id,
    start,
    end: intervalEnd,
  });
  if (
    name === "clean" &&
    (run.exception_count !== 0 || run.matched_count !== 4)
  )
    throw new Error(
      "Golden clean statement did not match all four expected legs",
    );
  if (name === "anomalies") {
    const detail = await operations.request(
      "/ops/reconciliation-runs/" + run.id,
    );
    const actual = detail.exceptions.map((e) => e.category).sort();
    if (
      JSON.stringify(actual) !==
      JSON.stringify([...manifest.anomalyCategories].sort())
    )
      throw new Error(
        "Anomaly result differs from independent manifest: " +
          JSON.stringify(actual),
      );
  }
  runs.push({
    name,
    id: run.id,
    matched: run.matched_count,
    exceptions: run.exception_count,
  });
}
const internal = await operations.request("/ops/reconciliation-runs", "POST", {
  start,
  end: intervalEnd,
});
if (internal.exception_count !== 0)
  throw new Error("Internal integrity check found a discrepancy");
const report = {
  passed: true,
  batch: nonce,
  directory: directory.pathname,
  runs,
  integrityFindings: internal.exception_count,
};
await writeFile(
  new URL("../.runtime/demo-scenario-result.json", import.meta.url),
  JSON.stringify(report, null, 2),
);
console.log(JSON.stringify(report, null, 2));
