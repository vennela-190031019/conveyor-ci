// Types mirror the Spring Boot DTOs in com.conveyorci.web.ApiModels.

export type RunStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export type JobStatus = "PENDING" | "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "SKIPPED";
export type StepStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "SKIPPED";
export type RunSource = "API" | "GITHUB_PUSH";

export interface Project {
  id: number;
  owner: string;
  name: string;
  defaultBranch: string;
  createdAt: string;
}

export interface RunSummary {
  id: number;
  projectId: number;
  project: string;
  runNumber: number;
  pipelineName: string;
  commitSha: string;
  branch: string;
  status: RunStatus;
  source: RunSource;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface Step {
  position: number;
  name: string;
  command: string;
  status: StepStatus;
  exitCode: number | null;
}

export interface Job {
  id: number;
  name: string;
  image: string;
  stage: number;
  status: JobStatus;
  needs: string[];
  attempt: number;
  maxAttempts: number;
  timeoutMinutes: number;
  workerId: string | null;
  failureReason: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  steps: Step[];
}

export interface Run {
  id: number;
  projectId: number;
  project: string;
  runNumber: number;
  pipelineName: string;
  commitSha: string;
  branch: string;
  status: RunStatus;
  source: RunSource;
  checkout: boolean;
  failureReason: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  stages: string[][];
  jobs: Job[];
}

export interface Worker {
  id: string;
  hostname: string;
  concurrency: number;
  startedAt: string;
  lastHeartbeatAt: string;
  alive: boolean;
}

export interface LogLine {
  seq: number;
  step: number;
  text: string;
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const problem = await response.json();
      message = problem.errors?.join("; ") ?? problem.detail ?? message;
    } catch {
      // not JSON
    }
    throw new ApiError(message, response.status);
  }
  const type = response.headers.get("Content-Type") ?? "";
  return (type.includes("json") ? response.json() : response.text()) as Promise<T>;
}

export const api = {
  recentRuns: () => request<RunSummary[]>("GET", "/api/runs"),
  projectRuns: (projectId: number) => request<RunSummary[]>("GET", `/api/projects/${projectId}/runs`),
  run: (id: number) => request<Run>("GET", `/api/runs/${id}`),
  cancel: (id: number) => request<Run>("POST", `/api/runs/${id}/cancel`),
  rerun: (id: number) => request<Run>("POST", `/api/runs/${id}/rerun`),
  jobLogs: (jobId: number) => request<string>("GET", `/api/jobs/${jobId}/logs`),
  projects: () => request<Project[]>("GET", "/api/projects"),
  project: (id: number) => request<Project>("GET", `/api/projects/${id}`),
  createProject: (owner: string, name: string) => request<Project>("POST", "/api/projects", { owner, name }),
  workers: () => request<Worker[]>("GET", "/api/workers"),
  logStreamUrl: (jobId: number) => `/api/jobs/${jobId}/logs/stream`,
};

export const TERMINAL_JOB: ReadonlySet<JobStatus> = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "SKIPPED"]);
export const ACTIVE_RUN: ReadonlySet<RunStatus> = new Set(["QUEUED", "RUNNING"]);
