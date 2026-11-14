/** SpecHub backend REST 客户端（对齐 modules/server Records.kt / ApiQueryController） */

export interface Project {
  id: number;
  name: string;
}

export interface Repo {
  id: number;
  projectId: number;
  url: string;
  branch: string;
  syncStatus: string;
  lastError?: string | null;
}

export interface Api {
  id: number;
  repoId: number;
  versionId?: number | null;
  method: string;
  path: string;
  operationId?: string | null;
  tag?: string | null;
  summary?: string | null;
  specJson: string;
}

export interface WriteSpecResp {
  repoId: number;
  path: string;
  commit: string;
  branch: string;
  apiCount: number;
}

async function j<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch("/api" + path, {
    headers: init?.body ? { "Content-Type": "application/json" } : undefined,
    ...init,
  });
  if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
  if (r.status === 204) return undefined as T;
  return r.json() as Promise<T>;
}

export const listProjects = () => j<Project[]>("/projects");
export const listRepos = () => j<Repo[]>("/repos");
export const listApis = (q?: string, method?: string) => {
  const p = new URLSearchParams();
  if (q) p.set("q", q);
  if (method) p.set("method", method);
  const s = p.toString();
  return j<Api[]>(`/apis${s ? `?${s}` : ""}`);
};
export const getApi = (id: number) => j<Api>(`/apis/${id}`);
export const writeSpec = (repoId: number, path: string, content: string) =>
  j<WriteSpecResp>(`/repos/${repoId}/spec`, { method: "PUT", body: JSON.stringify({ path, content }) });

/** repo.url → 展示名（git 路径尾段，去 .git） */
export const repoDisplay = (url: string) => url.replace(/\.git$/, "").split("/").pop() ?? url;