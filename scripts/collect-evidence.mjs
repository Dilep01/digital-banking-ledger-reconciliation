import { readFile, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
const root = new URL("../", import.meta.url);
const read = async (name) => readFile(new URL(name, root), "utf8");
const backend = [];
for (const name of ["BankingIntegrationTest", "MoneyTest"]) {
  const text = await read(
    `backend/target/surefire-reports/dev.ledger.${name}.txt`,
  );
  const match = text.match(
    /Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/,
  );
  if (!match) throw new Error("Missing test summary: " + name);
  const suite = {
    name,
    tests: Number(match[1]),
    failures: Number(match[2]),
    errors: Number(match[3]),
    skipped: Number(match[4]),
  };
  if (suite.failures || suite.errors || suite.skipped)
    throw new Error("Backend suite is not fully passing");
  backend.push(suite);
}
const build = (await read(".runtime/final-build.log")).replace(
  /\u001b\[[0-9;]*m/g,
  "",
);
const unit = build.match(/Tests\s+(\d+)\s+passed/),
  contract = build.match(/Validated OpenAPI ([\d.]+): (\d+) paths/);
if (!unit || !contract)
  throw new Error("Missing frontend or contract validation result");
const browser = JSON.parse(await read("frontend/test-results/results.json"));
const cases = [];
function walk(suite) {
  for (const spec of suite.specs || [])
    for (const test of spec.tests || [])
      cases.push({
        title: spec.title,
        status: test.results.at(-1)?.status,
        durationMs: test.results.at(-1)?.duration,
        project: test.projectName,
      });
  for (const child of suite.suites || []) walk(child);
}
browser.suites.forEach(walk);
if (cases.length !== 5 || cases.some((test) => test.status !== "passed"))
  throw new Error("Packaged browser suite is not fully passing");
if (!browser.config.metadata?.baseURL)
  throw new Error("Browser report is missing its target URL metadata");
const report = {
  collectedAt: new Date().toISOString(),
  backend: {
    passed: backend.reduce((sum, suite) => sum + suite.tests, 0),
    suites: backend,
  },
  frontend: { passed: Number(unit[1]) },
  apiContract: {
    version: contract[1],
    paths: Number(contract[2]),
    validated: true,
  },
  packagedBrowser: {
    baseURL: browser.config.metadata.baseURL,
    passed: cases.length,
    tests: cases,
  },
};
await mkdir(new URL("docs/evidence/", root), { recursive: true });
await writeFile(
  new URL("docs/evidence/test-summary.json", root),
  JSON.stringify(report, null, 2) + "\n",
);
for (const [source, destination] of [
  ["cluster-result.json", "cluster-result.json"],
  ["restore-result.json", "restore-result.json"],
  ["demo-scenario-result.json", "demo-scenario-result.json"],
]) {
  const data = JSON.parse(await read(".runtime/" + source));
  if (!data.passed) throw new Error("Verification failed: " + source);
  if (data.dump) data.dump = ".runtime/backups/" + path.basename(data.dump);
  if (data.directory)
    data.directory = ".runtime/generated-statements/" + data.batch + "/";
  await writeFile(
    new URL("docs/evidence/" + destination, root),
    JSON.stringify(data, null, 2) + "\n",
  );
}
console.log(JSON.stringify(report, null, 2));
