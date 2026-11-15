/** SpecHub backend REST 客户端（对齐 modules/server Records.kt / ApiQueryController） */

// ── H6 登录（admin token 占位）────────────────────────────────────────────
const TOKEN_KEY = "spechub.admin-token";

/** 从 localStorage 读取 admin token（可被登录对话框写入） */
export const getAdminToken = (): string | null => {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
};
/** 保存 / 清除 admin token（登录成功 / 登出） */
export const setAdminToken = (t: string | null) => {
  try {
    if (t) localStorage.setItem(TOKEN_KEY, t);
    else localStorage.removeItem(TOKEN_KEY);
  } catch { /* 隐私模式忽略 */ }
};

export interface AuthStatus {
  authEnabled: boolean;
  authenticated: boolean;
}

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
  /** 来源 spec 文件路径（仅 GET /apis/{id} 填充），H4「编辑此文件」直达用 */
  specPath?: string | null;
  /** 投影档位（I6）：draft=最新摄取、release=发布快照 */
  stage?: string;
}

export interface WriteSpecResp {
  repoId: number;
  path: string;
  commit: string;
  branch: string;
  apiCount: number;
}

// ── I6 双层版本 / 变更记录 / Mock / Try it out ─────────────────────────────

export interface VersionRecord {
  id: number;
  repoId: number;
  path: string;
  specHash: string;
  stage: string;
}

export interface PromoteResp {
  repoId: number;
  path: string;
  versionId: number;
  specHash: string;
  stage: string;
  message: string;
}

export interface ChangelogEntry {
  id: number;
  repoId: number | null;
  apiId: number | null;
  actor: string;
  action: string;
  detail: string;
  createdAt: string | null;
}

export interface MockRule {
  id: number | null;
  repoId: number;
  apiId: number | null;
  fieldPattern: string;
  ruleType: string;
  ruleValue: string;
}

export interface MockPreview {
  method: string;
  requestPath: string;
  stage: string;
  status: number;
  mediaType: string;
  body: string;
  mockUrl: string;
}

export interface TryRequest {
  baseUrl: string;
  pathParams: Record<string, string>;
  queryParams: Record<string, string>;
  headers: Record<string, string>;
  body: string | null;
}

export interface TryResponse {
  status: number;
  headers: Record<string, string[]>;
  body: string;
  truncated: boolean;
  durationMs: number;
}

async function j<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = init?.body ? { "Content-Type": "application/json" } : {};
  const token = getAdminToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const r = await fetch("/api" + path, { headers, ...init });
  if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
  if (r.status === 204) return undefined as T;
  return r.json() as Promise<T>;
}

// ── H6 鉴权端点（/api/auth 已被 TokenAuthFilter 放行）────────────────────────
export const getAuthStatus = () => j<AuthStatus>("/auth/status");
export const login = (token: string) => j<AuthStatus>("/auth/login", { method: "POST", body: JSON.stringify({ token }) });

export const listProjects = () => j<Project[]>("/projects");
export const listRepos = () => j<Repo[]>("/repos");
export const listApis = (q?: string, method?: string, stage = "draft") => {
  const p = new URLSearchParams();
  if (q) p.set("q", q);
  if (method) p.set("method", method);
  if (stage) p.set("stage", stage);
  const s = p.toString();
  return j<Api[]>(`/apis${s ? `?${s}` : ""}`);
};
export const getApi = (id: number) => j<Api>(`/apis/${id}`);
/** 仓内全部 spec 文件相对路径 */
export const listSpecs = (repoId: number) => j<string[]>(`/repos/${repoId}/specs`);
/** 读取单个 spec 文件内容 */
export const readSpec = (repoId: number, path: string) => j<string>(`/repos/${repoId}/spec?path=${encodeURIComponent(path)}`);
export const writeSpec = (repoId: number, path: string, content: string) =>
  j<WriteSpecResp>(`/repos/${repoId}/spec`, { method: "PUT", body: JSON.stringify({ path, content }) });

// ── I6 双层版本 ───────────────────────────────────────────────────────────
/** 发布某 spec 文件的最新 draft → release（POST /repos/{id}/promote） */
export const promoteSpec = (repoId: number, path: string) =>
  j<PromoteResp>(`/repos/${repoId}/promote`, { method: "POST", body: JSON.stringify({ path }) });
export const listVersions = (repoId: number, path?: string) =>
  j<VersionRecord[]>(`/repos/${repoId}/versions${path ? `?path=${encodeURIComponent(path)}` : ""}`);
export const listChangelog = (repoId: number) => j<ChangelogEntry[]>(`/repos/${repoId}/changelog`);
export const listApiChangelog = (apiId: number) => j<ChangelogEntry[]>(`/apis/${apiId}/changelog`);

// ── I2/I3 Mock ─────────────────────────────────────────────────────────────
export const listMockRules = (repoId: number) => j<MockRule[]>(`/repos/${repoId}/mock/rules`);
export const addMockRule = (repoId: number, rule: Omit<MockRule, "repoId" | "id">) =>
  j<MockRule>(`/repos/${repoId}/mock/rules`, { method: "POST", body: JSON.stringify(rule) });
export const deleteMockRule = (id: number) => j<unknown>(`/mock/rules/${id}`, { method: "DELETE" });
export const mockPreview = (apiId: number) => j<MockPreview>(`/apis/${apiId}/mock/preview`);

// ── I1 Try it out ───────────────────────────────────────────────────────────
export const tryApi = (apiId: number, req: TryRequest) =>
  j<TryResponse>(`/apis/${apiId}/try`, { method: "POST", body: JSON.stringify(req) });

/** repo.url → 展示名（git 路径尾段，去 .git） */
export const repoDisplay = (url: string) => url.replace(/\.git$/, "").split("/").pop() ?? url;