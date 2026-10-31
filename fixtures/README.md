# Synthetic reconciliation fixtures

The backend integration suite contains small golden cases for exact matches, missing legs, duplicate statement legs, amount mismatch, direction mismatch, currency mismatch, unknown references, and malformed rows. The expected monetary values are constants in the tests, not values read back from the ledger.

To populate the running local demo with reviewable statements, execute from the project root:

```powershell
node scripts/demo-scenario.mjs
```

This intentionally posts two simulated Alice-to-Bob transfers (INR 500 and INR 250). It defines those amounts before posting, uses returned transaction references only to identify the operations, and constructs a separate expected-event manifest. It writes `clean.csv`, `anomalies.csv`, and `expected-manifest.json` into a new `.runtime/generated-statements/<batch>/` folder.

The script imports both statements through the actual REST API and verifies:

- Clean: four matched customer legs and zero exceptions.
- Anomalies: one amount mismatch, one duplicate leg, one missing leg, and one statement-only entry.
- Internal integrity: zero findings after posting.

The operation requires the default demo accounts/password and sufficient Alice funds. It is not a read-only check; every successful run deliberately creates a new documented synthetic batch. The final application review dataset includes one such run when the script has been executed.

Opening-funding journals are excluded from statement-leg comparison. Reversals inside the selected interval would need their own statement legs. The short recorded batch interval keeps unrelated historical demo activity out of the comparison.

Rows in `statements/schema-example.csv` illustrate the format only. Their references do not automatically identify a transfer in a freshly created database.
