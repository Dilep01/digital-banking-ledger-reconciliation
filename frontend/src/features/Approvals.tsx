import { useState } from "react";
import type { FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ShieldCheck } from "lucide-react";
import { api } from "../api";
import type { Actor, Correction, Tell } from "../shared/types";
import { useData, short, message } from "../shared/data";
import { Status, ErrorBox, Loading, Table, Modal } from "../shared/ui";

export function Approvals({ actor, tell }: { actor: Actor; tell: Tell }) {
  const query = useData<Correction[]>("/ops/correction-requests");
  const client = useQueryClient();
  const [decision, setDecision] = useState<{
    id: string;
    approve: boolean;
  } | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  async function save(e: FormEvent) {
    e.preventDefault();
    if (!decision) return;
    setBusy(true);
    try {
      await api(
        "/ops/correction-requests/" +
          decision.id +
          "/" +
          (decision.approve ? "approve" : "reject"),
        { method: "POST", body: JSON.stringify({ reason: note }) },
      );
      tell(
        decision.approve
          ? "Full reversal posted. Original entries remain available."
          : "Correction request rejected.",
      );
      setDecision(null);
      setNote("");
      await client.invalidateQueries();
    } catch (ex) {
      tell(message(ex), "error");
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <div className="info-strip">
        <ShieldCheck size={19} />
        <p>
          A different staff member must approve a correction. Approval still
          checks balances and account status.
        </p>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <h2>Correction requests</h2>
          <span className="muted">Full reversals only</span>
        </div>
        {query.isPending ? (
          <Loading />
        ) : query.error ? (
          <ErrorBox error={query.error} />
        ) : (
          <Table
            rows={query.data}
            columns={[
              {
                label: "Original transfer",
                cell: (r) => (
                  <div className="cell-main">
                    <strong className="mono">{short(r.reference)}</strong>
                    <span>{r.requester_name}</span>
                  </div>
                ),
              },
              { label: "Reason", cell: (r) => r.reason },
              { label: "Status", cell: (r) => <Status value={r.state} /> },
              {
                label: "Decision",
                cell: (r) =>
                  r.state === "PENDING" && actor.role === "APPROVER" ? (
                    <div className="button-row">
                      <button
                        className="button small"
                        onClick={() =>
                          setDecision({ id: r.id, approve: false })
                        }
                      >
                        Reject
                      </button>
                      <button
                        className="button primary small"
                        onClick={() => setDecision({ id: r.id, approve: true })}
                      >
                        Review reversal
                      </button>
                    </div>
                  ) : (
                    r.decision_reason || "Awaiting approver"
                  ),
              },
            ]}
            empty="No correction requests. Operations can request a reversal from the ledger or an exception."
          />
        )}
      </section>
      {decision && (
        <Modal
          title={
            decision.approve
              ? "Approve full reversal"
              : "Reject correction request"
          }
          onClose={() => setDecision(null)}
        >
          <form onSubmit={save}>
            <p className="form-note">
              {decision.approve
                ? "Approval creates a new journal with reversed entries. It cannot make a customer balance negative."
                : "The original posting stays unchanged. Your decision is recorded in the audit trail."}
            </p>
            <label>
              Decision reason
              <textarea
                required
                maxLength={300}
                value={note}
                onChange={(e) => setNote(e.target.value)}
              />
            </label>
            <button className="button primary full" disabled={busy}>
              {busy
                ? "Processing…"
                : decision.approve
                  ? "Approve and post reversal"
                  : "Reject request"}
            </button>
          </form>
        </Modal>
      )}
    </>
  );
}
