import { useState } from "react";
import { ChevronLeft, ChevronRight, Search } from "lucide-react";
import { date } from "../api";
import type { Audit } from "../shared/types";
import { useData, short } from "../shared/data";
import { Status, ErrorBox, Loading, Table } from "../shared/ui";

export function AuditTrail() {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [draft, setDraft] = useState("");
  const query = useData<Audit[]>(
    `/audit/events?page=${page}&search=${encodeURIComponent(search)}`,
  );
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>Audit trail</h2>
          <p>Actor, action, outcome, and reason for recorded operations</p>
        </div>
        <form
          className="search-form"
          onSubmit={(e) => {
            e.preventDefault();
            setSearch(draft);
            setPage(0);
          }}
        >
          <Search size={16} />
          <input
            aria-label="Search audit actions or targets"
            placeholder="Search action or reference"
            maxLength={100}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
          <button className="text-button compact">Search</button>
        </form>
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
              label: "Action",
              cell: (r) => (
                <div className="cell-main">
                  <strong>{r.action.replaceAll("_", " ")}</strong>
                  <span className="mono" title={r.target}>
                    {short(r.target)}
                  </span>
                </div>
              ),
            },
            { label: "Actor", cell: (r) => r.actor_name || "Unauthenticated" },
            { label: "Outcome", cell: (r) => <Status value={r.outcome} /> },
            { label: "Reason", cell: (r) => r.reason },
            {
              label: "Time",
              cell: (r) => (
                <span title={"Correlation: " + r.correlation_id}>
                  {date(r.occurred_at)}
                </span>
              ),
            },
          ]}
        />
      )}
      <div className="pagination">
        <span>Page {page + 1}</span>
        <button
          aria-label="Previous audit page"
          disabled={page === 0}
          onClick={() => setPage(page - 1)}
        >
          <ChevronLeft size={17} />
        </button>
        <button
          aria-label="Next audit page"
          disabled={(query.data?.length || 0) < 50}
          onClick={() => setPage(page + 1)}
        >
          <ChevronRight size={17} />
        </button>
      </div>
    </section>
  );
}
