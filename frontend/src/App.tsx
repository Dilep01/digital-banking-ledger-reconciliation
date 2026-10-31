import { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  ArrowUpRight,
  ArrowLeftRight,
  BookOpen,
  CheckCircle2,
  ChevronRight,
  ClipboardList,
  Clock3,
  FileCheck2,
  Layers3,
  LayoutDashboard,
  LogOut,
  Plus,
  ShieldCheck,
  Wallet,
  X,
  Menu,
  AlertCircle,
} from "lucide-react";
import { api, ApiError, refreshCsrf } from "./api";
import type { Actor, Notice, Tell } from "./shared/types";
import { useData, message } from "./shared/data";
import { ErrorBox, Loading } from "./shared/ui";

import { Login } from "./features/Auth";
import { Dashboard } from "./features/Dashboard";
import { Accounts } from "./features/Accounts";
import { Transfer } from "./features/Transfer";
import { History } from "./features/History";
import { Schedules, Background } from "./features/Jobs";
import { Reconciliation } from "./features/Reconciliation";
import { Approvals } from "./features/Approvals";
import { LedgerExplorer } from "./features/Ledger";
import { AuditTrail } from "./features/Audit";

export default function App() {
  const me = useData<Actor>("/auth/me");
  const client = useQueryClient();
  const [view, setView] = useState("overview");
  const [notice, setNotice] = useState<Notice | null>(null);
  const [mobile, setMobile] = useState(false);
  const tell: Tell = (text, kind = "success") => {
    setNotice({ text, kind });
  };
  useEffect(() => {
    if (!notice) return;
    const id = setTimeout(() => setNotice(null), 9000);
    return () => clearTimeout(id);
  }, [notice]);
  if (me.isPending) return <Loading />;
  if (me.error) {
    if (me.error instanceof ApiError && me.error.status === 401)
      return <Login />;
    return (
      <div className="offline">
        <h1>Workspace unavailable</h1>
        <ErrorBox error={me.error} />
        <button className="button" onClick={() => me.refetch()}>
          Retry connection
        </button>
      </div>
    );
  }
  const actor = me.data;
  const customer = actor.role === "CUSTOMER";
  const nav = customer
    ? ([
        ["overview", "Overview", LayoutDashboard],
        ["accounts", "Accounts", Wallet],
        ["transfer", "Transfer money", ArrowLeftRight],
        ["activity", "Activity", Activity],
        ["schedules", "Scheduled transfers", Clock3],
      ] as const)
    : ([
        ["overview", "Overview", LayoutDashboard],
        ["accounts", "Accounts", Wallet],
        ["reconciliation", "Reconciliation", FileCheck2],
        ["approvals", "Approvals", ClipboardList],
        ["ledger", "Ledger explorer", BookOpen],
        ["audit", "Audit trail", ShieldCheck],
        ["jobs", "Background activity", Layers3],
      ] as const);
  const navigate = (next: string) => {
    setView(next);
    setMobile(false);
  };
  async function logout() {
    try {
      await refreshCsrf();
      await api("/auth/logout", { method: "POST" });
      await refreshCsrf();
      client.clear();
      window.location.reload();
    } catch (ex) {
      tell(message(ex), "error");
    }
  }
  return (
    <div className="app-shell">
      <aside className={"sidebar " + (mobile ? "mobile-open" : "")}>
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            navigate("overview");
          }}
        >
          <span className="brand-mark">
            <Layers3 size={23} />
          </span>
          <span>
            ledger<span className="brand-dot">.</span>
          </span>
        </a>
        <span className="sidebar-label">BANKING WORKSPACE</span>
        <nav>
          {nav.map(([id, label, Icon]) => (
            <button
              className={view === id ? "active" : ""}
              key={id}
              onClick={() => navigate(id)}
            >
              <Icon size={19} />
              {label}
              {id === "transfer" && (
                <ArrowUpRight size={15} className="nav-arrow" />
              )}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="simulation-note">
            <ShieldCheck size={20} />
            <div>
              <strong>A safe place to learn</strong>
              <p>
                Simulated funds.
                <br />
                No real bank connections.
              </p>
            </div>
          </div>
          <div className="profile">
            <span className="avatar">{actor.name.slice(0, 1)}</span>
            <div>
              <strong>{actor.name}</strong>
              <span>{actor.role.toLowerCase()}</span>
            </div>
            <button aria-label="Sign out" title="Sign out" onClick={logout}>
              <LogOut size={18} />
            </button>
          </div>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <div>
            <button
              className="icon-button mobile-toggle"
              aria-label="Open navigation"
              onClick={() => setMobile(!mobile)}
            >
              <Menu />
            </button>
            <span className="breadcrumb">
              Workspace <ChevronRight size={13} />{" "}
              <strong>{nav.find((n) => n[0] === view)?.[1]}</strong>
            </span>
          </div>
          <div className="topbar-right">
            <span className="environment">
              <i />
              LOCAL DEMO
            </span>
            <span className="topbar-divider" />
            <span className="role-tag">{actor.role.toLowerCase()}</span>
          </div>
        </header>
        <main>
          <div className="page-heading">
            <div>
              <span className="eyebrow">
                {customer ? "PERSONAL BANKING" : "OPERATIONS WORKSPACE"}
              </span>
              <h1>
                {view === "overview"
                  ? `Good to see you, ${actor.name.split(" ")[0]}.`
                  : nav.find((n) => n[0] === view)?.[1]}
              </h1>
              <p>
                {view === "overview"
                  ? "Your accounts and the activity behind every balance."
                  : view === "reconciliation"
                    ? "Compare synthetic statements, investigate differences, and preserve the evidence."
                    : view === "transfer"
                      ? "Move simulated INR between internal accounts."
                      : view === "approvals"
                        ? "Review requested corrections before funds move."
                        : "A clear record of your simulated banking activity."}
              </p>
            </div>
            {view === "overview" && customer && (
              <button
                className="button primary"
                onClick={() => navigate("transfer")}
              >
                <Plus size={17} />
                New transfer
              </button>
            )}
          </div>
          {notice && (
            <div className={"notice " + notice.kind} role="status">
              {notice.kind === "success" ? (
                <CheckCircle2 size={18} />
              ) : (
                <AlertCircle size={18} />
              )}
              <span>{notice.text}</span>
              <button
                aria-label="Dismiss notification"
                onClick={() => setNotice(null)}
              >
                <X size={17} />
              </button>
            </div>
          )}
          {view === "overview" && (
            <Dashboard actor={actor} navigate={navigate} />
          )}
          {view === "accounts" && <Accounts actor={actor} tell={tell} />}
          {view === "transfer" && <Transfer actor={actor} tell={tell} />}
          {view === "activity" && <History />}
          {view === "schedules" && <Schedules actor={actor} tell={tell} />}
          {view === "reconciliation" && (
            <Reconciliation actor={actor} tell={tell} />
          )}
          {view === "approvals" && <Approvals actor={actor} tell={tell} />}
          {view === "ledger" && <LedgerExplorer actor={actor} tell={tell} />}
          {view === "audit" && <AuditTrail />}
          {view === "jobs" && <Background actor={actor} tell={tell} />}
          <footer>
            <span>
              <ShieldCheck size={13} />
              Simulated banking · All amounts in INR
            </span>
            <span>Ledger / Local workspace</span>
          </footer>
        </main>
      </div>
    </div>
  );
}
