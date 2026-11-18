/** SpecHub backend REST 客户端（对齐 modules/server Records.kt / ApiQueryController） */

// ── H6 登录（admin token 占位 → I4 升级为 RBAC 用户登录，向后兼容）────────────
const TOKEN_KEY = "spechub.admin-token";

/** 从 localStorage 读取令牌（I4：用户 token，或 admin-token 兼容） */
export const getAdminToken = (): string | null => {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
};
/** 保存 / 清除令牌（登录成功 / 登出） */
export const setAdminToken = (t: string | null) => {
  try {
    if (t) localStorage.setItem(TOKEN_KEY, t);
    else localStorage.removeItem(TOKEN_KEY);
  } catch { /* 隐私模式忽略 */ }
};

export interface AuthStatus {
  authEnabled: boolean;
  authenticated: boolean;
  username?: string | null;
}

export interface Me {
  authenticated: boolean;
  authEnabled: boolean;
  username?: string | null;
  displayName?: string | null;
  platformAdmin?: boolean;
  /** 组织管理员（含平台 admin） */
  orgAdmin?: boolean;
  /** 当前组织 */
  currentOrgId?: number | null;
  currentOrgSlug?: string | null;
  /** 当前用户的组织列表（阶段 A 一个默认组织） */
  orgs?: { id: number; name: string; slug: string; role: string }[];
  /** 兼容字段：阶段 A 起不再返回 default 项目角色，前端用 projectRoles */
  role?: string | null;
  /** projectId → 当前用户角色 */
  projectRoles?: Record<string, string>;
  canManage?: boolean;
}

export interface UserView {
  id: number;
  username: string;
  displayName: string;
  platformAdmin: boolean;
  role?: string | null;
  orgRole?: string | null;
  createdAt?: string | null;
}

export interface ProjectMember {
  userId: number;
  username: string;
  displayName: string;
  role: string;
  orgRole?: string | null;
}

export interface CreateUserResp {
  user: UserView;
  token: string;
}

/** 写权限角色集合（editor / admin / owner） */
export const WRITE_ROLES = ["editor", "admin", "owner"];
/** 项目成员可设置角色（owner 只能通过转移接口） */
export const PROJECT_ROLES = ["admin", "editor", "viewer"];

export interface Project {
  id: number;
  name: string;
  slug?: string | null;
  description?: string;
  visibility?: string;
  ownerId?: number | null;
  archived?: boolean;
  createdAt?: string | null;
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
  /** I8 评审流：启鉴权下写回返回非空 reviewId */
  reviewId?: number | null;
  status?: string | null;
  message?: string;
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
  if (!r.ok) {
    // 统一错误体 { status, code, message }：优先展示后端 message
    let msg = `${r.status} ${r.statusText}`;
    try {
      const body = (await r.json()) as { message?: string };
      if (body?.message) msg = body.message;
    } catch { /* 非 JSON 错误体 */ }
    throw new Error(msg);
  }
  if (r.status === 204) return undefined as T;
  return r.json() as Promise<T>;
}

// ── H6 鉴权端点（/api/auth 已被 TokenAuthFilter 放行）────────────────────────
export const getAuthStatus = () => j<AuthStatus>("/auth/status");
/** I4 RBAC 登录：username + token；username 留空向后兼容 admin-token */
export const login = (token: string, username?: string) =>
  j<AuthStatus>("/auth/login", { method: "POST", body: JSON.stringify({ username, token }) });
/** 当前用户 + 对默认项目角色（前端据此定编辑入口可见性） */
export const getMe = () => j<Me>("/auth/me");
/** 组织用户目录（org_admin） */
export const listUsers = () => j<UserView[]>("/auth/users");
export const createUser = (u: { username: string; displayName?: string; orgRole?: string }) =>
  j<CreateUserResp>("/auth/users", { method: "POST", body: JSON.stringify(u) });
export const setUserRole = (id: number, role: string) =>
  j<UserView>(`/auth/users/${id}/role`, { method: "POST", body: JSON.stringify({ role }) });
export const resetUserToken = (id: number) => j<CreateUserResp>(`/auth/users/${id}/token`, { method: "POST" });
export const deleteUser = (id: number) => j<unknown>(`/auth/users/${id}`, { method: "DELETE" });

// ── 项目成员（阶段 A）────────────────────────────────────────────────────
export const listProjectMembers = (projectId: number) => j<ProjectMember[]>(`/projects/${projectId}/members`);
export const addProjectMember = (projectId: number, m: { username: string; role: string }) =>
  j<ProjectMember>(`/projects/${projectId}/members`, { method: "POST", body: JSON.stringify(m) });
export const updateProjectMemberRole = (projectId: number, userId: number, role: string) =>
  j<ProjectMember>(`/projects/${projectId}/members/${userId}`, { method: "PATCH", body: JSON.stringify({ role }) });
export const removeProjectMember = (projectId: number, userId: number) =>
  j<unknown>(`/projects/${projectId}/members/${userId}`, { method: "DELETE" });

export const listProjects = () => j<Project[]>("/projects");
/** 仓库列表；传 projectId 时只取该项目（A8 前端按项目加载） */
export const listRepos = (projectId?: number) =>
  j<Repo[]>(`/repos${projectId != null ? `?projectId=${projectId}` : ""}`);

// ── 阶段 A：项目 CRUD / 归档 / owner 转移 ─────────────────────────────────
export const getProject = (id: number) => j<Project>(`/projects/${id}`);

export const createProject = (p: {
  name: string;
  slug?: string;
  description?: string;
  visibility?: string;
  ownerId?: number | null;
}) => j<Project>("/projects", { method: "POST", body: JSON.stringify(p) });

export const updateProject = (
  id: number,
  patch: { name?: string; slug?: string; description?: string; visibility?: string; archived?: boolean },
) => j<Project>(`/projects/${id}`, { method: "PATCH", body: JSON.stringify(patch) });

export const archiveProject = (id: number) =>
  j<Project>(`/projects/${id}/archive`, { method: "POST" });

export const transferProjectOwner = (id: number, ownerId: number) =>
  j<Project>(`/projects/${id}/transfer`, { method: "POST", body: JSON.stringify({ ownerId }) });

export const deleteProject = (id: number) =>
  j<unknown>(`/projects/${id}`, { method: "DELETE" });

// ── 阶段 A：仓库注册（必须归属项目）与真源 binding ─────────────────────────
export interface RegisterRepoResp {
  repo: Repo;
  bindingId?: number | null;
  apiCount: number;
  schemaCount: number;
  versionCount: number;
  messages: string[];
}

export const registerRepo = (r: {
  projectId: number;
  url: string;
  branch?: string;
  token?: string;
  kind?: "managed" | "external";
  connectionId?: number | null;
  externalRepoId?: string;
}) => j<RegisterRepoResp>("/repos", { method: "POST", body: JSON.stringify(r) });

export interface RepoBinding {
  id: number;
  repoId: number;
  kind: string;
  connectionId?: number | null;
  externalRepoId?: string | null;
  remoteUrl: string;
  branch: string;
  status: string;
  createdAt: string;
  retiredAt?: string | null;
  /** retired 绑定的可删除时间（回滚窗口结束） */
  deleteAfter?: string | null;
}

export interface BindingVerifyResult {
  status: "identical" | "diverged" | "no_active" | "error";
  candidateId: number;
  activeId?: number | null;
  added: string[];
  removed: string[];
  changed: string[];
  message?: string | null;
}

export interface ActivateBindingResp {
  binding: RepoBinding;
  verify?: BindingVerifyResult | null;
  ingest?: { repoId: number; apiCount: number; schemaCount: number; versionCount: number; messages: string[] } | null;
  ingestError?: string | null;
}

export const listRepoBindings = (repoId: number) => j<RepoBinding[]>(`/repos/${repoId}/bindings`);
export const createRepoBinding = (
  repoId: number,
  b: { remoteUrl: string; kind?: "managed" | "external"; branch?: string; connectionId?: number | null; externalRepoId?: string },
) => j<RepoBinding>(`/repos/${repoId}/bindings`, { method: "POST", body: JSON.stringify(b) });

export const verifyRepoBinding = (repoId: number, bindingId: number) =>
  j<BindingVerifyResult>(`/repos/${repoId}/bindings/${bindingId}/verify`, { method: "POST" });

export const activateRepoBinding = (repoId: number, bindingId: number, confirm = false) =>
  j<ActivateBindingResp>(`/repos/${repoId}/bindings/${bindingId}/activate`, {
    method: "POST",
    body: JSON.stringify({ confirm }),
  });

export const retireRepoBinding = (repoId: number, bindingId: number) =>
  j<RepoBinding>(`/repos/${repoId}/bindings/${bindingId}/retire`, { method: "POST" });

export const deleteRepoBinding = (repoId: number, bindingId: number) =>
  j<unknown>(`/repos/${repoId}/bindings/${bindingId}`, { method: "DELETE" });

// ── K AI 文档层（Doc 域，两域隔离 §5.7）──────────────────────────────────
export interface Doc {
  id: number;
  repoId: number;
  versionId?: number | null;
  path: string;
  title: string;
  markdown: string;
  createdAt?: string | null;
}

export interface DocComment {
  id: number;
  docId: number | null;
  author: string;
  body: string;
  createdAt?: string | null;
}

export interface DocQuery {
  repoId?: number;
  projectId?: number;
}

const docQuery = (opts?: DocQuery): string => {
  const p = new URLSearchParams();
  if (opts?.repoId) p.set("repoId", String(opts.repoId));
  if (opts?.projectId) p.set("projectId", String(opts.projectId));
  const s = p.toString();
  return s ? `?${s}` : "";
};

export const listDocs = (opts?: DocQuery) => j<Doc[]>(`/docs${docQuery(opts)}`);
export const searchDocs = (q: string, opts?: DocQuery) => {
  const p = new URLSearchParams();
  if (q) p.set("q", q);
  if (opts?.repoId) p.set("repoId", String(opts.repoId));
  if (opts?.projectId) p.set("projectId", String(opts.projectId));
  const s = p.toString();
  return j<Doc[]>(`/docs${s ? `?${s}` : ""}`);
};
export const getDoc = (id: number) => j<Doc>(`/docs/${id}`);
export const listDocComments = (id: number) => j<DocComment[]>(`/docs/${id}/comments`);
export const addDocComment = (id: number, body: string) =>
  j<DocComment>(`/docs/${id}/comments`, { method: "POST", body: JSON.stringify({ body }) });
export const listApis = (q?: string, method?: string, stage = "draft", projectId?: number) => {
  const p = new URLSearchParams();
  if (q) p.set("q", q);
  if (method) p.set("method", method);
  if (stage) p.set("stage", stage);
  if (projectId != null) p.set("projectId", String(projectId));
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
/** 全局变更记录流（跨 repo，I5 独立变更页） */
export const listGlobalChangelog = (limit = 50) => j<ChangelogEntry[]>(`/changelog?limit=${limit}`);

// ── I5 评论 ──────────────────────────────────────────────────────────────
export interface Comment {
  id: number;
  apiId: number | null;
  repoId: number;
  method: string;
  path: string;
  author: string;
  body: string;
  createdAt: string | null;
}

export const listComments = (apiId: number) => j<Comment[]>(`/apis/${apiId}/comments`);
export const addComment = (apiId: number, body: string) =>
  j<Comment>(`/apis/${apiId}/comments`, { method: "POST", body: JSON.stringify({ body }) });
export const deleteComment = (id: number) => j<unknown>(`/comments/${id}`, { method: "DELETE" });

// ── I7 订阅与变更通知 ─────────────────────────────────────────────────────
export interface Subscription {
  id: number;
  repoId: number;
  apiId?: number | null;
  method: string;
  path: string;
  team: string;
  notifyUrl?: string;
  createdAt?: string | null;
}

export interface Notification {
  id: number;
  team: string;
  repoId: number;
  method: string;
  path: string;
  summary: string;
  createdAt?: string | null;
}

export const listSubscriptions = (repoId?: number) =>
  j<Subscription[]>(`/subscriptions${repoId ? `?repoId=${repoId}` : ""}`);
export const subscribe = (repoId: number, method: string, path: string, team: string, notifyUrl = "") =>
  j<Subscription>(`/subscriptions`, { method: "POST", body: JSON.stringify({ repoId, method, path, team, notifyUrl }) });
export const unsubscribe = (id: number) => j<unknown>(`/subscriptions/${id}`, { method: "DELETE" });
export const listNotifications = (team?: string, limit = 20) => {
  const p = new URLSearchParams();
  if (team) p.set("team", team);
  p.set("limit", String(limit));
  return j<Notification[]>(`/subscriptions/notifications?${p.toString()}`);
};

// ── I8 评审审批流（写操作皆审）──────────────────────────────────────────────
export interface Review {
  id: number;
  repoId: number;
  path: string;
  status: string;
  requester: string;
  reviewer?: string | null;
  rejectReason?: string | null;
  createdAt?: string | null;
  resolvedAt?: string | null;
}

export interface ReviewDetail extends Review {
  newContent: string;
  currentContent?: string | null;
  oldHash?: string | null;
}

export const listReviews = (status?: string, repoId?: number) => {
  const p = new URLSearchParams();
  if (status) p.set("status", status);
  if (repoId) p.set("repoId", String(repoId));
  const s = p.toString();
  return j<Review[]>(`/reviews${s ? `?${s}` : ""}`);
};
export const getReview = (id: number) => j<ReviewDetail>(`/reviews/${id}`);
export const approveReview = (id: number) => j<Review>(`/reviews/${id}/approve`, { method: "POST" });
export const rejectReview = (id: number, reason?: string) =>
  j<unknown>(`/reviews/${id}/reject`, { method: "POST", body: JSON.stringify({ reason }) });

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