import { useState } from "react";
import type { FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  ArrowRight,
  ArrowLeftRight,
  Check,
  CheckCircle2,
  ShieldCheck,
} from "lucide-react";
import { z } from "zod";
import { api, ApiError, money } from "../api";
import type { Actor, Account, Tell } from "../shared/types";
import { useData, message } from "../shared/data";
import { Status, Empty, ErrorBox, Loading, Modal } from "../shared/ui";

type Pending = {
  key: string;
  payload: {
    sourceAccountId: string;
    destinationAccountRef: string;
    amount: string;
    currency: string;
    description: string;
  };
};

export function Transfer({ actor, tell }: { actor: Actor; tell: Tell }) {
  const query = useData<Account[]>("/accounts");
  const client = useQueryClient();
  const storageKey = "ledger-pending-" + actor.id;
  const [source, setSource] = useState("");
  const [dest, setDest] = useState("");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [pending, setPending] = useState<Pending | null>(() => {
    try {
      return JSON.parse(sessionStorage.getItem(storageKey) || "null");
    } catch {
      return null;
    }
  });
  const [review, setReview] = useState(false);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<{
    reference?: string;
    amount?: string;
    status: string;
    id?: string;
  } | null>(null);
  function prepare(e: FormEvent) {
    e.preventDefault();
    setError("");
    const parsed = z
      .object({
        sourceAccountId: z.string().uuid(),
        destinationAccountRef: z.string().trim().min(1).max(45),
        amount: z
          .string()
          .regex(/^[0-9]{1,7}(\.[0-9]{1,2})?$/)
          .refine((v) => Number(v) > 0 && Number(v) <= 1000000),
        description: z.string().max(160),
      })
      .safeParse({
        sourceAccountId: source || query.data?.[0]?.id,
        destinationAccountRef: dest,
        amount,
        description,
      });
    if (!parsed.success) {
      setError(
        "Choose an account, a destination, and a positive amount with at most two decimal places.",
      );
      return;
    }
    const intent = {
      key: crypto.randomUUID(),
      payload: { ...parsed.data, currency: "INR" },
    };
    setPending(intent);
    setReview(true);
  }
  async function send() {
    if (!pending) return;
    setBusy(true);
    setError("");
    sessionStorage.setItem(storageKey, JSON.stringify(pending));
    try {
      const response = await api<{
        reference: string;
        amount: string;
        status: string;
        id: string;
      }>("/transfers", {
        method: "POST",
        headers: { "Idempotency-Key": pending.key },
        body: JSON.stringify(pending.payload),
      });
      setResult(response);
      setPending(null);
      sessionStorage.removeItem(storageKey);
      setUncertain(false);
      setReview(false);
      await client.invalidateQueries();
      tell("Transfer posted. Both account entries are recorded.");
    } catch (ex) {
      setError(message(ex));
      if (ex instanceof ApiError && ex.status === 422) {
        setResult({ status: "REJECTED" });
        setPending(null);
        sessionStorage.removeItem(storageKey);
        setReview(false);
        setUncertain(false);
      } else {
        setUncertain(true);
        setReview(true);
      }
    } finally {
      setBusy(false);
    }
  }
  const account = query.data?.find(
    (a) => a.id === (source || query.data?.[0]?.id),
  );
  return (
    <div className="transfer-grid">
      <section className="panel transfer-panel">
        <div className="panel-heading">
          <div>
            <h2>Internal transfer</h2>
            <p>Between active INR accounts in this simulation</p>
          </div>
          <span className="round-icon">
            <ArrowLeftRight size={20} />
          </span>
        </div>
        {result?.status === "POSTED" && (
          <div className="transfer-success">
            <CheckCircle2 size={35} />
            <h2>Transfer posted</h2>
            <strong>{money(result.amount || "0")}</strong>
            <p>{result.reference}</p>
            <button
              className="text-button"
              onClick={() => {
                setResult(null);
                setAmount("");
                setDescription("");
              }}
            >
              Make another transfer
              <ArrowRight size={15} />
            </button>
          </div>
        )}
        {query.isPending ? (
          <Loading />
        ) : query.error ? (
          <ErrorBox error={query.error} />
        ) : query.data.length === 0 ? (
          <Empty
            title="Open an account first"
            detail="An owned, funded demo account is needed to send a transfer."
          />
        ) : (
          result?.status !== "POSTED" && (
            <form onSubmit={prepare}>
              <label>
                From account
                <select
                  value={source || query.data[0].id}
                  onChange={(e) => setSource(e.target.value)}
                  disabled={!!pending}
                >
                  {query.data.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.name} · {money(a.balance)}
                    </option>
                  ))}
                </select>
              </label>
              {account && (
                <div className="balance-hint">
                  Available <strong>{money(account.balance)}</strong>
                  <Status value={account.status} />
                </div>
              )}
              <label>
                To demo account reference
                <input
                  required
                  placeholder="For example, DEMO-BOB"
                  value={dest}
                  maxLength={45}
                  disabled={!!pending}
                  onChange={(e) => setDest(e.target.value)}
                />
              </label>
              <label>
                Amount in INR
                <div className="amount-input">
                  <span>₹</span>
                  <input
                    required
                    inputMode="decimal"
                    placeholder="0.00"
                    value={amount}
                    disabled={!!pending}
                    onChange={(e) => setAmount(e.target.value)}
                  />
                  <span>INR</span>
                </div>
              </label>
              <label>
                Description <span className="optional">optional</span>
                <input
                  maxLength={160}
                  placeholder="What is this transfer for?"
                  value={description}
                  disabled={!!pending}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </label>
              {error && <ErrorBox error={new Error(error)} />}
              <button
                className="button primary full"
                type={pending ? "button" : "submit"}
                onClick={pending ? () => setReview(true) : undefined}
              >
                {pending ? "Resume saved transfer" : "Review transfer"}
                <ArrowRight size={17} />
              </button>
            </form>
          )
        )}
      </section>
      <aside>
        <div className="panel transfer-aside">
          <span className="round-icon green">
            <ShieldCheck size={23} />
          </span>
          <h2>
            One transfer.
            <br />
            Two balanced entries.
          </h2>
          <p>The source debit and destination credit are recorded together.</p>
          <ul className="check-list">
            <li>
              <Check size={16} />
              Funds checked before posting
            </li>
            <li>
              <Check size={16} />
              Safe to retry the same request
            </li>
            <li>
              <Check size={16} />A permanent journal reference
            </li>
          </ul>
          <div className="quiet-note">
            This workspace uses simulated money. It does not send real payments.
          </div>
        </div>
        <div className="destination-tip">
          <span className="eyebrow">TRY THE DEMO</span>
          <p>
            Alice can send to <code>DEMO-BOB</code>.<br />
            Bob can send to <code>DEMO-ALICE</code>.
          </p>
        </div>
      </aside>
      {review && pending && (
        <Modal
          title={
            uncertain ? "Check your saved transfer" : "Review your transfer"
          }
          onClose={() => {
            if (!busy) setReview(false);
          }}
        >
          <div className="review-amount">
            {money(pending.payload.amount)}
            <span>INR · internal transfer</span>
          </div>
          <dl className="detail-list">
            <dt>Destination</dt>
            <dd>{pending.payload.destinationAccountRef}</dd>
            <dt>Description</dt>
            <dd>{pending.payload.description || "Internal transfer"}</dd>
            <dt>Request key</dt>
            <dd className="mono">{pending.key}</dd>
          </dl>
          {uncertain && (
            <div className="quiet-note">
              The outcome is uncertain. Retrying below uses the original request
              key and cannot create a second debit for this intent.
            </div>
          )}
          {error && <ErrorBox error={new Error(error)} />}
          <button
            className="button primary full"
            disabled={busy}
            onClick={send}
          >
            {busy
              ? "Checking and posting…"
              : uncertain
                ? "Retry same request"
                : "Confirm simulated transfer"}
            <ArrowRight size={16} />
          </button>
          {!uncertain && !busy && (
            <button
              className="text-button full"
              onClick={() => {
                setReview(false);
                setPending(null);
              }}
            >
              Edit transfer
            </button>
          )}
        </Modal>
      )}
    </div>
  );
}
