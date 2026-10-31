import { useState } from "react";
import type { FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  ArrowDownLeft,
  ArrowRight,
  ArrowUpRight,
  Layers3,
  ShieldCheck,
} from "lucide-react";
import { api, refreshCsrf } from "../api";
import { message } from "../shared/data";
import { ErrorBox } from "../shared/ui";

export function Login() {
  const client = useQueryClient();
  const [register, setRegister] = useState(false);
  const [email, setEmail] = useState("alice@ledger.local");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await refreshCsrf();
      if (register)
        await api("/auth/register", {
          method: "POST",
          body: JSON.stringify({ name, email, password }),
        });
      await api("/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ email, password }).toString(),
      });
      await refreshCsrf();
      client.clear();
      window.location.reload();
    } catch (ex) {
      setError(message(ex));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login-page">
      <section className="login-story">
        <div className="brand">
          <span className="brand-mark">
            <Layers3 size={25} />
          </span>
          <span>
            ledger<span className="brand-dot">.</span>
          </span>
        </div>
        <div>
          <span className="eyebrow light">SIMULATED BANKING WORKSPACE</span>
          <h1>
            Every movement.
            <br />
            Accounted for.
          </h1>
          <p>
            Transfer funds, follow the ledger, and resolve differences in one
            local workspace.
          </p>
          <div className="story-entry">
            <span>
              <ArrowUpRight />
              Alice · Everyday
            </span>
            <strong>− ₹500.00</strong>
          </div>
          <div className="story-entry">
            <span>
              <ArrowDownLeft />
              Bob · Everyday
            </span>
            <strong>+ ₹500.00</strong>
          </div>
          <div className="story-note">
            <ShieldCheck size={16} />
            Balanced entries. A traceable history.
          </div>
        </div>
        <small>Learning project · Synthetic identities and funds</small>
      </section>
      <section className="login-form">
        <div className="login-form-inner">
          <span className="eyebrow">YOUR WORKSPACE</span>
          <h2>{register ? "Create a demo identity" : "Welcome back"}</h2>
          <p>
            {register
              ? "New accounts start with a zero balance."
              : "Sign in to your banking workspace."}
          </p>
          <form onSubmit={submit}>
            {register && (
              <label>
                Your name
                <input
                  required
                  maxLength={80}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  autoComplete="name"
                />
              </label>
            )}
            <label>
              Email address
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="username"
              />
            </label>
            <label>
              Password
              <input
                type="password"
                required
                minLength={register ? 12 : 1}
                maxLength={64}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete={register ? "new-password" : "current-password"}
              />
            </label>
            {error && <ErrorBox error={new Error(error)} />}
            <button className="button primary full" disabled={busy}>
              {busy
                ? "Signing in…"
                : register
                  ? "Create account & sign in"
                  : "Sign in"}
              <ArrowRight size={17} />
            </button>
          </form>
          <button
            className="text-button"
            onClick={() => {
              setRegister(!register);
              setError("");
            }}
          >
            {register
              ? "Already have an identity? Sign in"
              : "Create a customer identity"}
          </button>
          <div className="demo-accounts">
            <p>
              Local demo accounts <span>when demo mode is enabled</span>
            </p>
            <div>
              {["alice", "bob", "operator", "approver", "auditor"].map(
                (user) => (
                  <button
                    key={user}
                    onClick={() => {
                      setEmail(user + "@ledger.local");
                      setPassword("DemoBank!2026");
                      setRegister(false);
                    }}
                  >
                    {user}
                  </button>
                ),
              )}
            </div>
            <small>
              Demo password: <code>DemoBank!2026</code>
            </small>
          </div>
        </div>
      </section>
    </div>
  );
}
