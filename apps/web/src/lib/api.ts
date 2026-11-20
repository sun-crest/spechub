/** SpecHub backend REST 客户端（对齐 modules/server Records.kt / ApiQueryController） */

// ── 阶段 C：认证 / 会话 / CSRF（不再持久化长期令牌）─────────────────────────
/**
 * - 登录态由 Spring Session Cookie（HttpOnly `spechub_session`）承载；
 * - CSRF token 只保存在内存，非 GET/HEAD 请求带 `X-CSRF-Token`；
 * - Web 端不再写 localStorage：模块加载时清除历史遗留的 `spechub.admin-token`
 *   （升级后用户需用密码 / OIDC / 令牌重新登录）；
 * - 推荐用 Vite 代理保持同源（见 vite.config.ts）。若 `VITE_API_BASE` 跨域，
 *   `credentials` 必须改为 `include`，且后端开启 CORS credentials 与
 *   `SameSite=None; Secure` Cookie，否则登录态 / CSRF 会失效。
 */
const LEGACY_TOKEN_KEY = "spechub.admin-token";
try {
  localStorage.removeItem(LEGACY_TOKEN_KEY);
} catch {
  /* 隐私模式忽略 */
}

let csrfToken: string | null = null;
/** 登录 / `/auth/me` 返回的 CSRF token（仅内存，不持久化）。 */
export const setCsrfToken = (token: string | null) => {
  csrfToken = token;
};
export const getCsrfToken = () => csrfToken;

/** 401 时通知 App 清空当前用户并弹出登录框（登录请求自身除外）。 */
export const UNAUTHORIZED_EVENT = "spechub:unauthorized";

/** REST 错误：保留 HTTP 状态码 / 错误码，前端据此区分 401 / 429 等。 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export interface AuthStatus {
  authEnabled: boolean;
  authenticated: boolean;
  authMethod?: string | null;
  username?: string | null;
  oidcEnabled?: boolean;
  oidcLoginUrl?: string | null;
}

/** `POST /api/auth/login` 响应（Spring Security JSON filter）。 */
export interface LoginResult {
  authenticated: boolean;
  authEnabled: boolean;
  authMethod?: string | null;
  username?: string | null;
  displayName?: string | null;
  csrfToken?: string | null;
  mustChangePassword?: boolean;
}

export interface Me {
  authenticated: boolean;
  authEnabled: boolean;
  /** session / bearer（服务账号固定 bearer） */
  authMethod?: string | null;
  username?: string | null;
  displayName?: string | null;
  email?: string | null;
  status?: string | null;
  /** 当前会话 CSRF token（前端存内存，写请求回带） */
  csrfToken?: string | null;
  mustChangePassword?: boolean;
  hasPassword?: boolean;
  /** 组织管理员是唯一组织管理标识（阶段 C 移除 platformAdmin） */
  orgAdmin?: boolean;
  oidcEnabled?: boolean;
  oidcLoginUrl?: string | null;
  /** 当前组织（阶段 C 单组织） */
  currentOrgId?: number | null;
  currentOrgSlug?: string | null;
  /** 当前用户的组织列表 */
  orgs?: { id: number; name: string; slug: string; role: string }[];
  /** 兼容字段：阶段 A 起不再返回 default 项目角色，前端用 projectRoles */
  role?: string | null;
  /** projectId → 当前用户角色 */
  projectRoles?: Record<string, string>;
  /** 当前用户所在团队（B3） */
  teams?: { id: number; name: string; role: string }[];
  canManage?: boolean;
}

export interface UserView {
  id: number;
  username: string;
  displayName: string;
  email?: string | null;
  status?: string;
  mustChangePassword?: boolean;
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
  /** 邀请用户的一次性密码设置令牌（只返回一次） */
  setupToken?: string | null;
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

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

async function j<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  const headers = new Headers(init?.headers);
  if (init?.body) headers.set("Content-Type", "application/json");
  // 会话写请求带 CSRF（Bearer 链由后端豁免；Web 端不会再持久化 Bearer）
  if (!SAFE_METHODS.has(method) && csrfToken) headers.set("X-CSRF-Token", csrfToken);
  const r = await fetch("/api" + path, { ...init, method, headers, credentials: "same-origin" });
  if (!r.ok) {
    // 统一错误体 { status, code, message }：优先展示后端 message
    let msg = `${r.status} ${r.statusText}`;
    let code: string | undefined;
    try {
      const body = (await r.json()) as { message?: string; code?: string };
      if (body?.message) msg = body.message;
      code = body?.code;
    } catch { /* 非 JSON 错误体 */ }
    // 会话失效：清空 CSRF 并通知 App 弹登录框。
    // 排除登录本身与改密（当前密码错误后端返回 401，不应把用户踢下线）。
    if (r.status === 401 && path !== "/auth/login" && !path.startsWith("/auth/password")) {
      csrfToken = null;
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
    }
    throw new ApiError(r.status, msg, code);
  }
  if (r.status === 204) return undefined as T;
  return r.json() as Promise<T>;
}

// ── 阶段 C：认证 / 改密 / 会话 / 令牌 ───────────────────────────────────────
export const getAuthStatus = () => j<AuthStatus>("/auth/status");
export const getMe = () => j<Me>("/auth/me");

/** 密码登录：用户名或邮箱 + 密码（成功后 Spring Session 建立会话）。 */
export const loginWithPassword = async (identifier: string, password: string) => {
  const r = await j<LoginResult>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ username: identifier, password }),
  });
  setCsrfToken(r.csrfToken ?? null);
  return r;
};

/** 兼容令牌登录：admin-token / 用户 API 令牌；不再把令牌写入 localStorage。 */
export const loginWithToken = async (token: string, username?: string) => {
  const r = await j<LoginResult>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ username: username?.trim() || undefined, token }),
  });
  setCsrfToken(r.csrfToken ?? null);
  return r;
};

/** 退出登录（Spring Security logout：删除会话 + 审计）。 */
export const logout = () => j<void>("/auth/logout", { method: "POST" });

/** 修改自己的密码；成功后其它 Web 会话被吊销。 */
export const changePassword = (currentPassword: string | null, newPassword: string) =>
  j<void>("/auth/password", {
    method: "POST",
    body: JSON.stringify({ currentPassword, newPassword }),
  });

/** 邀请 / 重置令牌设置密码（无需登录）。 */
export const setupPassword = (token: string, newPassword: string) =>
  j<{ ok: boolean }>("/auth/password/setup", {
    method: "POST",
    body: JSON.stringify({ token, newPassword }),
  });

export interface SessionView {
  /** 脱敏会话 id（sha256 前 16 位），不暴露 Spring Session 主键 */
  id: string;
  ip?: string | null;
  userAgent?: string | null;
  createdAt?: string | null;
  lastAccessedAt?: string | null;
  current: boolean;
}

export const listSessions = () => j<SessionView[]>("/auth/sessions");
export const revokeSession = (id: string) =>
  j<void>(`/auth/sessions/${encodeURIComponent(id)}`, { method: "DELETE" });

export interface TokenView {
  id: number;
  name: string;
  prefix: string;
  purpose: string;
  expiresAt?: string | null;
  lastUsedAt?: string | null;
  createdAt?: string | null;
  status: string;
}

export interface CreateTokenResp {
  token: TokenView;
  secret: string;
}

export const listTokens = () => j<TokenView[]>("/auth/tokens");
export const createToken = (name: string, expiresInDays = 90) =>
  j<CreateTokenResp>("/auth/tokens", {
    method: "POST",
    body: JSON.stringify({ name, expiresInDays }),
  });
export const revokeToken = (id: number) => j<void>(`/auth/tokens/${id}`, { method: "DELETE" });

/** I4 RBAC 兼容端点：组织用户目录 / 创建 / 角色 / 令牌重置 / 删除（org_admin）。 */
export const listUsers = () => j<UserView[]>("/auth/users");
export const createUser = (u: { username: string; displayName?: string; email?: string; password?: string; orgRole?: string }) =>
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

// ── 阶段 B（B3）：团队与成员 ──────────────────────────────────────────────
export interface Team {
  id: number;
  name: string;
  description?: string;
  memberCount: number;
  subscriptionCount: number;
  /** 当前用户在该团队的角色（lead / member）；org_admin 可为 null 但 canManage=true */
  myRole?: string | null;
  canManage?: boolean;
  createdAt?: string | null;
}

export interface TeamMember {
  userId: number;
  username: string;
  displayName: string;
  role: string;
  orgRole?: string | null;
}

export const TEAM_ROLES = ["lead", "member"];

export const listTeams = () => j<Team[]>("/teams");
export const createTeam = (t: { name: string; description?: string }) =>
  j<Team>("/teams", { method: "POST", body: JSON.stringify(t) });
export const updateTeam = (id: number, patch: { name?: string; description?: string }) =>
  j<Team>(`/teams/${id}`, { method: "PATCH", body: JSON.stringify(patch) });
export const deleteTeam = (id: number) => j<unknown>(`/teams/${id}`, { method: "DELETE" });
export const listTeamMembers = (id: number) => j<TeamMember[]>(`/teams/${id}/members`);
export const addTeamMember = (id: number, m: { userId?: number; username?: string; role?: string }) =>
  j<TeamMember>(`/teams/${id}/members`, { method: "POST", body: JSON.stringify(m) });
export const updateTeamMemberRole = (id: number, userId: number, role: string) =>
  j<TeamMember>(`/teams/${id}/members/${userId}`, { method: "PATCH", body: JSON.stringify({ role }) });
export const removeTeamMember = (id: number, userId: number) =>
  j<unknown>(`/teams/${id}/members/${userId}`, { method: "DELETE" });

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

// ── I7 / B4 订阅与变更通知（teamId 契约）──────────────────────────────────
export interface Subscription {
  id: number;
  repoId: number;
  apiId?: number | null;
  method: string;
  path: string;
  teamId?: number | null;
  teamName: string;
  /** 兼容字段：与 teamName 同值 */
  team?: string;
  notifyUrl?: string;
  createdAt?: string | null;
}

export interface Notification {
  id: number;
  teamId?: number | null;
  teamName: string;
  /** 兼容字段：与 teamName 同值 */
  team?: string;
  repoId: number;
  method: string;
  path: string;
  summary: string;
  createdAt?: string | null;
}

export const listSubscriptions = (repoId?: number, teamId?: number) => {
  const p = new URLSearchParams();
  if (repoId) p.set("repoId", String(repoId));
  if (teamId) p.set("teamId", String(teamId));
  const s = p.toString();
  return j<Subscription[]>(`/subscriptions${s ? `?${s}` : ""}`);
};
export const subscribe = (repoId: number, method: string, path: string, teamId: number, notifyUrl = "") =>
  j<Subscription>("/subscriptions", { method: "POST", body: JSON.stringify({ repoId, method, path, teamId, notifyUrl }) });
export const unsubscribe = (id: number) => j<unknown>(`/subscriptions/${id}`, { method: "DELETE" });
export const listNotifications = (teamId?: number, limit = 20) => {
  const p = new URLSearchParams();
  if (teamId) p.set("teamId", String(teamId));
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

// ── 阶段 C：项目级服务账号（C5）────────────────────────────────────────────
export interface ServiceAccount {
  id: number;
  projectId: number;
  name: string;
  /** 令牌前缀（明文只返回一次） */
  prefix: string;
  role: string;
  status: string;
  expiresAt?: string | null;
  lastUsedAt?: string | null;
  revokedAt?: string | null;
  createdAt?: string | null;
}

export interface CreateServiceAccountResp {
  account: ServiceAccount;
  token: string;
}

export const SERVICE_ACCOUNT_ROLES = ["viewer", "editor"];

export const listServiceAccounts = (projectId: number) =>
  j<ServiceAccount[]>(`/service-accounts?projectId=${projectId}`);
export const createServiceAccount = (body: {
  projectId: number;
  name: string;
  role: string;
  expiresInDays?: number;
}) => j<CreateServiceAccountResp>("/service-accounts", { method: "POST", body: JSON.stringify(body) });
export const rotateServiceAccount = (id: number, expiresInDays?: number) =>
  j<CreateServiceAccountResp>(`/service-accounts/${id}/rotate`, {
    method: "POST",
    body: JSON.stringify({ expiresInDays }),
  });
export const revokeServiceAccount = (id: number) =>
  j<void>(`/service-accounts/${id}`, { method: "DELETE" });

// ── 阶段 C：管理动作审计（C7，org_admin）───────────────────────────────────
export interface AuditEvent {
  id: number;
  actorName: string;
  actorType: string;
  action: string;
  targetType?: string | null;
  targetId?: string | null;
  detail?: string | null;
  outcome: string;
  ip?: string | null;
  createdAt?: string | null;
}

export interface AuditQuery {
  from?: string;
  to?: string;
  actor?: string;
  action?: string;
  targetType?: string;
  outcome?: string;
  limit?: number;
  offset?: number;
}

export const listAudit = (q: AuditQuery = {}) => {
  const p = new URLSearchParams();
  if (q.from) p.set("from", q.from);
  if (q.to) p.set("to", q.to);
  if (q.actor?.trim()) p.set("actor", q.actor.trim());
  if (q.action?.trim()) p.set("action", q.action.trim());
  if (q.targetType?.trim()) p.set("targetType", q.targetType.trim());
  if (q.outcome) p.set("outcome", q.outcome);
  if (q.limit != null) p.set("limit", String(q.limit));
  if (q.offset != null) p.set("offset", String(q.offset));
  const s = p.toString();
  return j<AuditEvent[]>(`/audit${s ? `?${s}` : ""}`);
};

/** repo.url → 展示名（git 路径尾段，去 .git） */
export const repoDisplay = (url: string) => url.replace(/\.git$/, "").split("/").pop() ?? url;