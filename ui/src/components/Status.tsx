import type { JobStatus, RunStatus, StepStatus } from "../api";

type AnyStatus = RunStatus | JobStatus | StepStatus;

const LABELS: Record<AnyStatus, string> = {
  PENDING: "Pending",
  QUEUED: "Queued",
  RUNNING: "Running",
  SUCCEEDED: "Passed",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
  SKIPPED: "Skipped",
};

export function statusClass(status: AnyStatus): string {
  return `status-${status.toLowerCase()}`;
}

/** Small glyph per status: check, cross, spinner ring, hollow circle, dash. */
export function StatusIcon({ status, size = 16 }: { status: AnyStatus; size?: number }) {
  const common = { width: size, height: size, viewBox: "0 0 16 16", className: `status-icon ${statusClass(status)}` };
  switch (status) {
    case "SUCCEEDED":
      return (
        <svg {...common} aria-label="passed">
          <circle cx="8" cy="8" r="7" fill="currentColor" />
          <path d="M4.8 8.3l2.1 2.1 4.3-4.6" stroke="var(--bg)" strokeWidth="1.8" fill="none" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      );
    case "FAILED":
      return (
        <svg {...common} aria-label="failed">
          <circle cx="8" cy="8" r="7" fill="currentColor" />
          <path d="M5.5 5.5l5 5M10.5 5.5l-5 5" stroke="var(--bg)" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
      );
    case "RUNNING":
      return (
        <svg {...common} aria-label="running">
          <circle cx="8" cy="8" r="6" stroke="currentColor" strokeOpacity="0.25" strokeWidth="2.2" fill="none" />
          <path d="M8 2a6 6 0 0 1 6 6" stroke="currentColor" strokeWidth="2.2" fill="none" strokeLinecap="round" className="spin" />
        </svg>
      );
    case "CANCELLED":
    case "SKIPPED":
      return (
        <svg {...common} aria-label={status.toLowerCase()}>
          <circle cx="8" cy="8" r="6.2" stroke="currentColor" strokeWidth="1.6" fill="none" />
          <path d="M5.2 8h5.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
      );
    default:
      return (
        <svg {...common} aria-label={status.toLowerCase()}>
          <circle cx="8" cy="8" r="6.2" stroke="currentColor" strokeWidth="1.6" fill="none" strokeDasharray="3 2.2" />
        </svg>
      );
  }
}

export function StatusBadge({ status }: { status: AnyStatus }) {
  return (
    <span className={`badge ${statusClass(status)}`}>
      <StatusIcon status={status} size={14} />
      {LABELS[status]}
    </span>
  );
}
