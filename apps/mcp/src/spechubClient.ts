import { config } from "./config.js";

/** backend REST 薄客户端（对应 server 的 /api 端点；MCP/CLI 复用，见 spec-client 定位） */
async function call<T>(path: string, method: string, body?: unknown): Promise<T> {
  const res = await fetch(`${config.baseUrl}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(config.token ? { Authorization: `Bearer ${config.token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    const detail = (await res.text()).slice(0, 300);
    throw new Error(`${method} ${path} → HTTP ${res.status}: ${detail}`);
  }
  return (await res.json()) as T;
}

function qs(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") search.set(k, String(v));
  }
  const s = search.toString();
  return s ? `?${s}` : "";
}

export const spechub = {
  listProjects: () => call<Array<{ id: number; name: string }>>("/api/projects", "GET"),

  searchApis: (q?: string, method?: string) =>
    call<Array<{ id: number; repoId: number; method: string; path: string; operationId: string | null; tag: string | null; summary: string | null; specJson: string }>>(
      `/api/apis${qs({ q, method })}`,
      "GET",
    ),

  getApi: (id: number) =>
    call<{ id: number; method: string; path: string; operationId: string | null; tag: string | null; summary: string | null; specJson: string }>(
      `/api/apis/${id}`,
      "GET",
    ),

  /** 各真源仓摄取状态（doc_sync_status 数据源） */
  listRepos: () =>
    call<Array<{ id: number; url: string; branch: string; syncStatus: string; lastError: string | null }>>("/api/repos", "GET"),

  listSpecs: (repoId: number) => call<string[]>(`/api/repos/${repoId}/specs`, "GET"),

  writeSpec: (repoId: number, path: string, content: string) =>
    call<{ repoId: number; path: string; commit: string; branch: string; apiCount: number }>(
      `/api/repos/${repoId}/spec`,
      "PUT",
      { path, content },
    ),
};