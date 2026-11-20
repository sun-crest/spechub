import { useEffect, useMemo, useRef, useState } from "react";
import { Boxes, ChevronsUpDown, ClipboardList, KeyRound, LogOut, Moon, PanelLeftClose, Search, Shield, ShieldCheck, Sun, CheckCircle2, Users, History, ClipboardCheck, FolderPlus, Settings2, Plus } from "lucide-react";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ScrollArea } from "@/components/ui/scroll-area";
import { ApiList, type Filter } from "./demo/ApiList";
import { ApiDetail } from "./demo/ApiDetail";
import { EditorView, type EditingEntry } from "./demo/EditorView";
import { LoginDialog } from "./demo/LoginDialog";
import { MembersDialog } from "./demo/MembersDialog";
import { ProjectDialog } from "./demo/ProjectDialog";
import { RepoRegisterDialog } from "./demo/RepoRegisterDialog";
import { ChangeLogDialog } from "./demo/ChangeLogDialog";
import { ReviewDialog } from "./demo/ReviewDialog";
import { TeamDialog } from "./demo/TeamDialog";
import { SecurityDialog } from "./demo/SecurityDialog";
import { ServiceAccountDialog } from "./demo/ServiceAccountDialog";
import { AuditDialog } from "./demo/AuditDialog";
import { DocPanel } from "./demo/DocPanel";
import { getAuthStatus, setCsrfToken, logout, UNAUTHORIZED_EVENT, ApiError, getMe, listApis, listProjects, listRepos, listDocs, searchDocs, listReviews, repoDisplay, WRITE_ROLES, type Api, type Doc, type Me, type Project, type Repo, type WriteSpecResp, type AuthStatus, type LoginResult } from "@/lib/api";

export default function App() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains("dark"));
  const [domain, setDomain] = useState("api");
  const [collapsed, setCollapsed] = useState(false);
  // I6 双层版本：draft（仓内最新）/ release（发布快照）全局视图开关（UI 方案决策 1 top pin）
  const [stage, setStage] = useState<"draft" | "release">("draft");

  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<number | null>(null);
  const [repos, setRepos] = useState<Repo[]>([]);
  const [apis, setApis] = useState<Api[]>([]);
  // K Doc 域
  const [docs, setDocs] = useState<Doc[]>([]);
  const [selectedDocId, setSelectedDocId] = useState<number | null>(null);
  const [docQ, setDocQ] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // A8：项目创建/设置、仓库注册入口
  const [projectDialog, setProjectDialog] = useState<{ mode: "create" | "settings" } | null>(null);
  const [repoDialogOpen, setRepoDialogOpen] = useState(false);

  const [filter, setFilter] = useState<Filter>({});
  const [selected, setSelected] = useState<number | null>(null);
  const [editing, setEditing] = useState<EditingEntry | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  // 阶段 C：所有环境真实登录；authStatus 仅用于 OIDC 探测，me 为当前会话主体
  const [authStatus, setAuthStatus] = useState<AuthStatus | null>(null);
  const [loginOpen, setLoginOpen] = useState(false);
  const [loginError, setLoginError] = useState<string | null>(null);
  // I4 RBAC：当前用户 + 项目角色（决定写入口可见性）
  const [me, setMe] = useState<Me | null>(null);
  const authedRef = useRef(false);
  // 账号与安全 / 服务账号 / 审计日志
  const [securityOpen, setSecurityOpen] = useState(false);
  const [serviceAccountsOpen, setServiceAccountsOpen] = useState(false);
  const [auditOpen, setAuditOpen] = useState(false);
  const [membersOpen, setMembersOpen] = useState(false);
  // B6：团队管理（组织级，账号菜单入口）
  const [teamsOpen, setTeamsOpen] = useState(false);
  // I5 变更记录独立视图
  const [changeLogOpen, setChangeLogOpen] = useState(false);
  // I8 评审审批流
  const [reviewsOpen, setReviewsOpen] = useState(false);
  const [pendingCount, setPendingCount] = useState(0);
  const loadPendingReviews = () => {
    if (!authedRef.current) { setPendingCount(0); return; }
    void listReviews("pending").then((rs) => setPendingCount(rs.length)).catch(() => setPendingCount(0));
  };
  useEffect(() => { loadPendingReviews(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [me?.authenticated]);

  useEffect(() => { document.documentElement.classList.toggle("dark", dark); }, [dark]);

  /** 应用 `/auth/me` 结果：同步内存 CSRF token 与「已认证」标记（供 load 守卫使用）。 */
  const applyMe = (m: Me | null) => {
    authedRef.current = m?.authenticated === true;
    setMe(m);
    if (m?.csrfToken) setCsrfToken(m.csrfToken);
  };
  const refreshMe = async (): Promise<Me | null> => {
    try {
      const m = await getMe();
      applyMe(m);
      return m;
    } catch {
      applyMe(null);
      return null;
    }
  };

  // 启动即探测会话：/auth/status + /auth/me；无会话一律弹登录框（无 dev 免登录分支）
  useEffect(() => {
    let alive = true;
    void (async () => {
      // OIDC 回调失败（/?loginError=oidc）：提示后清理 URL 标记
      const params = new URLSearchParams(window.location.search);
      if (params.get("loginError") === "oidc") {
        setLoginError("SSO 登录失败，请重试或改用密码登录");
        setLoginOpen(true);
        params.delete("loginError");
        const qs = params.toString();
        window.history.replaceState({}, "", window.location.pathname + (qs ? `?${qs}` : ""));
      }
      try {
        const s = await getAuthStatus();
        if (!alive) return;
        setAuthStatus(s);
        if (!s.authenticated) {
          setLoading(false);
          setLoginOpen(true);
          return;
        }
        const m = await getMe();
        if (!alive) return;
        applyMe(m);
        void load();
      } catch {
        if (!alive) return;
        setLoading(false);
        setLoginOpen(true);
      }
    })();
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 会话失效（任意 API 401）：清空当前用户与数据，关闭管理对话框并弹出登录框
  useEffect(() => {
    const onUnauthorized = () => {
      authedRef.current = false;
      setMe(null);
      setAuthStatus((s) => (s ? { ...s, authenticated: false, username: null, authMethod: null } : { authEnabled: true, authenticated: false }));
      setProjects([]); setRepos([]); setApis([]); setDocs([]);
      setSelected(null); setEditing(null); setSelectedDocId(null);
      setSecurityOpen(false); setServiceAccountsOpen(false); setAuditOpen(false);
      setMembersOpen(false); setTeamsOpen(false); setReviewsOpen(false);
      setPendingCount(0);
      setLoginOpen(true);
    };
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, []);

  // I4 编辑入口可见性：必须已认证，且组织管理员或当前项目角色 ∈ editor/admin/owner
  const selectedProject = projects.find((p) => p.id === selectedProjectId) ?? projects[0] ?? null;
  const currentProjectRole = selectedProjectId != null
    ? (me?.projectRoles?.[String(selectedProjectId)] ?? null)
    : null;
  const isOrgAdmin = me?.orgAdmin === true;
  const isAuthenticated = me?.authenticated === true;
  const canManageCurrentProject = isOrgAdmin || (currentProjectRole != null && ["admin", "owner"].includes(currentProjectRole));
  const canOwnerCurrentProject = isOrgAdmin || currentProjectRole === "owner";
  const canWrite = isAuthenticated && (isOrgAdmin || WRITE_ROLES.includes(currentProjectRole ?? ""));
  // I6：release 是只读消费视图，编辑/发布只作用于 draft
  const canEdit = canWrite && stage === "draft";
  // I5：评论能力 = 登录即可
  const canComment = isAuthenticated;
  const meUsername = me?.username ?? null;

  // A8：先取可见项目，再按选中项目拉取 repos / apis / docs（不再全量）
  const loadSeq = useRef(0);
  async function load() {
    // 未登录不拉业务数据（登录成功后由 onAuthed 显式触发）
    if (!authedRef.current) {
      setProjects([]); setRepos([]); setApis([]); setDocs([]);
      setLoading(false);
      return;
    }
    const seq = ++loadSeq.current;
    setLoading(true);
    setError(null);
    try {
      const pr = await listProjects();
      if (seq !== loadSeq.current) return;
      setProjects(pr);
      // 选中项失效（删除/归档/权限变化）时回落到第一个可见项目
      const pid =
        selectedProjectId != null && pr.some((p) => p.id === selectedProjectId)
          ? selectedProjectId
          : (pr[0]?.id ?? null);
      if (pid !== selectedProjectId) setSelectedProjectId(pid);
      const [re, ap, dc] = await Promise.all([
        listRepos(pid ?? undefined),
        listApis(undefined, undefined, stage, pid ?? undefined),
        listDocs(pid != null ? { projectId: pid } : undefined),
      ]);
      if (seq !== loadSeq.current) return;
      setRepos(re);
      setApis(ap);
      setDocs(dc);
    } catch (e) {
      // 401 由全局 unauthorized 处理（弹登录框），不展示业务错误横幅
      if (seq === loadSeq.current && !(e instanceof ApiError && e.status === 401)) setError(String(e));
    } finally {
      if (seq === loadSeq.current) setLoading(false);
    }
  }
  useEffect(() => { void load(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [stage, selectedProjectId]);

  // 切换项目时清空跨项目选择（详情/编辑/文档/仓库过滤）
  useEffect(() => {
    setSelected(null);
    setEditing(null);
    setSelectedDocId(null);
    setFilter({});
  }, [selectedProjectId]);

  // K：Doc 全文检索（防抖，按当前项目；切换项目时丢弃过期响应）
  useEffect(() => {
    let alive = true;
    const t = setTimeout(() => {
      const opts = selectedProjectId != null ? { projectId: selectedProjectId } : undefined;
      const p = docQ.trim() ? searchDocs(docQ.trim(), opts) : listDocs(opts);
      void p.then((d) => { if (alive) setDocs(d); }).catch(() => { if (alive) setDocs([]); });
    }, 250);
    return () => { alive = false; clearTimeout(t); };
  }, [docQ, selectedProjectId]);

  // K：Doc↔Api 链接 —— 从 AI 文档跳转到关联接口详情
  function gotoApi(path: string) {
    const api = apis.find((a) => a.path === "/" + path.replace(/^\/+/, ""));
    setDomain("api");
    setSelected(api?.id ?? null);
    if (!api) setToast(`未找到关联接口：/${path}`);
  }

  const tags = useMemo(() => Array.from(new Set(apis.map((a) => a.tag).filter(Boolean) as string[])), [apis]);

  const toggleRepo = (id: number) => setFilter((f) => (f.repoId === id ? { ...f, repoId: undefined } : { ...f, repoId: id }));
  const toggleTag = (t?: string) => setFilter((f) => (f.tag === t ? { ...f, tag: undefined } : { ...f, tag: t }));

  const cntByRepo = (id: number) => apis.filter((a) => a.repoId === id).length;
  const cntByTag = (t: string) => apis.filter((a) => a.tag === t).length;

  /** 登录成功：换成会话后刷新 /auth/me 与业务数据（登录框不持久化令牌）。 */
  async function handleAuthed(_result: LoginResult) {
    setLoginError(null);
    setLoginOpen(false);
    // 重新探测 status，拿到 OIDC 配置等仅登录前可见的字段
    try {
      setAuthStatus(await getAuthStatus());
    } catch {
      setAuthStatus((s) => (s ? { ...s, authenticated: true } : { authEnabled: true, authenticated: true }));
    }
    const m = await refreshMe();
    if (m?.authenticated) await load();
  }

  /** 退出登录：POST /auth/logout 删除 Spring Session，清空前端状态并回到登录框。 */
  async function handleLogout() {
    try {
      await logout();
    } catch {
      /* 会话可能已失效：仍按已登出处理 */
    }
    setCsrfToken(null);
    authedRef.current = false;
    setMe(null);
    setAuthStatus((s) => (s ? { ...s, authenticated: false, username: null, authMethod: null } : { authEnabled: true, authenticated: false }));
    setProjects([]); setRepos([]); setApis([]); setDocs([]);
    setSelected(null); setEditing(null); setSelectedDocId(null);
    setSecurityOpen(false); setServiceAccountsOpen(false); setAuditOpen(false);
    setMembersOpen(false); setTeamsOpen(false); setReviewsOpen(false);
    setPendingCount(0);
    setLoginOpen(true);
    setToast("已退出登录");
  }

  return (
    <div className="flex h-svh bg-background text-foreground">
      {/* 侧栏 */}
      <aside className={`w-60 shrink-0 flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground ${collapsed ? "hidden" : "flex"}`}>
        <div className="p-3 pb-2">
          <Tabs value={domain} onValueChange={setDomain} className="w-full">
            <TabsList className="w-full bg-muted">
              <TabsTrigger value="api" className="flex-1">接口</TabsTrigger>
              <TabsTrigger value="doc" className="flex-1">AI 文档</TabsTrigger>
            </TabsList>
          </Tabs>
        </div>

        {domain === "api" ? (
          <ScrollArea className="min-h-0 flex-1 px-2">
            <div className="mb-1 flex items-center justify-between pr-1 pl-2.5 pt-2">
              <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">仓库</span>
              {canWrite && selectedProjectId != null && (
                <button
                  onClick={() => setRepoDialogOpen(true)}
                  className="grid size-6 place-items-center rounded-md text-muted-foreground hover:bg-sidebar-accent hover:text-foreground"
                  title="注册仓库（归属当前项目）"
                  aria-label="注册仓库"
                >
                  <Plus className="size-3.5" />
                </button>
              )}
            </div>
            <div className="flex flex-col gap-0.5">
              {repos.map((r) => (
                <button
                  key={r.id}
                  onClick={() => toggleRepo(r.id)}
                  className={`flex items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm transition-colors ${
                    filter.repoId === r.id ? "bg-primary/8 font-medium text-primary" : "text-foreground/80 hover:bg-sidebar-accent"
                  }`}
                >
                  <Boxes className="size-4 shrink-0" />
                  <span className="flex-1 truncate">{repoDisplay(r.url)}</span>
                  <span className={`size-2 shrink-0 rounded-full ${r.syncStatus === "ok" ? "bg-green-500" : r.syncStatus === "error" ? "bg-red-500" : "bg-amber-400"}`} aria-hidden="true" />
                  <span className="text-xs text-muted-foreground">{r.syncStatus}</span>
                  <span className="font-mono text-xs">{cntByRepo(r.id)}</span>
                </button>
              ))}
              {repos.length === 0 && (loading ? (
                <div className="px-2.5 py-2 text-xs text-muted-foreground">加载中…</div>
              ) : (
                <div className="px-2.5 py-2 text-xs text-muted-foreground">
                  当前项目还没有仓库。
                  {canWrite && selectedProjectId != null && (
                    <button onClick={() => setRepoDialogOpen(true)} className="ml-1 text-link hover:underline">注册仓库…</button>
                  )}
                </div>
              ))}
            </div>

            {tags.length > 0 && (
              <>
                <div className="mb-1 px-2.5 pt-5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">分组</div>
                <div className="flex flex-col gap-0.5">
                  {tags.map((t) => (
                    <button
                      key={t}
                      onClick={() => toggleTag(t)}
                      className={`flex items-center rounded-md px-2.5 py-1.5 text-sm transition-colors ${
                        filter.tag === t ? "bg-primary/8 font-medium text-primary" : "text-muted-foreground hover:bg-sidebar-accent"
                      }`}
                    >
                      <span className="flex-1">{t}</span>
                      <span className="font-mono text-xs">{cntByTag(t)}</span>
                    </button>
                  ))}
                </div>
              </>
            )}
          </ScrollArea>
        ) : (
          <ScrollArea className="min-h-0 flex-1 px-2">
            <div className="flex flex-col gap-0.5">
              <div className="px-2.5 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">docs/（AI 文档）</div>
              <Input
                value={docQ}
                onChange={(e) => setDocQ(e.target.value)}
                placeholder="全文搜索…"
                className="mb-1 h-8 bg-muted/50 text-[12px]"
              />
              {docs.map((d) => (
                <button
                  key={d.id}
                  onClick={() => setSelectedDocId(d.id)}
                  className={`flex flex-col gap-0.5 rounded-md px-2.5 py-2 text-left text-sm transition-colors ${
                    d.id === selectedDocId ? "bg-primary/8 text-primary" : "text-foreground/80 hover:bg-sidebar-accent"
                  }`}
                >
                  <span className="font-mono text-[12.5px]">{d.path.replace(/^docs\//, "")}</span>
                  <span className={`text-[11.5px] ${d.id === selectedDocId ? "text-primary/70" : "text-muted-foreground"}`}>{d.title}</span>
                </button>
              ))}
              {docs.length === 0 && <p className="px-2.5 py-2 text-[11.5px] text-muted-foreground">暂无文档（摄取 specs/ 同仓的 docs/ 下 Markdown）</p>}
            </div>
          </ScrollArea>
        )}
        {/* I5：侧栏底部全局变更记录入口（跨 repo 写操作留痕） */}
        <div className="shrink-0 border-t border-sidebar-border p-2">
          <button
            onClick={() => setChangeLogOpen(true)}
            className="flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-[13px] text-muted-foreground transition-colors hover:bg-sidebar-accent"
          >
            <History className="size-4 shrink-0" />
            <span className="flex-1">变更记录</span>
          </button>
          {/* I8：评审队列入口（启鉴权时显示待审计数） */}
          {me?.authEnabled && (
            <button
              onClick={() => { loadPendingReviews(); setReviewsOpen(true); }}
              className="mt-1 flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-[13px] text-muted-foreground transition-colors hover:bg-sidebar-accent"
            >
              <ClipboardCheck className="size-4 shrink-0" />
              <span className="flex-1">评审队列</span>
              {pendingCount > 0 && (
                <span className="rounded-full bg-primary px-1.5 py-0.5 font-mono text-[10px] font-semibold text-primary-foreground">{pendingCount}</span>
              )}
            </button>
          )}
        </div>
      </aside>

      {/* 主区 */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-16 shrink-0 items-center gap-3 border-b border-border/70 px-6">
          <button className="grid size-8 place-items-center rounded-md text-muted-foreground hover:bg-muted" aria-label="折叠侧栏" onClick={() => setCollapsed((s) => !s)}>
            <PanelLeftClose className="size-4" />
          </button>
          <div className="flex items-center gap-2 font-semibold tracking-tight">
            <Shield className="size-5 shrink-0 text-link" />
            SpecHub
          </div>
          <DropdownMenu>
            <DropdownMenuTrigger className="flex max-w-56 items-center gap-1.5 rounded-md border px-2.5 py-1.5 text-[13px] text-muted-foreground hover:bg-muted">
              <span className="truncate">{selectedProject?.name ?? "加载项目…"}</span>
              {selectedProject?.archived && <span className="rounded bg-muted px-1 text-[10px]">已归档</span>}
              <ChevronsUpDown className="size-3.5 shrink-0" aria-hidden="true" />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="min-w-56">
              {projects.map((p) => (
                <DropdownMenuItem key={p.id} onSelect={() => setSelectedProjectId(p.id)}>
                  <span className="flex-1 truncate">{p.name}</span>
                  {p.archived && <span className="text-[10px] text-muted-foreground">已归档</span>}
                  {p.id === selectedProjectId && <span className="text-[10px] text-muted-foreground">当前</span>}
                </DropdownMenuItem>
              ))}
              {projects.length === 0 && <DropdownMenuItem disabled>无项目</DropdownMenuItem>}
              {isOrgAdmin && (
                <DropdownMenuItem onSelect={() => setProjectDialog({ mode: "create" })}>
                  <FolderPlus className="size-3.5" /> 新建项目…
                </DropdownMenuItem>
              )}
              {selectedProject != null && canManageCurrentProject && (
                <DropdownMenuItem onSelect={() => setProjectDialog({ mode: "settings" })}>
                  <Settings2 className="size-3.5" /> 项目设置…
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
          {/* I6 双层版本开关：draft（仓内最新，可编辑） / release（发布快照，只读消费视图） */}
          <div className="ml-2 flex shrink-0 items-center overflow-hidden rounded-lg border text-[12px]" role="group" aria-label="版本档位">
            {(["draft", "release"] as const).map((s) => (
              <button
                key={s}
                onClick={() => { setStage(s); setEditing(null); }}
                className={`px-2.5 py-1 transition-colors ${s === stage ? "bg-primary font-medium text-primary-foreground" : "text-muted-foreground hover:bg-muted"}`}
                title={s === "draft" ? "仓内最新（编辑中，可发布）" : "对外发布快照（消费者默认读 release）"}
              >
                {s}
              </button>
            ))}
          </div>
          <div className="relative ml-2 w-72">
            <Search className="absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input placeholder="搜索接口 / 文档…" className="bg-muted/60 pl-9 text-[13px] focus-visible:bg-background" />
            <kbd className="absolute top-1/2 right-2.5 -translate-y-1/2 rounded border bg-background px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground">⌘K</kbd>
          </div>
          <div className="ml-auto flex items-center gap-2">
            <Button variant="ghost" size="icon" onClick={() => setDark((d) => !d)} aria-label="切换主题">
              {dark ? <Sun className="size-4" /> : <Moon className="size-4" />}
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger
                className="flex items-center gap-1.5 rounded-full border px-2 py-1 outline-none data-open:bg-muted"
                aria-label="账号菜单"
              >
                <Avatar className="size-8">
                  <AvatarFallback className="bg-secondary text-xs font-semibold text-foreground">
                    {isAuthenticated ? (me?.username?.[0]?.toUpperCase() ?? "A") : "?"}
                  </AvatarFallback>
                </Avatar>
                <span className="text-[11px]">
                  {isAuthenticated
                    ? (canWrite
                      ? <span className="text-emerald-600 dark:text-emerald-400">{me?.username}</span>
                      : <span className="text-muted-foreground">{me?.username}</span>)
                    : <span className="text-muted-foreground">未登录</span>}
                </span>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                {isAuthenticated ? (
                  <>
                    <DropdownMenuItem disabled className="flex flex-col items-start gap-0.5">
                      <span className="font-mono text-[13px]">{me?.username}</span>
                      <span className="text-[11px] text-muted-foreground">
                        {isOrgAdmin
                          ? "组织管理员（org_admin）"
                          : (currentProjectRole ? `${selectedProject?.name ?? "当前项目"} · 角色：${currentProjectRole}` : "未分配项目角色（只读）")}
                      </span>
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={() => setSecurityOpen(true)}>
                      <ShieldCheck className="size-3.5" /> 账号与安全…
                    </DropdownMenuItem>
                    {canManageCurrentProject && (
                      <DropdownMenuItem onSelect={() => setMembersOpen(true)}>
                        <Users className="size-3.5" /> 成员与角色…
                      </DropdownMenuItem>
                    )}
                    {canManageCurrentProject && (
                      <DropdownMenuItem onSelect={() => setServiceAccountsOpen(true)}>
                        <KeyRound className="size-3.5" /> 服务账号…
                      </DropdownMenuItem>
                    )}
                    {isOrgAdmin && (
                      <DropdownMenuItem onSelect={() => setAuditOpen(true)}>
                        <ClipboardList className="size-3.5" /> 审计日志…
                      </DropdownMenuItem>
                    )}
                    <DropdownMenuItem onSelect={() => setTeamsOpen(true)}>
                      <Users className="size-3.5" /> 团队管理…
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={() => void handleLogout()}>
                      <LogOut className="size-3.5" /> 退出登录
                    </DropdownMenuItem>
                  </>
                ) : (
                  <>
                    <DropdownMenuItem disabled className="text-muted-foreground">未登录</DropdownMenuItem>
                    <DropdownMenuItem onSelect={() => setLoginOpen(true)}>登录…</DropdownMenuItem>
                  </>
                )}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </header>

        <main className="min-h-0 flex-1">
          {me == null ? (
            <div className="flex h-full items-center justify-center p-8 text-[13px] text-muted-foreground">
              正在校验登录状态…
            </div>
          ) : !isAuthenticated ? (
            <div className="flex h-full flex-col items-center justify-center gap-3 p-8 text-center">
              <Shield className="size-8 text-muted-foreground/60" />
              <div className="text-sm text-muted-foreground">请登录后使用 SpecHub。</div>
              <Button size="sm" onClick={() => setLoginOpen(true)}>登录…</Button>
            </div>
          ) : !loading && !error && projects.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center gap-3 p-8 text-center">
              <FolderPlus className="size-8 text-muted-foreground/60" />
              <div className="text-sm text-muted-foreground">当前没有可见项目。</div>
              {isOrgAdmin ? (
                <Button size="sm" onClick={() => setProjectDialog({ mode: "create" })}>
                  <FolderPlus className="size-3.5" /> 新建项目
                </Button>
              ) : (
                <div className="text-[12.5px] text-muted-foreground">请联系组织管理员把你加入项目。</div>
              )}
            </div>
          ) : domain === "api" ? (
            editing && canEdit ? (
              <EditorView
                entry={editing}
                repos={repos}
                onBack={() => { setEditing(null); setSelected(null); void load(); }}
                onSaved={(r: WriteSpecResp) => {
                  setToast(r.message || (r.reviewId ? `已提交评审请求 #${r.reviewId}` : `已提交 ${r.commit.slice(0, 8)} · ${r.path}（${r.apiCount} 个接口）`));
                  setEditing(null);
                  setSelected(null);
                  void load(); void loadPendingReviews();
                }}
              />
            ) : selected && !loading && !error ? (
              <ApiDetail
                id={selected}
                onBack={() => setSelected(null)}
                repos={repos}
                canWrite={canEdit}
                canComment={canComment}
                meUsername={meUsername}
                stage={stage}
                onEdit={(a) => setEditing({ repoId: a.repoId, path: a.specPath ?? null })}
                onPromoted={(msg) => { setToast(msg); void load(); }}
              />
            ) : (
              <ApiList
                apis={apis}
                repos={repos}
                filter={filter}
                onFilter={setFilter}
                onSelect={setSelected}
                onEdit={() => setEditing({ repoId: repos[0]?.id, path: null })}
                canWrite={canEdit}
                loading={loading}
                error={error}
                onRetry={load}
              />
            )
          ) : (
            selectedDocId ? (
              <DocPanel
                docId={selectedDocId}
                canComment={canComment}
                meUsername={meUsername}
                onBack={() => setSelectedDocId(null)}
                onNavigateApi={gotoApi}
              />
            ) : (
              <div className="flex h-full items-center justify-center p-8 text-[13px] text-muted-foreground">
                从左侧选择一份 AI 文档（docs/ 域，与接口分开管理）
              </div>
            )
          )}
        </main>
      </div>

      {/* 保存成功 toast */}
      {toast && (
        <div className="fixed right-6 bottom-6 z-50 flex items-center gap-2.5 rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-4 py-3 text-[13px] text-emerald-700 shadow-lg dark:text-emerald-400">
          <CheckCircle2 className="size-4 shrink-0" />
          <span>{toast}</span>
          <button className="ml-2 text-muted-foreground hover:text-foreground" onClick={() => setToast(null)} aria-label="关闭">✕</button>
        </div>
      )}

      {/* 阶段 C 登录：密码 / OIDC / 兼容令牌；成功后建立真实 Spring Session */}
      <LoginDialog
        open={loginOpen}
        onOpenChange={(o) => { setLoginOpen(o); if (!o) setLoginError(null); }}
        onAuthed={(status) => { void handleAuthed(status); }}
        oidcEnabled={authStatus?.oidcEnabled}
        oidcLoginUrl={authStatus?.oidcLoginUrl}
        initialError={loginError}
      />

      {/* 阶段 C 账号与安全：资料 / 改密 / 活动会话 / 我的令牌 */}
      <SecurityDialog
        open={securityOpen || me?.mustChangePassword === true}
        onOpenChange={(o) => { if (!o && me?.mustChangePassword) return; setSecurityOpen(o); }}
        me={me}
        forcePasswordChange={me?.mustChangePassword === true}
        onChanged={() => void refreshMe()}
      />

      {/* 阶段 C 服务账号（当前项目 admin+ / org_admin） */}
      <ServiceAccountDialog
        open={serviceAccountsOpen}
        onOpenChange={setServiceAccountsOpen}
        projects={projects}
        projectRoles={me?.projectRoles}
        orgAdmin={isOrgAdmin}
        defaultProjectId={selectedProjectId}
      />

      {/* 阶段 C 审计日志（org_admin） */}
      <AuditDialog open={auditOpen} onOpenChange={setAuditOpen} />

      {/* I4 / A6 项目成员与角色管理（当前项目 admin+） */}
      <MembersDialog
        open={membersOpen}
        onOpenChange={setMembersOpen}
        projectId={selectedProjectId}
        projectName={selectedProject?.name ?? null}
        canManageOrg={isOrgAdmin}
      />

      {/* A8 项目创建 / 设置（归档 / 删除） */}
      <ProjectDialog
        open={projectDialog != null}
        onOpenChange={(o) => { if (!o) setProjectDialog(null); }}
        mode={projectDialog?.mode ?? "create"}
        project={projectDialog?.mode === "settings" ? selectedProject : null}
        canCreate={isOrgAdmin}
        canOwner={canOwnerCurrentProject}
        canAdmin={canManageCurrentProject}
        onChanged={(p) => {
          if (p) {
            setToast(projectDialog?.mode === "create" ? `项目「${p.name}」已创建` : `项目「${p.name}」已更新`);
            setSelectedProjectId(p.id);
          }
          void load();
        }}
      />

      {/* A7/A8 仓库注册：必须归属项目 */}
      <RepoRegisterDialog
        open={repoDialogOpen}
        onOpenChange={setRepoDialogOpen}
        projects={projects}
        projectId={selectedProjectId}
        onRegistered={(msg) => { setToast(msg); void load(); }}
      />

      {/* B6 团队管理（org_admin / team lead；普通成员查看自己的团队） */}
      <TeamDialog
        open={teamsOpen}
        onOpenChange={setTeamsOpen}
        me={me}
        onChanged={() => void refreshMe()}
      />

      {/* I5 全局变更记录 */}
      <ChangeLogDialog open={changeLogOpen} onOpenChange={setChangeLogOpen} />

      {/* I8 评审审批流 */}
      <ReviewDialog open={reviewsOpen} onOpenChange={(o) => { setReviewsOpen(o); if (!o) loadPendingReviews(); }} canReview={canWrite} />
    </div>
  );
}