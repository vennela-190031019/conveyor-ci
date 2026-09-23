import { useState } from "react";
import { ACTIVE_RUN, api, type Job, type Run } from "../api";
import { DagGraph } from "../components/DagGraph";
import { LogViewer } from "../components/LogViewer";
import { StatusBadge } from "../components/Status";
import { navigate } from "../router";
import { commitUrl, duration, relativeTime, shortSha, useNow, usePolling } from "../util";

/** Picks the most interesting job to show first: failed, then running, then the first one. */
function defaultJob(run: Run): Job | undefined {
  return (
    run.jobs.find((j) => j.status === "FAILED") ??
    run.jobs.find((j) => j.status === "RUNNING") ??
    run.jobs[0]
  );
}

export function RunPage({ id, jobParam }: { id: number; jobParam?: number }) {
  const { data: run, error, refresh } = usePolling(() => api.run(id), 1500, [id]);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const now = useNow();

  if (!run) {
    return error ? <div className="error-box">Could not load run: {error}</div> : <div className="loading">Loading…</div>;
  }

  const selected = run.jobs.find((j) => j.id === jobParam) ?? defaultJob(run);
  const active = ACTIVE_RUN.has(run.status);
  const url = commitUrl(run.project, run.commitSha);

  const act = async (action: () => Promise<Run>, goToNew: boolean) => {
    setBusy(true);
    setActionError(null);
    try {
      const result = await action();
      if (goToNew) navigate(`/runs/${result.id}`);
      else await refresh();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <nav className="crumbs">
        <a href={`#/projects/${run.projectId}`}>{run.project}</a>
        <span>/</span>
        <span>run #{run.runNumber}</span>
      </nav>
      <header className="page-header run-header">
        <div>
          <h1>
            {run.pipelineName} <span className="muted">#{run.runNumber}</span>
          </h1>
          <div className="meta">
            <StatusBadge status={run.status} />
            <span className="branch">{run.branch}</span>
            {url ? (
              <a className="sha" href={url} target="_blank" rel="noreferrer">
                {shortSha(run.commitSha)}
              </a>
            ) : (
              <span className="sha">{shortSha(run.commitSha)}</span>
            )}
            <span className="muted">{run.source === "GITHUB_PUSH" ? "triggered by push" : "triggered via API"}</span>
            <span className="muted">started {relativeTime(run.startedAt ?? run.createdAt, now)}</span>
            <span className="muted mono">{duration(run.startedAt, run.finishedAt, now)}</span>
          </div>
        </div>
        <div className="actions">
          {active && (
            <button className="btn btn-danger" disabled={busy} onClick={() => act(() => api.cancel(run.id), false)}>
              Cancel
            </button>
          )}
          <button className="btn" disabled={busy || run.jobs.length === 0} onClick={() => act(() => api.rerun(run.id), true)}>
            Re-run
          </button>
        </div>
      </header>

      {actionError && <div className="error-box">{actionError}</div>}
      {run.failureReason && <div className="error-box">{run.failureReason}</div>}

      {run.jobs.length > 0 && (
        <div className="card">
          <DagGraph
            jobs={run.jobs}
            stages={run.stages}
            selectedJobId={selected?.id ?? null}
            onSelect={(job) => navigate(`/runs/${run.id}?job=${job.id}`)}
            now={now}
          />
        </div>
      )}

      {selected && (
        <div className="card job-panel">
          <div className="job-header">
            <div>
              <h2>{selected.name}</h2>
              <div className="meta">
                <StatusBadge status={selected.status} />
                <span className="mono muted">{selected.image}</span>
                {selected.attempt > 0 && (
                  <span className="muted">
                    attempt {selected.attempt}/{selected.maxAttempts}
                  </span>
                )}
                {selected.workerId && <span className="muted">on {selected.workerId}</span>}
                <span className="muted mono">{duration(selected.startedAt, selected.finishedAt, now)}</span>
              </div>
            </div>
          </div>
          {selected.failureReason && selected.status !== "SUCCEEDED" && (
            <div className="error-box compact">{selected.failureReason}</div>
          )}
          <LogViewer job={selected} />
        </div>
      )}
    </>
  );
}
