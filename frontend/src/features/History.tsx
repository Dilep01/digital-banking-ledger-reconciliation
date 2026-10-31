import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Download } from "lucide-react";
import { api, date, money } from "../api";
import type { Account, Entry } from "../shared/types";
import { useData, short, message, initialInterval } from "../shared/data";
import { ErrorBox, Loading, Table, Modal, Evidence } from "../shared/ui";

export function History() {
  const accounts = useData<Account[]>("/accounts");
  const [selected, setSelected] = useState("");
  const [page, setPage] = useState(0);
  const [interval, setInterval] = useState(initialInterval);
  const [evidence, setEvidence] = useState<unknown>(null);
  const id = selected || accounts.data?.[0]?.id || "";
  const suffix = `?page=${page}&size=15&start=${encodeURIComponent(new Date(interval.start).toISOString())}&end=${encodeURIComponent(new Date(interval.end).toISOString())}`;
  const entries = useQuery<Entry[]>({
    queryKey: ["/accounts/" + id + "/transactions" + suffix],
    queryFn: () => api<Entry[]>("/accounts/" + id + "/transactions" + suffix),
    enabled: !!id,
  });
  return (
    <section className="panel">
      <div className="panel-heading">
        <h2>Account history</h2>
        {id && (
          <a
            className="button small"
            href={`/api/v1/accounts/${id}/statement.csv?start=${encodeURIComponent(new Date(interval.start).toISOString())}&end=${encodeURIComponent(new Date(interval.end).toISOString())}`}
          >
            <Download size={15} />
            Export CSV
          </a>
        )}
      </div>
      <div className="filters">
        <label>
          Account
          <select
            value={id}
            onChange={(e) => {
              setSelected(e.target.value);
              setPage(0);
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
          From (local time)
          <input
            type="datetime-local"
            required
            value={interval.start}
            onChange={(e) => {
              if (e.target.value) {
                setInterval({ ...interval, start: e.target.value });
                setPage(0);
              }
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
              if (e.target.value) {
                setInterval({ ...interval, end: e.target.value });
                setPage(0);
              }
            }}
          />
        </label>
      </div>
      {accounts.isPending || (entries.isPending && id) ? (
        <Loading />
      ) : accounts.error || entries.error ? (
        <ErrorBox error={accounts.error || entries.error} />
      ) : (
        <Table
          rows={entries.data || []}
          columns={[
            {
              label: "Entry",
              cell: (r) => (
                <div className="cell-main">
                  <strong>{r.description}</strong>
                  <span className="mono">{short(r.reference)}</span>
                </div>
              ),
            },
            { label: "Date", cell: (r) => date(r.posted_at) },
            { label: "Type", cell: (r) => r.event_type },
            {
              label: "Amount",
              cell: (r) => (
                <strong className={r.side === "DEBIT" ? "" : "positive-text"}>
                  {money(r.signed_amount)}
                </strong>
              ),
            },
            {
              label: "Details",
              cell: (r) =>
                r.transfer_id ? (
                  <button
                    className="text-button compact"
                    onClick={() =>
                      api("/transfers/" + r.transfer_id)
                        .then(setEvidence)
                        .catch((ex) => setEvidence({ error: message(ex) }))
                    }
                  >
                    View transfer
                    <ChevronRight size={13} />
                  </button>
                ) : (
                  <span className="muted">
                    {r.event_type === "FUNDING"
                      ? "Opening journal"
                      : "Reversal journal"}
                  </span>
                ),
            },
          ]}
          empty="No entries in this interval."
        />
      )}
      <div className="pagination">
        <span>Page {page + 1} · newest entries first</span>
        <button
          aria-label="Previous page"
          disabled={page === 0}
          onClick={() => setPage(page - 1)}
        >
          <ChevronLeft size={17} />
        </button>
        <button
          aria-label="Next page"
          disabled={(entries.data?.length || 0) < 15}
          onClick={() => setPage(page + 1)}
        >
          <ChevronRight size={17} />
        </button>
      </div>
      {!!evidence && (
        <Modal title="Transfer details" wide onClose={() => setEvidence(null)}>
          <Evidence data={evidence} />
        </Modal>
      )}
    </section>
  );
}
