import { useState } from "react";
import type { FormEvent } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Layers3, Plus, RefreshCw } from "lucide-react";
import { api, date, money } from "../api";
import type { Actor, Account, Scheduled, Outbox, Tell } from "../shared/types";
import { useData, short, message, localInput } from "../shared/data";
import { Status, ErrorBox, Loading, Table, Modal } from "../shared/ui";

export function Schedules({ actor, tell }: { actor: Actor; tell: Tell }) {
  const query = useData<Scheduled[]>("/scheduled-transfers");
  const accounts = useData<Account[]>("/accounts");
  const client = useQueryClient();
  const [modal, setModal] = useState(false);
  const [busy, setBusy] = useState(false);
  const [source, setSource] = useState("");
  const [dest, setDest] = useState("");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [due, setDue] = useState(localInput(new Date(Date.now() + 3600000)));
  const [key, setKey] = useState(crypto.randomUUID());
  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api("/scheduled-transfers", {
        method: "POST",
        headers: { "Idempotency-Key": key },
        body: JSON.stringify({
          sourceAccountId: source || accounts.data?.[0]?.id,
          destinationAccountRef: dest,
          amount,
          description,
          dueAt: new Date(due).toISOString(),
        }),
      });
      tell("Transfer scheduled. Funds will be checked when it is due.");
      setModal(false);
      setKey(crypto.randomUUID());
      await client.invalidateQueries();
    } catch (ex) {
      tell(message(ex), "error");
    } finally {
      setBusy(false);
    }
  }
  async function cancel(id: string) {
    try {
      await api("/scheduled-transfers/" + id + "/cancel", { method: "POST" });
      tell("Schedule cancelled.");
      await client.invalidateQueries();
    } catch (ex) {
      tell(message(ex), "error");
    }
  }
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>Scheduled transfers</h2>
          <p>
            Funds are not reserved. The worker rechecks account status and
            balance at execution.
          </p>
        </div>
        <button className="button small" onClick={() => setModal(true)}>
          <Plus size={16} />
          Schedule transfer
        </button>
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
              label: "Destination",
              cell: (r) => (
                <div className="cell-main">
                  <strong>{r.destination_ref}</strong>
                  <span>{r.description}</span>
                </div>
              ),
            },
            { label: "Amount", cell: (r) => money(r.amount) },
            { label: "Due", cell: (r) => date(r.due_at) },
            { label: "Status", cell: (r) => <Status value={r.state} /> },
            {
              label: "Result",
              cell: (r) => (
                <span title={r.result || ""}>
                  {r.state === "PENDING" ? (
                    <button
                      className="text-button compact"
                      onClick={() => cancel(r.id)}
                    >
                      Cancel schedule
                    </button>
                  ) : r.state === "REJECTED" ? (
                    "Business rules rejected this transfer"
                  ) : (
                    `${r.attempts} attempt(s)`
                  )}
                </span>
              ),
            },
          ]}
        />
      )}
      {modal && (
        <Modal
          title="Schedule a simulated transfer"
          onClose={() => setModal(false)}
        >
          <form onSubmit={save}>
            <label>
              From
              <select
                value={source || accounts.data?.[0]?.id || ""}
                onChange={(e) => {
                  setSource(e.target.value);
                  setKey(crypto.randomUUID());
                }}
              >
                {accounts.data?.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Destination reference
              <input
                required
                value={dest}
                maxLength={45}
                onChange={(e) => {
                  setDest(e.target.value);
                  setKey(crypto.randomUUID());
                }}
              />
            </label>
            <div className="form-pair">
              <label>
                Amount (INR)
                <input
                  inputMode="decimal"
                  required
                  value={amount}
                  onChange={(e) => {
                    setAmount(e.target.value);
                    setKey(crypto.randomUUID());
                  }}
                />
              </label>
              <label>
                Due (local time)
                <input
                  type="datetime-local"
                  required
                  value={due}
                  onChange={(e) => {
                    setDue(e.target.value);
                    setKey(crypto.randomUUID());
                  }}
                />
              </label>
            </div>
            <label>
              Description
              <input
                required
                maxLength={160}
                value={description}
                onChange={(e) => {
                  setDescription(e.target.value);
                  setKey(crypto.randomUUID());
                }}
              />
            </label>
            <button
              className="button primary full"
              disabled={busy || actor.role !== "CUSTOMER"}
            >
              {busy ? "Scheduling…" : "Save schedule"}
            </button>
          </form>
        </Modal>
      )}
    </section>
  );
}

export function Background({ actor, tell }: { actor: Actor; tell: Tell }) {
  const query = useQuery<{ outbox: Outbox[]; schedules: Scheduled[] }>({
    queryKey: ["/ops/jobs"],
    queryFn: () => api("/ops/jobs"),
    refetchInterval: 5000,
  });
  const client = useQueryClient();
  async function retry(id: string) {
    try {
      await api("/ops/outbox/" + id + "/retry", { method: "POST" });
      tell("Failed event requeued with the same event identity.");
      await client.invalidateQueries();
    } catch (ex) {
      tell(message(ex), "error");
    }
  }
  return (
    <>
      <div className="info-strip">
        <Layers3 size={20} />
        <p>
          Events are committed with transfers. Durable workers process inbox
          updates and scheduled transfers; repeated delivery is deduplicated.
        </p>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <h2>Event delivery</h2>
          <button
            className="text-button compact"
            onClick={() => query.refetch()}
          >
            <RefreshCw size={14} />
            Refresh
          </button>
        </div>
        {query.isPending ? (
          <Loading />
        ) : query.error ? (
          <ErrorBox error={query.error} />
        ) : (
          <Table
            rows={query.data.outbox}
            columns={[
              {
                label: "Event",
                cell: (r) => (
                  <div className="cell-main">
                    <strong>{r.event_type.replaceAll("_", " ")}</strong>
                    <span className="mono">{short(r.id)}</span>
                  </div>
                ),
              },
              { label: "Status", cell: (r) => <Status value={r.state} /> },
              { label: "Attempts", cell: (r) => r.attempts },
              { label: "Created", cell: (r) => date(r.created_at) },
              {
                label: "Recovery",
                cell: (r) =>
                  r.state === "FAILED" && actor.role === "OPERATOR" ? (
                    <button
                      className="button small"
                      onClick={() => retry(r.id)}
                    >
                      Retry event
                    </button>
                  ) : (
                    r.last_error || "—"
                  ),
              },
            ]}
          />
        )}
      </section>
      <section className="panel">
        <div className="panel-heading">
          <h2>Scheduled work</h2>
        </div>
        <Table
          rows={query.data?.schedules || []}
          columns={[
            { label: "Destination", cell: (r) => r.destination_ref },
            { label: "Amount", cell: (r) => money(r.amount) },
            { label: "Due", cell: (r) => date(r.due_at) },
            { label: "State", cell: (r) => <Status value={r.state} /> },
            { label: "Attempts", cell: (r) => r.attempts },
          ]}
        />
      </section>
    </>
  );
}
