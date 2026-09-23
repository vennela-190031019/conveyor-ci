import { ProjectPage, ProjectsPage } from "./pages/ProjectsPage";
import { RunPage } from "./pages/RunPage";
import { RunsPage } from "./pages/RunsPage";
import { WorkersPage } from "./pages/WorkersPage";
import { useRoute } from "./router";

function Logo() {
  return (
    <svg width="22" height="22" viewBox="0 0 32 32" aria-hidden>
      <rect width="32" height="32" rx="8" fill="var(--accent)" />
      <path d="M7 20h18M7 20l3-8h12l3 8" stroke="white" strokeWidth="2.5" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="11" cy="24" r="2" fill="white" />
      <circle cx="21" cy="24" r="2" fill="white" />
    </svg>
  );
}

export function App() {
  const route = useRoute();
  const section = route.page === "run" || route.page === "runs" ? "runs" : route.page === "project" ? "projects" : route.page;

  return (
    <div className="shell">
      <aside className="sidebar">
        <a className="brand" href="#/">
          <Logo />
          <span>Conveyor</span>
        </a>
        <nav>
          <a className={section === "runs" ? "active" : ""} href="#/">
            Runs
          </a>
          <a className={section === "projects" ? "active" : ""} href="#/projects">
            Projects
          </a>
          <a className={section === "workers" ? "active" : ""} href="#/workers">
            Workers
          </a>
        </nav>
        <div className="sidebar-foot muted">self-hosted CI/CD</div>
      </aside>
      <main className="content">
        {route.page === "runs" && <RunsPage />}
        {route.page === "run" && <RunPage key={route.id} id={route.id} jobParam={route.job} />}
        {route.page === "projects" && <ProjectsPage />}
        {route.page === "project" && <ProjectPage id={route.id} />}
        {route.page === "workers" && <WorkersPage />}
        {route.page === "not-found" && (
          <div className="empty">
            Page not found. <a href="#/">Back to runs</a>
          </div>
        )}
      </main>
    </div>
  );
}
