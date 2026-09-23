import { useEffect, useState } from "react";

// Hash-based routing: the backend only has to serve index.html at "/", no fallback rules needed.
export type Route =
  | { page: "runs" }
  | { page: "run"; id: number; job?: number }
  | { page: "projects" }
  | { page: "project"; id: number }
  | { page: "workers" }
  | { page: "not-found" };

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, "") || "/";
  const [pathname, query = ""] = path.split("?");
  const params = new URLSearchParams(query);
  const parts = pathname.split("/").filter(Boolean);

  if (parts.length === 0) return { page: "runs" };
  if (parts[0] === "runs" && parts.length === 2 && /^\d+$/.test(parts[1])) {
    const job = params.get("job");
    return { page: "run", id: Number(parts[1]), job: job ? Number(job) : undefined };
  }
  if (parts[0] === "projects" && parts.length === 1) return { page: "projects" };
  if (parts[0] === "projects" && parts.length === 2 && /^\d+$/.test(parts[1])) {
    return { page: "project", id: Number(parts[1]) };
  }
  if (parts[0] === "workers" && parts.length === 1) return { page: "workers" };
  return { page: "not-found" };
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}

export function navigate(to: string) {
  window.location.hash = to;
}
