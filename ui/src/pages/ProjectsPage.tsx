import { useState, type FormEvent } from "react";
import { api } from "../api";
import { usePolling } from "../util";
import { RunsTable } from "./RunsPage";

export function ProjectsPage() {
  const { data, error, refresh } = usePolling(api.projects, 5000, []);
  const [owner, setOwner] = useState("");
  const [name, setName] = useState("");
  const [formError, setFormError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);
    try {
      await api.createProject(owner.trim(), name.trim());
      setOwner("");
      setName("");
      await refresh();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : String(err));
    }
  };

  return (
    <>
      <header className="page-header">
        <h1>Projects</h1>
        {error && <span className="error-text">{error}</span>}
      </header>
      <div className="card">
        <form className="inline-form" onSubmit={submit}>
          <span className="muted">Register a GitHub repository</span>
          <input placeholder="owner" value={owner} onChange={(e) => setOwner(e.target.value)} required />
          <span className="muted">/</span>
          <input placeholder="repository" value={name} onChange={(e) => setName(e.target.value)} required />
          <button className="btn btn-primary" type="submit">
            Add
          </button>
        </form>
        {formError && <div className="error-text">{formError}</div>}
      </div>
      <div className="project-list">
        {data?.map((p) => (
          <a key={p.id} className="card project" href={`#/projects/${p.id}`}>
            <span className="project-name">
              {p.owner}/<strong>{p.name}</strong>
            </span>
            <span className="muted">default branch {p.defaultBranch}</span>
          </a>
        ))}
        {data?.length === 0 && <div className="empty">No projects yet.</div>}
      </div>
    </>
  );
}

export function ProjectPage({ id }: { id: number }) {
  const project = usePolling(() => api.project(id), 30000, [id]);
  const runs = usePolling(() => api.projectRuns(id), 2000, [id]);
  const p = project.data;
  return (
    <>
      <nav className="crumbs">
        <a href="#/projects">Projects</a>
        <span>/</span>
        <span>{p ? `${p.owner}/${p.name}` : "…"}</span>
      </nav>
      <header className="page-header">
        <h1>{p ? `${p.owner}/${p.name}` : "Project"}</h1>
        {p && (
          <a className="btn" href={`https://github.com/${p.owner}/${p.name}`} target="_blank" rel="noreferrer">
            View on GitHub
          </a>
        )}
      </header>
      {runs.error && <div className="error-box">{runs.error}</div>}
      {runs.data ? <RunsTable runs={runs.data} showProject={false} /> : <div className="loading">Loading…</div>}
    </>
  );
}
