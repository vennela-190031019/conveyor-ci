import type { Job } from "../api";
import { duration } from "../util";
import { StatusIcon, statusClass } from "./Status";

const NODE_W = 196;
const NODE_H = 58;
const COL_GAP = 72;
const ROW_GAP = 16;
const PAD = 8;

interface Props {
  jobs: Job[];
  stages: string[][];
  selectedJobId: number | null;
  onSelect: (job: Job) => void;
  now: number;
}

/**
 * The run's dependency graph: one column per stage (jobs in a column can run in parallel),
 * with an edge from each job to the jobs that need it.
 */
export function DagGraph({ jobs, stages, selectedJobId, onSelect, now }: Props) {
  const byName = new Map(jobs.map((j) => [j.name, j]));
  const position = new Map<string, { x: number; y: number }>();
  stages.forEach((column, c) =>
    column.forEach((name, r) => position.set(name, { x: PAD + c * (NODE_W + COL_GAP), y: PAD + r * (NODE_H + ROW_GAP) })),
  );
  const width = PAD * 2 + stages.length * NODE_W + Math.max(0, stages.length - 1) * COL_GAP;
  const tallest = Math.max(1, ...stages.map((s) => s.length));
  const height = PAD * 2 + tallest * NODE_H + (tallest - 1) * ROW_GAP;

  const edges = jobs.flatMap((job) =>
    job.needs
      .filter((need) => position.has(need) && position.has(job.name))
      .map((need) => {
        const from = position.get(need)!;
        const to = position.get(job.name)!;
        const x1 = from.x + NODE_W;
        const y1 = from.y + NODE_H / 2;
        const x2 = to.x;
        const y2 = to.y + NODE_H / 2;
        const bend = (x2 - x1) / 2;
        const upstream = byName.get(need)!;
        return (
          <path
            key={`${need}->${job.name}`}
            d={`M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`}
            className={`edge ${upstream.status === "SUCCEEDED" ? "edge-done" : ""}`}
          />
        );
      }),
  );

  return (
    <div className="dag-scroll">
      <div className="dag" style={{ width, height }}>
        <svg width={width} height={height} className="dag-edges">
          {edges}
        </svg>
        {jobs.map((job) => {
          const p = position.get(job.name);
          if (!p) return null;
          const retrying = job.attempt > 1 || (job.status === "QUEUED" && job.attempt > 0);
          return (
            <button
              key={job.id}
              className={`dag-node ${statusClass(job.status)} ${selectedJobId === job.id ? "selected" : ""}`}
              style={{ left: p.x, top: p.y, width: NODE_W, height: NODE_H }}
              onClick={() => onSelect(job)}
              title={job.failureReason ?? job.name}
            >
              <StatusIcon status={job.status} />
              <span className="dag-node-text">
                <span className="dag-node-name">{job.name}</span>
                <span className="dag-node-meta">
                  {job.startedAt ? duration(job.startedAt, job.finishedAt, now) : job.status.toLowerCase()}
                  {retrying && ` · attempt ${job.attempt}/${job.maxAttempts}`}
                </span>
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
