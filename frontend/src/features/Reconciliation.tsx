import { useState } from "react";
import type { FormEvent } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, FileCheck2, ShieldCheck, Upload, X } from "lucide-react";
import { api, date } from "../api";
import type { Actor, Run, ReconException, Import, Tell } from "../shared/types";
import { useData, short, message, initialInterval } from "../shared/data";
import {
  Status,
  ErrorBox,
  Loading,
  Table,
  Modal,
  Evidence,
} from "../shared/ui";

export function Reconciliation({ actor, tell }: { actor: Actor; tell: Tell }) {
  const imports = useData<Import[]>("/ops/statement-imports");
  const runs = useData<Run[]>("/ops/reconciliation-runs");
  const client = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [source, setSource] = useState("SYNTHETIC");
  const [importId, setImportId] = useState("");
  const [interval, setInterval] = useState(initialInterval);
  const [busy, setBusy] = useState(false);
  const [selectedRun, setSelectedRun] = useState("");
  const [exception, setException] = useState("");
  const [uploadEvidence, setUploadEvidence] = useState<unknown>(null);
  const detail = useQuery<Run>({
    queryKey: ["/ops/reconciliation-runs/" + selectedRun],
    queryFn: () => api<Run>("/ops/reconciliation-runs/" + selectedRun),
    enabled: !!selectedRun,
  });
  async function upload(e: FormEvent) {
    e.preventDefault();
    if (!file) return;
    setBusy(true);
    try {
      const body = new FormData();
      body.append("file", file);
      body.append("source", source);
      const r = await api<{ id: string; duplicate: boolean }>(
        "/ops/statement-imports",
        { method: "POST", body },
      );
      setImportId(r.id);
      tell(
        r.duplicate
          ? "This exact statement was already imported. Existing evidence selected."
          : "Statement imported. Row validation is available below.",
      );
      await client.invalidateQueries();
    } catch (ex) {
      tell(message(ex), "error");
    } finally {
      setBusy(false);
    }
  }
  async function run(internal: boolean) {
    setBusy(true);
    try {
      const r = await api<Run>("/ops/reconciliation-runs", {
        method: "POST",
        body: JSON.stringify({
          importId: internal ? null : importId,
          start: new Date(interval.start).toISOString(),
          end: new Date(interval.end).toISOString(),
        }),
      });
      setSelectedRun(r.id);
      tell(`Reconciliation saved: ${r.exception_count} finding(s).`);
      await client.invalidateQueries();
    } catch (ex) {
      tell(message(ex), "error");
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <div className="recon-grid">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <span className="step-label">01 / STATEMENT SOURCE</span>
              <h2>Import a synthetic statement</h2>
            </div>
            <Upload size={21} />
          </div>
          <form onSubmit={upload}>
            <label>
              Source label
              <input
                required
                maxLength={60}
                value={source}
                onChange={(e) => setSource(e.target.value)}
              />
            </label>
            <label className="upload-box">
              <Upload size={27} />
              <strong>{file ? file.name : "Choose a CSV statement"}</strong>
              <span>UTF-8 CSV · maximum 1 MB / 2,000 rows</span>
              <input
                type="file"
                accept=".csv,text/csv"
                required
                aria-label="Statement CSV"
                onChange={(e) => setFile(e.target.files?.[0] || null)}
              />
            </label>
            <p className="form-note">
              Schema: reference, account, booking time, signed amount, currency.
              Original evidence and invalid rows are preserved.
            </p>
            <button
              className="button full"
              disabled={busy || !file || actor.role !== "OPERATOR"}
            >
              <Upload size={16} />
              Import statement
            </button>
          </form>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <span className="step-label">02 / COMPARE RECORDS</span>
              <h2>Run reconciliation</h2>
            </div>
            <FileCheck2 size={22} />
          </div>
          <div className="form-body">
            <label>
              Imported statement
              <select
                value={importId}
                onChange={(e) => setImportId(e.target.value)}
              >
                <option value="">Select a statement</option>
                {imports.data?.map((i) => (
                  <option key={i.id} value={i.id}>
                    {i.original_name} · {i.row_count} rows
                  </option>
                ))}
              </select>
            </label>
            <div className="form-pair">
              <label>
                From (local)
                <input
                  type="datetime-local"
                  required
                  value={interval.start}
                  onChange={(e) => {
                    if (e.target.value)
                      setInterval({ ...interval, start: e.target.value });
                  }}
                />
              </label>
              <label>
                Until (exclusive)
                <input
                  type="datetime-local"
                  required
                  value={interval.end}
                  onChange={(e) => {
                    if (e.target.value)
                      setInterval({ ...interval, end: e.target.value });
                  }}
                />
              </label>
            </div>
            <p className="form-note">
              Statement checks cover internal transfer and reversal legs in this
              interval. Use a finalized synthetic batch for a reproducible
              comparison.
            </p>
            <button
              className="button primary full"
              disabled={busy || !importId || actor.role !== "OPERATOR"}
              onClick={() => run(false)}
            >
              <FileCheck2 size={16} />
              {busy ? "Processing…" : "Compare statement"}
            </button>
            <button
              className="text-button full"
              disabled={busy || actor.role !== "OPERATOR"}
              onClick={() => run(true)}
            >
              <ShieldCheck size={15} />
              Run full internal integrity check
            </button>
          </div>
        </section>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <h2>Imported evidence</h2>
          <span className="muted">Identical files are deduplicated</span>
        </div>
        {imports.isPending ? (
          <Loading />
        ) : imports.error ? (
          <ErrorBox error={imports.error} />
        ) : (
          <Table
            rows={imports.data}
            columns={[
              {
                label: "File",
                cell: (r) => (
                  <button
                    className="text-button compact"
                    onClick={() =>
                      api("/ops/statement-imports/" + r.id)
                        .then(setUploadEvidence)
                        .catch((ex) => tell(message(ex), "error"))
                    }
                  >
                    {r.original_name}
                  </button>
                ),
              },
              { label: "Source", cell: (r) => r.source },
              { label: "Rows", cell: (r) => r.row_count },
              { label: "Invalid rows", cell: (r) => r.invalid_rows },
              { label: "Imported", cell: (r) => date(r.created_at) },
            ]}
          />
        )}
      </section>
      <section className="panel">
        <div className="panel-heading">
          <h2>Reconciliation runs</h2>
          <span className="muted">Each run preserves its own evidence</span>
        </div>
        {runs.isPending ? (
          <Loading />
        ) : runs.error ? (
          <ErrorBox error={runs.error} />
        ) : (
          <Table
            rows={runs.data}
            columns={[
              {
                label: "Run",
                cell: (r) => (
                  <button
                    className="text-button compact mono"
                    onClick={() => setSelectedRun(r.id)}
                  >
                    {short(r.id)}
                  </button>
                ),
              },
              { label: "Scope", cell: (r) => r.kind },
              { label: "Matched", cell: (r) => r.matched_count },
              {
                label: "Findings",
                cell: (r) => (
                  <span
                    className={
                      r.exception_count ? "warning-text" : "positive-text"
                    }
                  >
                    {r.exception_count}
                  </span>
                ),
              },
              { label: "Created", cell: (r) => date(r.created_at) },
            ]}
            empty="Import a statement or run the first integrity check."
          />
        )}
      </section>
      {selectedRun && (
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Run findings</h2>
              <p>
                {detail.data
                  ? `${detail.data.kind} · snapshot ${date(detail.data.snapshot_at)}`
                  : "Loading evidence"}
              </p>
            </div>
            <button
              className="icon-button"
              aria-label="Close findings"
              onClick={() => setSelectedRun("")}
            >
              <X size={18} />
            </button>
          </div>
          {detail.isPending ? (
            <Loading />
          ) : detail.error ? (
            <ErrorBox error={detail.error} />
          ) : (
            <Table
              rows={detail.data.exceptions || []}
              columns={[
                {
                  label: "Finding",
                  cell: (r) => (
                    <div className="cell-main">
                      <strong>{r.category.replaceAll("_", " ")}</strong>
                      <span className="mono">{short(r.reference)}</span>
                    </div>
                  ),
                },
                { label: "Evidence", cell: (r) => r.detail },
                { label: "Status", cell: (r) => <Status value={r.state} /> },
                {
                  label: "Review",
                  cell: (r) => (
                    <button
                      className="text-button compact"
                      onClick={() => setException(r.id)}
                    >
                      Investigate
                      <ArrowRight size={13} />
                    </button>
                  ),
                },
              ]}
              empty="No discrepancies found in this run."
            />
          )}
        </section>
      )}
      {exception && (
        <ExceptionReview
          id={exception}
          actor={actor}
          tell={tell}
          onClose={() => setException("")}
        />
      )}
      {!!uploadEvidence && (
        <Modal
          title="Imported statement evidence"
          wide
          onClose={() => setUploadEvidence(null)}
        >
          <Evidence data={uploadEvidence} />
        </Modal>
      )}
    </>
  );
}

export function ExceptionReview({
  id,
  actor,
  tell,
  onClose,
}: {
  id: string;
  actor: Actor;
  tell: Tell;
  onClose: () => void;
}) {
  type Detail = ReconException & {
    internal_leg?: { journal_id: string };
    events: unknown[];
    statement_row?: unknown;
  };
  const query = useData<Detail>("/ops/reconciliation-exceptions/" + id);
  const client = useQueryClient();
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  async function action(state: string) {
    setBusy(true);
    try {
      await api("/ops/reconciliation-exceptions/" + id + "/events", {
        method: "POST",
        body: JSON.stringify({ state, note }),
      });
      tell("Investigation history updated.");
      setNote("");
      await client.invalidateQueries();
    } catch (ex) {
      tell(message(ex), "error");
    } finally {
      setBusy(false);
    }
  }
  async function correction() {
    if (!query.data?.internal_leg) return;
    setBusy(true);
    try {
      await api("/ops/correction-requests", {
        method: "POST",
        body: JSON.stringify({
          journalId: query.data.internal_leg.journal_id,
          exceptionId: id,
          reason: note,
        }),
      });
      tell("Full reversal requested. A different staff member must approve.");
      await client.invalidateQueries();
    } catch (ex) {
      tell(message(ex), "error");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal title="Investigate exception" wide onClose={onClose}>
      {query.isPending ? (
        <Loading />
      ) : query.error ? (
        <ErrorBox error={query.error} />
      ) : (
        <>
          <div className="exception-summary">
            <h3>{query.data.category.replaceAll("_", " ")}</h3>
            <Status value={query.data.state} />
            <p>{query.data.detail}</p>
          </div>
          <Evidence
            data={{
              reference: query.data.reference,
              internal_leg: query.data.internal_leg,
              statement_row: query.data.statement_row,
              history: query.data.events,
            }}
          />
          {actor.role === "OPERATOR" &&
            !["RESOLVED", "DISMISSED", "AWAITING_APPROVAL"].includes(
              query.data.state,
            ) && (
              <>
                <label>
                  Investigation note or correction reason
                  <textarea
                    required
                    maxLength={300}
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                  />
                </label>
                <div className="button-row">
                  <button
                    className="button"
                    disabled={busy || !note.trim()}
                    onClick={() => action("INVESTIGATING")}
                  >
                    Save investigation
                  </button>
                  <button
                    className="button"
                    disabled={busy || !note.trim()}
                    onClick={() => action("DISMISSED")}
                  >
                    Dismiss with reason
                  </button>
                  {query.data.internal_leg && (
                    <button
                      className="button primary"
                      disabled={busy || !note.trim()}
                      onClick={correction}
                    >
                      Request full reversal
                    </button>
                  )}
                </div>
              </>
            )}
        </>
      )}
    </Modal>
  );
}
