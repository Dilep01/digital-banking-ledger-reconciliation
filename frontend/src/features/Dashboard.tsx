import {
  ArrowDownLeft,
  ArrowRight,
  ArrowUpRight,
  ArrowLeftRight,
  Bell,
  BookOpen,
  CheckCircle2,
  ChevronRight,
  ClipboardList,
  Clock3,
  FileCheck2,
  FileText,
  Layers3,
  ShieldCheck,
  Wallet,
} from "lucide-react";
import { date, money, sumDecimal } from "../api";
import type { Actor, Overview } from "../shared/types";
import { useData } from "../shared/data";
import { Status, Empty, ErrorBox, Loading } from "../shared/ui";

export function Dashboard({
  actor,
  navigate,
}: {
  actor: Actor;
  navigate: (view: string) => void;
}) {
  const query = useData<Overview>("/overview");
  if (query.isPending) return <Loading />;
  if (query.error) return <ErrorBox error={query.error} />;
  const data = query.data;
  const customer = actor.role === "CUSTOMER";
  const total = sumDecimal(data.accounts.map((a) => a.balance));
  return (
    <>
      <div className="stat-grid">
        <div className="stat-card featured">
          <div className="stat-label">
            {customer ? "TOTAL BALANCE" : "CUSTOMER DEPOSITS"}
            <Wallet size={19} />
          </div>
          <strong>{money(total)}</strong>
          <span>
            {data.accounts.length} {customer ? "owned" : "visible"} account
            {data.accounts.length !== 1 ? "s" : ""} · INR
          </span>
        </div>
        <div className="stat-card">
          <div className="stat-label">
            {customer ? "TRANSFERS SENT" : "POSTED TRANSFERS"}
            <ArrowLeftRight size={19} />
          </div>
          <strong>{data.transfer_count.toLocaleString()}</strong>
          <span>
            {customer
              ? "Committed outgoing transfers"
              : "Committed internal transfers"}
          </span>
        </div>
        <div className="stat-card">
          <div className="stat-label">
            {customer ? "ACCOUNT STATUS" : "OPEN EXCEPTIONS"}
            {customer ? <ShieldCheck size={19} /> : <FileCheck2 size={19} />}
          </div>
          <strong>
            {customer
              ? data.accounts.filter((a) => a.status === "ACTIVE").length
              : data.open_exceptions}
          </strong>
          <span>
            {customer
              ? "Accounts ready for transfers"
              : "Awaiting investigation or resolution"}
          </span>
        </div>
        <div className="stat-card">
          <div className="stat-label">
            {customer ? "LATEST UPDATES" : "PENDING APPROVALS"}
            {customer ? <Bell size={19} /> : <ClipboardList size={19} />}
          </div>
          <strong>
            {customer ? data.notifications.length : data.pending_approvals}
          </strong>
          <span>
            {customer
              ? "Recent event inbox items"
              : "Corrections awaiting a decision"}
          </span>
        </div>
      </div>
      <div className="overview-grid">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>{customer ? "Your accounts" : "Account overview"}</h2>
              <p>Balances backed by journal entries</p>
            </div>
            <button
              className="text-button compact"
              onClick={() => navigate("accounts")}
            >
              View all
              <ArrowRight size={15} />
            </button>
          </div>
          {data.accounts.length === 0 ? (
            <Empty
              title="Your first account starts here"
              detail="Open a demo account to begin exploring the ledger."
            />
          ) : (
            <div className="account-cards">
              {data.accounts.slice(0, 2).map((a, i) => (
                <div key={a.id} className={"bank-card card-" + i}>
                  <div className="card-top">
                    <span>{a.name}</span>
                    <Layers3 size={23} />
                  </div>
                  <span className="card-caption">AVAILABLE BALANCE</span>
                  <strong>{money(a.balance)}</strong>
                  <div className="card-bottom">
                    <code>{a.public_ref}</code>
                    <Status value={a.status} />
                  </div>
                </div>
              ))}
            </div>
          )}
          <div className="panel-foot">
            <ShieldCheck size={15} />
            Funds checks and both account entries commit together.
          </div>
        </section>
        <section className="panel quick-panel">
          <div className="panel-heading">
            <h2>Quick actions</h2>
          </div>
          {(customer
            ? [
                [
                  ArrowLeftRight,
                  "Make a transfer",
                  "Move funds between demo accounts",
                  "transfer",
                ],
                [
                  FileText,
                  "View statements",
                  "Follow every account entry",
                  "activity",
                ],
                [
                  Clock3,
                  "Schedule a transfer",
                  "Choose when funds should move",
                  "schedules",
                ],
              ]
            : [
                [
                  FileCheck2,
                  "Run reconciliation",
                  "Check statements and ledger integrity",
                  "reconciliation",
                ],
                [
                  ClipboardList,
                  "Review corrections",
                  "A second review before a reversal",
                  "approvals",
                ],
                [
                  BookOpen,
                  "Explore the ledger",
                  "Inspect balanced journal entries",
                  "ledger",
                ],
              ]
          ).map(([Icon, title, detail, view]) => {
            const I = Icon as typeof ArrowLeftRight;
            return (
              <button
                className="quick-action"
                key={String(view)}
                onClick={() => navigate(String(view))}
              >
                <span className="quick-icon">
                  <I size={20} />
                </span>
                <span>
                  <strong>{String(title)}</strong>
                  <small>{String(detail)}</small>
                </span>
                <ChevronRight size={17} />
              </button>
            );
          })}
        </section>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>Recent activity</h2>
            <p>
              {customer
                ? "A closer look at your latest ledger entries"
                : "Recent actions across the workspace"}
            </p>
          </div>
          <button
            className="text-button compact"
            onClick={() => navigate(customer ? "activity" : "audit")}
          >
            View history
            <ArrowRight size={15} />
          </button>
        </div>
        <div className="activity-list">
          {data.recent.length === 0 ? (
            <Empty
              title="No activity yet"
              detail="Posted entries and staff actions will appear here."
            />
          ) : (
            data.recent.map((r, i) => (
              <div className="activity-row" key={i}>
                <span
                  className={
                    "activity-icon " + (r.side === "DEBIT" ? "outgoing" : "")
                  }
                >
                  {customer ? (
                    r.side === "DEBIT" ? (
                      <ArrowUpRight size={19} />
                    ) : (
                      <ArrowDownLeft size={19} />
                    )
                  ) : (
                    <ShieldCheck size={19} />
                  )}
                </span>
                <div className="activity-text">
                  <strong>
                    {customer ? r.description : r.action.replaceAll("_", " ")}
                  </strong>
                  <span>{customer ? r.public_ref : r.reason}</span>
                </div>
                <span className="activity-date">
                  {date(customer ? r.posted_at : r.occurred_at)}
                </span>
                <div className="activity-amount">
                  {customer ? (
                    <>
                      <strong>
                        {r.side === "DEBIT" ? "−" : "+"}
                        {money(r.amount || "0")}
                      </strong>
                      <span>Posted</span>
                    </>
                  ) : (
                    <Status value={r.outcome} />
                  )}
                </div>
              </div>
            ))
          )}
        </div>
      </section>
      {data.notifications.length > 0 && (
        <section className="panel inbox-panel">
          <div className="panel-heading">
            <h2>
              <Bell size={17} />
              Event inbox
            </h2>
          </div>
          {data.notifications.map((n) => (
            <div className="inbox-row" key={n.id}>
              <CheckCircle2 size={15} />
              <span>
                {n.message
                  .replace("FUNDING_POSTED:", "Opening funds recorded:")
                  .replace("TRANSFER_POSTED:", "Transfer posted:")
                  .replace("REVERSAL_POSTED:", "Transfer reversed:")}
              </span>
              <small>{date(n.created_at)}</small>
            </div>
          ))}
        </section>
      )}
    </>
  );
}
