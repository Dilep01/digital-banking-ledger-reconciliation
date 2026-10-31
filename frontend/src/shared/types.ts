export type Actor = { id: string; email: string; role: string; name: string };

export type Account = {
  id: string;
  public_ref: string;
  name: string;
  balance: string;
  status: string;
  currency: string;
};

export type Entry = {
  id: string;
  reference: string;
  event_type: string;
  description: string;
  posted_at: string;
  side: string;
  signed_amount: string;
  amount?: string;
  public_ref?: string;
  journal_id: string;
  transfer_id: string | null;
};

export type Audit = {
  id: string;
  action: string;
  target: string;
  outcome: string;
  reason: string;
  occurred_at: string;
  actor_name: string | null;
  correlation_id: string;
};

export type Overview = {
  accounts: Account[];
  transfer_count: number;
  open_exceptions?: number;
  pending_approvals?: number;
  recent: (Entry & Audit)[];
  notifications: { id: string; message: string; created_at: string }[];
};

export type Run = {
  id: string;
  kind: string;
  matched_count: number;
  exception_count: number;
  created_at: string;
  snapshot_at: string;
  interval_start: string;
  interval_end: string;
  exceptions?: ReconException[];
};

export type ReconException = {
  id: string;
  category: string;
  reference: string;
  detail: string;
  state: string;
  run_id: string;
};

export type Import = {
  id: string;
  source: string;
  original_name: string;
  row_count: number;
  invalid_rows: number;
  created_at: string;
};

export type Correction = {
  id: string;
  reference: string;
  reason: string;
  requester_name: string;
  state: string;
  reversal_id: string | null;
  decision_reason: string | null;
};

export type Journal = {
  id: string;
  reference: string;
  event_type: string;
  description: string;
  posted_at: string;
};

export type Scheduled = {
  id: string;
  destination_ref: string;
  amount: string;
  description: string;
  due_at: string;
  state: string;
  result: string | null;
  attempts: number;
};

export type Outbox = {
  id: string;
  event_type: string;
  state: string;
  attempts: number;
  created_at: string;
  last_error: string | null;
};

export type Notice = { kind: "success" | "error"; text: string };

export type Tell = (text: string, kind?: "success" | "error") => void;
