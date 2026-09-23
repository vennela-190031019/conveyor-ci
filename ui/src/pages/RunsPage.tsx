import { api, type RunSummary } from "../api";
import { StatusIcon } from "../components/Status";
import { navigate } from "../router";
import { commitUrl, duration, relativeTime, shortSha, useNow, usePolling } from "../util";

function Trigger({ run }: { run: RunSummary }) {
  return run.source === "GITHUB_PUSH" ? (
    <span className="chip" title="Triggered by a GitHub push">
      <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden>
        <path
          fill="currentColor"
          d="M8 0a8 8 0 0 0-2.53 15.59c.4.07.55-.17.55-.38v-1.33c-2.23.48-2.7-1.07-2.7-1.07-.36-.92-.89-1.17-.89-1.17-.73-.5.05-.49.05-.49.8.06 1.23.83 1.23.83.72 1.22 1.87.87 2.33.66.07-.52.28-.87.5-1.07-1.78-.2-3.65-.89-3.65-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.22 2.2.82a7.6 7.6 0 0 1 4 0c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.28.82 2.15 0 3.07-1.87 3.75-3.66 3.95.29.25.54.73.54 1.48v2.2c0 .21.15.46.55.38A8 8 0 0 0 8 0Z"
        />
      </svg>
      push
    </span>
  ) : (
    <span className="chip" title="Triggered from the API">
      api
    </span>
  );
}

export function RunsTable({ runs, showProject }: { runs: RunSummary[]; showProject: boolean }) {
  const now = useNow();
  if (runs.length === 0) {
    return (
      <div className="empty">
        <p>No runs yet.</p>
        <p className="muted">Push to a connected repository, or trigger one with <code>scripts/trigger.sh</code>.</p>
      </div>
    );
  }
  return (
    <div className="table-wrap">
      <table className="runs">
        <thead>
          <tr>
            <th aria-label="status" />
            <th>Run</th>
            <th>Commit</th>
            <th>Trigger</th>
            <th>Started</th>
            <th className="right">Duration</th>
          </tr>
        </thead>
        <tbody>
          {runs.map((run) => {
            const url = commitUrl(run.project, run.commitSha);
            return (
              <tr key={run.id} onClick={() => navigate(`/runs/${run.id}`)} className="clickable">
                <td className="status-cell">
                  <StatusIcon status={run.status} />
                </td>
                <td>
                  <div className="run-title">
                    {showProject && <span className="muted">{run.project} </span>}
                    <strong>#{run.runNumber}</strong> <span>{run.pipelineName}</span>
                  </div>
                </td>
                <td>
                  <span className="branch">{run.branch}</span>{" "}
                  {url ? (
                    <a className="sha" href={url} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}>
                      {shortSha(run.commitSha)}
                    </a>
                  ) : (
                    <span className="sha">{shortSha(run.commitSha)}</span>
                  )}
                </td>
                <td>
                  <Trigger run={run} />
                </td>
                <td className="muted">{relativeTime(run.startedAt ?? run.createdAt, now)}</td>
                <td className="right mono">{duration(run.startedAt, run.finishedAt, now)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function RunsPage() {
  const runs = usePolling(api.recentRuns, 2000, []);
  const workers = usePolling(api.workers, 5000, []);

  const list = runs.data ?? [];
  const running = list.filter((r) => r.status === "RUNNING").length;
  const queued = list.filter((r) => r.status === "QUEUED").length;
  const recent = list.slice(0, 20);
  const passRate = recent.filter((r) => r.status === "SUCCEEDED" || r.status === "FAILED");
  const passed = passRate.filter((r) => r.status === "SUCCEEDED").length;
  const alive = (workers.data ?? []).filter((w) => w.alive);

  return (
    <>
      <header className="page-header">
        <h1>Runs</h1>
        {runs.error && <span className="error-text">{runs.error}</span>}
      </header>
      <div className="stats">
        <div className="stat">
          <span className="stat-value">{running}</span>
          <span className="stat-label">running</span>
        </div>
        <div className="stat">
          <span className="stat-value">{queued}</span>
          <span className="stat-label">queued</span>
        </div>
        <div className="stat">
          <span className="stat-value">{passRate.length ? `${Math.round((passed / passRate.length) * 100)}%` : "—"}</span>
          <span className="stat-label">pass rate, last 20</span>
        </div>
        <div className="stat">
          <span className="stat-value">
            {alive.length}
            <span className="stat-sub"> / {alive.reduce((n, w) => n + w.concurrency, 0)} slots</span>
          </span>
          <span className="stat-label">workers online</span>
        </div>
      </div>
      {runs.data ? <RunsTable runs={list} showProject /> : <div className="loading">Loading…</div>}
    </>
  );
}
