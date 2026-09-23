import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { api, TERMINAL_JOB, type Job, type LogLine } from "../api";

interface Section {
  step: number;
  title: string;
  lines: string[];
}

function sectionTitle(job: Job, step: number): string {
  if (step === 0) return "Check out code";
  if (step === -1) return "Conveyor";
  const s = job.steps.find((st) => st.position === step);
  return s ? `Step ${step}: ${s.name}` : `Step ${step}`;
}

/** Groups consecutive lines by step into collapsible-looking sections. */
function toSections(job: Job, lines: LogLine[]): Section[] {
  const sections: Section[] = [];
  for (const line of lines) {
    const last = sections[sections.length - 1];
    if (last && last.step === line.step) {
      last.lines.push(line.text);
    } else {
      sections.push({ step: line.step, title: sectionTitle(job, line.step), lines: [line.text] });
    }
  }
  return sections;
}

/** Parses the stored plain-text log ("==> Step 1: name [STATUS, exit 0]" headers) into sections. */
function parseStored(text: string): Section[] {
  const sections: Section[] = [];
  for (const raw of text.split("\n")) {
    const header = raw.match(/^==> Step (\d+): (.*)$/);
    if (header) {
      sections.push({ step: Number(header[1]), title: `Step ${header[1]}: ${header[2]}`, lines: [] });
    } else if (sections.length > 0) {
      sections[sections.length - 1].lines.push(raw);
    }
  }
  sections.forEach((s) => {
    while (s.lines.length > 0 && s.lines[s.lines.length - 1] === "") s.lines.pop();
  });
  return sections;
}

type Mode = "live" | "stored";

/**
 * Shows a job's output. While the job is active it streams over Server-Sent Events (snapshot of
 * earlier lines, then each new line as it's printed); once finished it shows the stored log.
 */
export function LogViewer({ job }: { job: Job }) {
  const terminal = TERMINAL_JOB.has(job.status);
  const [mode, setMode] = useState<Mode>(terminal ? "stored" : "live");
  const [lines, setLines] = useState<LogLine[]>([]);
  const [attempt, setAttempt] = useState<number | null>(null);
  const [stored, setStored] = useState<Section[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const stickToBottom = useRef(true);

  // Reset when switching jobs.
  useEffect(() => {
    setMode(TERMINAL_JOB.has(job.status) ? "stored" : "live");
    setLines([]);
    setAttempt(null);
    setStored(null);
    setError(null);
    stickToBottom.current = true;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job.id]);

  // Live stream.
  useEffect(() => {
    if (mode !== "live") return;
    const source = new EventSource(api.logStreamUrl(job.id));
    source.addEventListener("lines", (e) => {
      const payload = JSON.parse((e as MessageEvent).data) as { attempt: number; lines: LogLine[] };
      setAttempt(payload.attempt);
      setLines((prev) => [...prev, ...payload.lines]);
    });
    source.addEventListener("reset", (e) => {
      const payload = JSON.parse((e as MessageEvent).data) as { attempt: number };
      setAttempt(payload.attempt);
      setLines([]);
    });
    source.addEventListener("end", () => {
      source.close();
      setMode("stored");
    });
    source.onerror = () => {
      // The server closes the stream when the job finishes; switch to the stored log.
      if (source.readyState === EventSource.CLOSED) setMode("stored");
    };
    return () => source.close();
  }, [mode, job.id]);

  // The job may finish (or be cancelled) without an "end" event reaching us.
  useEffect(() => {
    if (terminal && mode === "live") {
      const timer = window.setTimeout(() => setMode("stored"), 1500);
      return () => window.clearTimeout(timer);
    }
  }, [terminal, mode]);

  // Stored log, re-fetched when the job's status changes.
  useEffect(() => {
    if (mode !== "stored") return;
    let cancelled = false;
    api
      .jobLogs(job.id)
      .then((text) => !cancelled && setStored(parseStored(text)))
      .catch((e) => !cancelled && setError(String(e.message ?? e)));
    return () => {
      cancelled = true;
    };
  }, [mode, job.id, job.status]);

  const sections = mode === "live" ? toSections(job, lines) : stored;

  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && stickToBottom.current) el.scrollTop = el.scrollHeight;
  }, [sections]);

  const onScroll = () => {
    const el = scroller.current;
    if (el) stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
  };

  return (
    <div className="log">
      <div className="log-toolbar">
        {mode === "live" ? (
          <span className="live-pill">
            <span className="live-dot" /> Live{attempt && attempt > 1 ? ` · attempt ${attempt}` : ""}
          </span>
        ) : (
          <span className="muted">Saved output</span>
        )}
        {job.status === "PENDING" && <span className="muted">Waiting for dependencies…</span>}
        {job.status === "QUEUED" && <span className="muted">Waiting for a worker…</span>}
      </div>
      <div className="log-body" ref={scroller} onScroll={onScroll}>
        {error && <div className="log-error">{error}</div>}
        {sections && sections.length === 0 && !error && (
          <div className="log-empty">{terminal ? "No output." : "No output yet."}</div>
        )}
        {sections?.map((section, i) => (
          <section key={i} className={`log-section ${section.step === -1 ? "log-system" : ""}`}>
            <div className="log-section-title">{section.title}</div>
            <pre>
              {section.lines.map((line, n) => (
                <div key={n} className={line.startsWith("$ ") ? "log-cmd" : undefined}>
                  <span className="ln">{n + 1}</span>
                  {line || " "}
                </div>
              ))}
            </pre>
          </section>
        ))}
      </div>
    </div>
  );
}
