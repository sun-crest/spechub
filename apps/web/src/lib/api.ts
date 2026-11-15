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
}

export interface WriteSpecResp {
  repoId: number;
  path: string;
  commit: string;
  branch: string;
  apiCount: number;
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
export const listApis = (q?: string, method?: string) => {
  const p = new URLSearchParams();
  if (q) p.set("q", q);
  if (method) p.set("method", method);
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

/** repo.url → 展示名（git 路径尾段，去 .git） */
export const repoDisplay = (url: string) => url.replace(/\.git$/, "").split("/").pop() ?? url;