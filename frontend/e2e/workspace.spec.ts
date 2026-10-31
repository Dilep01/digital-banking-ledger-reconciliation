import { test, expect, type Page } from "@playwright/test";
test.describe.configure({ mode: "serial" });
const transferDescription = "Browser review transfer " + Date.now();
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByRole("button", { name: user, exact: true }).click();
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", {
      name:
        "Good to see you," +
        (user === "alice"
          ? " Alice."
          : user === "bob"
            ? " Bob."
            : user === "operator"
              ? " Priya."
              : user === "approver"
                ? " Sam."
                : " Alex."),
    }),
  ).toBeVisible();
}
test("customer dashboard, exact transfer, history and logout", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await login(page, "alice");
  await page.screenshot({
    path: "../docs/screenshots/customer-overview.png",
    fullPage: true,
    animations: "disabled",
  });
  await page
    .getByRole("button", { name: "Transfer money", exact: true })
    .click();
  await page.getByLabel("To demo account reference").fill("DEMO-BOB");
  await page.getByPlaceholder("0.00").fill("12.34");
  await page
    .getByPlaceholder("What is this transfer for?")
    .fill(transferDescription);
  await page
    .getByRole("button", { name: "Review transfer", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page
    .getByRole("button", { name: "Confirm simulated transfer" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Transfer posted" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Activity", exact: true }).click();
  await expect(
    page.getByText(transferDescription, { exact: true }),
  ).toBeVisible();
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: "Export CSV" }).click();
  expect((await download).suggestedFilename()).toBe("statement.csv");
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(
    page.getByRole("heading", { name: "Welcome back" }),
  ).toBeVisible();
  expect(errors).toEqual([]);
});
test("staff can request and independently approve a full reversal", async ({
  page,
}) => {
  await login(page, "operator");
  await page
    .getByRole("button", { name: "Ledger explorer", exact: true })
    .click();
  const row = page
    .getByRole("row")
    .filter({ hasText: transferDescription })
    .first();
  await row.getByRole("button", { name: "Request reversal" }).click();
  await page
    .getByLabel("Reason", { exact: true })
    .fill("Browser validation: " + transferDescription);
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByRole("status")).toContainText("Reversal requested");
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(
    page.getByRole("heading", { name: "Welcome back" }),
  ).toBeVisible();
  await login(page, "approver");
  await page.getByRole("button", { name: "Approvals", exact: true }).click();
  await page
    .getByRole("row")
    .filter({ hasText: transferDescription })
    .getByRole("button", { name: "Review reversal" })
    .click();
  await page
    .getByLabel("Decision reason")
    .fill("Independent browser check against the original entries");
  await page.getByRole("button", { name: "Approve and post reversal" }).click();
  await expect(page.getByRole("status")).toContainText("Full reversal posted");
  await expect(
    page.getByRole("row").filter({ hasText: transferDescription }),
  ).toContainText("APPROVED");
  await page.screenshot({
    path: "../docs/screenshots/approvals.png",
    fullPage: true,
    animations: "disabled",
  });
});
test("statement upload, integrity run and exception evidence", async ({
  page,
}) => {
  await login(page, "operator");
  await page
    .getByRole("button", { name: "Reconciliation", exact: true })
    .click();
  const time = new Date().toISOString();
  await page.getByLabel("Statement CSV").setInputFiles({
    name: "synthetic-browser.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(
      `external_reference,account_ref,booking_time,signed_amount,currency\nEXTERNAL-BROWSER,DEMO-ALICE,${time},-9.00,INR\n`,
    ),
  });
  await page
    .getByRole("button", { name: "Import statement", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Statement imported");
  await page.getByRole("button", { name: "Compare statement" }).click();
  await expect(
    page.getByRole("heading", { name: "Run findings" }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Investigate", exact: true })
    .first()
    .click();
  await expect(page.getByRole("dialog")).toContainText("EXTERNAL-BROWSER");
  await page
    .getByLabel("Investigation note or correction reason")
    .fill("Independent synthetic statement entry; no matching posting");
  await page.getByRole("button", { name: "Save investigation" }).click();
  await expect(page.getByRole("status")).toContainText(
    "Investigation history updated",
  );
  await page.getByRole("button", { name: "Close dialog" }).click();
  await page
    .getByRole("button", { name: "Run full internal integrity check" })
    .click();
  await expect(page.getByRole("status")).toContainText("0 finding(s)");
  await page.screenshot({
    path: "../docs/screenshots/reconciliation.png",
    fullPage: true,
    animations: "disabled",
  });
});
test("mobile navigation and account reference fit the viewport", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, "bob");
  await page.getByRole("button", { name: "Open navigation" }).click();
  await page.getByRole("button", { name: "Accounts", exact: true }).click();
  await expect(page.getByText("DEMO-BOB", { exact: true })).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "../docs/screenshots/mobile-account.png",
    fullPage: true,
    animations: "disabled",
  });
});
test("lost response recovers the same transfer without a second debit", async ({
  page,
}) => {
  await login(page, "alice");
  const before = await (await page.request.get("/api/v1/overview")).json();
  await page
    .getByRole("button", { name: "Transfer money", exact: true })
    .click();
  await page.getByLabel("To demo account reference").fill("DEMO-BOB");
  await page.getByPlaceholder("0.00").fill("0.01");
  await page
    .getByPlaceholder("What is this transfer for?")
    .fill("Lost response recovery test");
  await page
    .getByRole("button", { name: "Review transfer", exact: true })
    .click();
  await page.route(
    "**/api/v1/transfers",
    async (route) => {
      await route.fetch();
      await route.abort("failed");
    },
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Confirm simulated transfer" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Check your saved transfer" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Retry same request" }).click();
  await expect(
    page.getByRole("heading", { name: "Transfer posted" }),
  ).toBeVisible();
  const after = await (await page.request.get("/api/v1/overview")).json();
  expect(after.transfer_count).toBe(before.transfer_count + 1);
});
