import { api } from "../api";
import { relativeTime, useNow, usePolling } from "../util";

export function WorkersPage() {
  const { data, error } = usePolling(api.workers, 3000, []);
  const now = useNow();
  return (
    <>
      <header className="page-header">
        <h1>Workers</h1>
        {error && <span className="error-text">{error}</span>}
      </header>
      <p className="muted intro">
        Each worker heartbeats every 5 seconds. A worker that stops heartbeating for 15 seconds is shown as offline; any
        job it held is re-queued once its lease expires.
      </p>
      <div className="table-wrap">
        <table className="runs">
          <thead>
            <tr>
              <th aria-label="status" />
              <th>Worker</th>
              <th>Host</th>
              <th className="right">Slots</th>
              <th>Last heartbeat</th>
              <th>Started</th>
            </tr>
          </thead>
          <tbody>
            {data?.map((w) => (
              <tr key={w.id} className={w.alive ? "" : "dim"}>
                <td className="status-cell">
                  <span className={`dot ${w.alive ? "dot-on" : "dot-off"}`} title={w.alive ? "online" : "offline"} />
                </td>
                <td className="mono">{w.id}</td>
                <td className="muted">{w.hostname}</td>
                <td className="right">{w.concurrency}</td>
                <td className="muted">{relativeTime(w.lastHeartbeatAt, now)}</td>
                <td className="muted">{relativeTime(w.startedAt, now)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {data?.length === 0 && <div className="empty">No workers have registered yet.</div>}
      </div>
    </>
  );
}
