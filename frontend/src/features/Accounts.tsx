import { useState } from "react";
import type { FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Copy, Plus } from "lucide-react";
import { api, money } from "../api";
import type { Actor, Account, Tell } from "../shared/types";
import { useData, message } from "../shared/data";
import { Status, ErrorBox, Loading, Table, Modal } from "../shared/ui";

export function Accounts({ actor, tell }: { actor: Actor; tell: Tell }) {
  const query = useData<Account[]>("/accounts");
  const client = useQueryClient();
  const [modal, setModal] = useState(false);
  const [freeze, setFreeze] = useState<Account | null>(null);
  const [reason, setReason] = useState("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      if (freeze) {
        await api(
          `/accounts/${freeze.id}/${freeze.status === "ACTIVE" ? "freeze" : "unfreeze"}`,
          { method: "POST", body: JSON.stringify({ reason }) },
        );
        tell("Account status updated.");
      } else {
        await api("/accounts", {
          method: "POST",
          body: JSON.stringify({ name }),
        });
        tell("Account opened with a zero balance.");
      }
      setModal(false);
      setFreeze(null);
      setName("");
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
          <h2>
            {actor.role === "CUSTOMER"
              ? "Your INR accounts"
              : "Customer accounts"}
          </h2>
          <p>Copy a demo reference to use it as a transfer destination.</p>
        </div>
        {actor.role === "CUSTOMER" && (
          <button className="button small" onClick={() => setModal(true)}>
            <Plus size={16} />
            Open account
          </button>
        )}
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
              label: "Account",
              cell: (a) => (
                <div className="cell-main">
                  <strong>{a.name}</strong>
                  <span>
                    {a.public_ref}
                    <button
                      className="copy-button"
                      aria-label={"Copy " + a.public_ref}
                      onClick={() =>
                        navigator.clipboard
                          .writeText(a.public_ref)
                          .then(() => tell("Account reference copied."))
                          .catch(() =>
                            tell(
                              "Copy is unavailable in this browser.",
                              "error",
                            ),
                          )
                      }
                    >
                      <Copy size={12} />
                    </button>
                  </span>
                </div>
              ),
            },
            {
              label: "Balance",
              cell: (a) => <strong>{money(a.balance)}</strong>,
            },
            { label: "Currency", cell: (a) => a.currency },
            { label: "Status", cell: (a) => <Status value={a.status} /> },
            {
              label: "Action",
              cell: (a) =>
                actor.role === "OPERATOR" ? (
                  <button
                    className="text-button compact"
                    onClick={() => {
                      setFreeze(a);
                      setReason("");
                    }}
                  >
                    {a.status === "ACTIVE"
                      ? "Freeze account"
                      : "Unfreeze account"}
                  </button>
                ) : (
                  <span className="muted">Owned account</span>
                ),
            },
          ]}
        />
      )}
      {(modal || freeze) && (
        <Modal
          title={
            freeze
              ? freeze.status === "ACTIVE"
                ? "Freeze account"
                : "Unfreeze account"
              : "Open a demo account"
          }
          onClose={() => {
            setModal(false);
            setFreeze(null);
          }}
        >
          <form onSubmit={save}>
            {freeze ? (
              <>
                <p className="form-note">
                  Account {freeze.public_ref}. Transfers check the account state
                  while holding a database lock.
                </p>
                <label>
                  Reason
                  <textarea
                    required
                    maxLength={300}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                </label>
              </>
            ) : (
              <>
                <p className="form-note">
                  New accounts start at ₹0.00. Demo opening funds are only
                  created by the local seed process.
                </p>
                <label>
                  Account name
                  <input
                    required
                    maxLength={80}
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="Everyday expenses"
                  />
                </label>
              </>
            )}
            <button className="button primary full" disabled={busy}>
              {busy ? "Saving…" : "Save account"}
            </button>
          </form>
        </Modal>
      )}
    </section>
  );
}
