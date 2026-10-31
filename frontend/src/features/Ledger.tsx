import { useState } from "react";
import type { FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { BookOpen } from "lucide-react";
import { api, date } from "../api";
import type { Actor, Journal, Tell } from "../shared/types";
import { useData, short, message } from "../shared/data";
import { ErrorBox, Loading, Table, Modal, Evidence } from "../shared/ui";

export function LedgerExplorer({ actor, tell }: { actor: Actor; tell: Tell }) {
  const query = useData<Journal[]>("/audit/journals");
  const client = useQueryClient();
  const [evidence, setEvidence] = useState<unknown>(null);
  const [journal, setJournal] = useState<Journal | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  async function request(e: FormEvent) {
    e.preventDefault();
    if (!journal) return;
    setBusy(true);
    try {
      await api("/ops/correction-requests", {
        method: "POST",
        body: JSON.stringify({ journalId: journal.id, reason }),
      });
      tell(
        "Reversal requested. Review it in Approvals with a separate staff identity.",
      );
      setJournal(null);
      setReason("");
      await client.invalidateQueries();
    } catch (ex) {
      tell(message(ex), "error");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>Posted journals</h2>
          <p>
            Original entries stay unchanged. Corrections add a reversing
            journal.
          </p>
        </div>
        <BookOpen size={20} />
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
              label: "Reference",
              cell: (r) => (
                <button
                  className="text-button compact mono"
                  onClick={() =>
                    api("/audit/journals/" + r.id)
                      .then(setEvidence)
                      .catch((ex) => tell(message(ex), "error"))
                  }
                >
                  {short(r.reference)}
                </button>
              ),
            },
            { label: "Type", cell: (r) => r.event_type },
            { label: "Description", cell: (r) => r.description },
            { label: "Posted", cell: (r) => date(r.posted_at) },
            {
              label: "Action",
              cell: (r) =>
                actor.role === "OPERATOR" && r.event_type === "TRANSFER" ? (
                  <button
                    className="text-button compact"
                    onClick={() => setJournal(r)}
                  >
                    Request reversal
                  </button>
                ) : (
                  <span className="muted">Read only</span>
                ),
            },
          ]}
        />
      )}
      {!!evidence && (
        <Modal
          title="Journal and balanced entries"
          wide
          onClose={() => setEvidence(null)}
        >
          <Evidence data={evidence} />
        </Modal>
      )}
      {journal && (
        <Modal title="Request a full reversal" onClose={() => setJournal(null)}>
          <form onSubmit={request}>
            <p className="form-note">Original reference: {journal.reference}</p>
            <label>
              Reason
              <textarea
                required
                maxLength={300}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </label>
            <button className="button primary full" disabled={busy}>
              {busy ? "Saving…" : "Submit for approval"}
            </button>
          </form>
        </Modal>
      )}
    </section>
  );
}
