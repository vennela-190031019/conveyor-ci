import { useCallback, useEffect, useRef, useState } from "react";

/** Fetches `load` now and every `intervalMs` while mounted. Keeps the last good data on errors. */
export function usePolling<T>(load: () => Promise<T>, intervalMs: number, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const loadRef = useRef(load);
  loadRef.current = load;

  const refresh = useCallback(async () => {
    try {
      setData(await loadRef.current());
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    setData(null);
    let cancelled = false;
    const tick = async () => {
      if (!cancelled) await refresh();
    };
    tick();
    const timer = window.setInterval(tick, intervalMs);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { data, error, refresh };
}

/** Re-renders every `ms` so relative times and running durations stay current. */
export function useNow(ms = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), ms);
    return () => window.clearInterval(timer);
  }, [ms]);
  return now;
}

export function relativeTime(iso: string | null, now: number): string {
  if (!iso) return "—";
  const seconds = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  if (seconds < 5) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

export function duration(start: string | null, end: string | null, now: number): string {
  if (!start) return "—";
  const ms = (end ? Date.parse(end) : now) - Date.parse(start);
  const total = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s.toString().padStart(2, "0")}s`;
  return `${s}s`;
}

export const shortSha = (sha: string) => sha.slice(0, 7);

export function commitUrl(project: string, sha: string): string | null {
  return sha.length === 40 ? `https://github.com/${project}/commit/${sha}` : null;
}
