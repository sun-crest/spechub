import { useEffect, useMemo, useState } from "react";
import { Boxes, ChevronsUpDown, LogOut, Moon, PanelLeftClose, Search, Shield, Sun, CheckCircle2 } from "lucide-react";
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
import { DocPanel } from "./demo/DocPanel";
import { DOCS } from "./demo/data";
import { getAuthStatus, getAdminToken, setAdminToken, listApis, listProjects, listRepos, repoDisplay, type Api, type Project, type Repo, type WriteSpecResp, type AuthStatus } from "@/lib/api";

export default function App() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains("dark"));
  const [domain, setDomain] = useState("api");
  const [collapsed, setCollapsed] = useState(false);
  // I6 双层版本：draft（仓内最新）/ release（发布快照）全局视图开关（UI 方案决策 1 top pin）
  const [stage, setStage] = useState<"draft" | "release">("draft");

  const [projects, setProjects] = useState<Project[]>([]);
  const [repos, setRepos] = useState<Repo[]>([]);
  const [apis, setApis] = useState<Api[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [filter, setFilter] = useState<Filter>({});
  const [selected, setSelected] = useState<number | null>(null);
  const [selectedDoc, setSelectedDoc] = useState("overview");
  const [editing, setEditing] = useState<EditingEntry | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  // H6 登录：authStatus=null 表示尚未探测；authEnabled=false 后端未启用鉴权（免登录只读+可写）
  const [authStatus, setAuthStatus] = useState<AuthStatus | null>(null);
  const [loginOpen, setLoginOpen] = useState(false);

  useEffect(() => { document.documentElement.classList.toggle("dark", dark); }, [dark]);

  useEffect(() => {
    let alive = true;
    getAuthStatus().then((s) => { if (alive) { setAuthStatus(s); if (s.authEnabled && !s.authenticated) setLoginOpen(true); } }).catch(() => {});
    return () => { alive = false; };
  }, []);

  // 编辑入口可见性（§5「H6 登录决定编辑入口」）：后端启用鉴权且未认证 → 只读
  const canWrite = authStatus == null || !authStatus.authEnabled || authStatus.authenticated;
  // I6：release 是只读消费视图，编辑/发布只作用于 draft
  const canEdit = canWrite && stage === "draft";

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [pr, re, ap] = await Promise.all([listProjects(), listRepos(), listApis(undefined, undefined, stage)]);
      setProjects(pr);
      setRepos(re);
      setApis(ap);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => { void load(); }, [stage]);

  const tags = useMemo(() => Array.from(new Set(apis.map((a) => a.tag).filter(Boolean) as string[])), [apis]);

  const toggleRepo = (id: number) => setFilter((f) => (f.repoId === id ? { ...f, repoId: undefined } : { ...f, repoId: id }));
  const toggleTag = (t?: string) => setFilter((f) => (f.tag === t ? { ...f, tag: undefined } : { ...f, tag: t }));

  const cntByRepo = (id: number) => apis.filter((a) => a.repoId === id).length;
  const cntByTag = (t: string) => apis.filter((a) => a.tag === t).length;

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
            <div className="mb-1 px-2.5 pt-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">仓库</div>
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
              {repos.length === 0 && (loading ? <div className="px-2.5 py-2 text-xs text-muted-foreground">加载中…</div> : <div className="px-2.5 py-2 text-xs text-muted-foreground">暂无仓库，先 POST /api/repos 注册</div>)}
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
              <div className="px-2.5 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">docs/</div>
              {DOCS.map((d) => (
                <button
                  key={d.id}
                  onClick={() => setSelectedDoc(d.id)}
                  className={`flex flex-col gap-0.5 rounded-md px-2.5 py-2 text-left text-sm transition-colors ${
                    d.id === selectedDoc ? "bg-primary/8 text-primary" : "text-foreground/80 hover:bg-sidebar-accent"
                  }`}
                >
                  <span className="font-mono text-[12.5px]">{d.name}</span>
                  <span className={`text-[11.5px] ${d.id === selectedDoc ? "text-primary/70" : "text-muted-foreground"}`}>{d.title}</span>
                </button>
              ))}
            </div>
          </ScrollArea>
        )}
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
              <span className="truncate">{projects[0]?.name ?? "加载项目…"}</span>
              <ChevronsUpDown className="size-3.5 shrink-0" aria-hidden="true" />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              {projects.map((p) => <DropdownMenuItem key={p.id} onSelect={() => {}}>{p.name}</DropdownMenuItem>)}
              {projects.length === 0 && <DropdownMenuItem disabled>无项目</DropdownMenuItem>}
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
                    {canWrite ? "A" : "?"}
                  </AvatarFallback>
                </Avatar>
                <span className={`text-[11px] ${canWrite ? "text-emerald-600 dark:text-emerald-400" : "text-muted-foreground"}`}>
                  {authStatus ? (canWrite ? "已认证" : "只读") : "…"}
                </span>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                {!canWrite && authStatus?.authEnabled ? (
                  <>
                    <DropdownMenuItem disabled className="text-muted-foreground">未登录（只读）</DropdownMenuItem>
                    <DropdownMenuItem onSelect={() => setLoginOpen(true)}>登录…</DropdownMenuItem>
                  </>
                ) : (
                  <>
                    <DropdownMenuItem disabled className="text-muted-foreground">
                      {authStatus?.authEnabled ? "admin 令牌已认证" : "后端未启用鉴权（免登录）"}
                    </DropdownMenuItem>
                    {authStatus?.authEnabled && (
                      <DropdownMenuItem onSelect={() => { setAdminToken(null); setAuthStatus({ ...authStatus, authenticated: false }); setEditing(null); void load(); }}>
                        <LogOut className="size-3.5" /> 退出登录
                      </DropdownMenuItem>
                    )}
                  </>
                )}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </header>

        <main className="min-h-0 flex-1">
          {domain === "api" ? (
            editing && canEdit ? (
              <EditorView
                entry={editing}
                repos={repos}
                onBack={() => { setEditing(null); setSelected(null); void load(); }}
                onSaved={(r: WriteSpecResp) => {
                  setToast(`已提交 ${r.commit.slice(0, 8)} · ${r.path}（${r.apiCount} 个接口）`);
                  setEditing(null);
                  setSelected(null);
                  void load();
                }}
              />
            ) : selected && !loading && !error ? (
              <ApiDetail
                id={selected}
                onBack={() => setSelected(null)}
                repos={repos}
                canWrite={canEdit}
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
            <DocPanel docId={selectedDoc} />
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

      {/* H6 登录对话框 */}
      {authStatus?.authEnabled && (
        <LoginDialog
          open={loginOpen}
          onOpenChange={setLoginOpen}
          onAuthed={(status) => { setAuthStatus(status); void load(); }}
        />
      )}
    </div>
  );
}