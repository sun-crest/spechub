import { useCallback, useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { MethodBadge } from "./method";
import { TryPanel } from "./TryPanel";
import { MockPanel } from "./MockPanel";
import { getApi, listVersions, listChangelog, promoteSpec, listComments, addComment, deleteComment, repoDisplay, type Api, type ChangelogEntry, type Comment, type Repo, type VersionRecord } from "@/lib/api";
import { OperationContract, type SpecOp } from "./contract";

export function ApiDetail({ id, onBack, repos, canWrite, canComment, meUsername, stage, onEdit, onPromoted }: {
  id: number;
  onBack: () => void;
  repos: Repo[];
  canWrite: boolean;
  /** I5 评论能力：登录即可（不必 editor+） */
  canComment: boolean;
  meUsername: string | null;
  /** I6 当前视图档位（release 只读消费视图） */
  stage: "draft" | "release";
  onEdit: (api: Api) => void;
  onPromoted: (message: string) => void;
}) {
  const [api, setApi] = useState<Api | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [tab, setTab] = useState("contract");
  const [versions, setVersions] = useState<VersionRecord[]>([]);
  const [log, setLog] = useState<ChangelogEntry[]>([]);
  const [comments, setComments] = useState<Comment[]>([]);
  const [commentBody, setCommentBody] = useState("");
  const [commentMsg, setCommentMsg] = useState<string | null>(null);
  const [publishing, setPublishing] = useState(false);

  useEffect(() => {
    let alive = true;
    getApi(id).then((a) => { if (alive) { setApi(a); setErr(null); } }).catch((e) => alive && setErr(String(e)));
    return () => { alive = false; };
  }, [id]);

  const loadVersions = useCallback((repoId: number, specPath?: string | null) => {
    void listVersions(repoId, specPath ?? undefined).then(setVersions).catch(() => setVersions([]));
  }, []);
  const loadLog = useCallback((repoId: number) => {
    void listChangelog(repoId).then((l) => setLog(l.slice(0, 12))).catch(() => setLog([]));
  }, []);

  // 详情数据变化时刷新版本、变更记录（发布/编辑后）
  useEffect(() => {
    if (api) { loadVersions(api.repoId, api.specPath); loadLog(api.repoId); }
  }, [api, loadVersions, loadLog]);

  // I5 评论：随详情加载
  const loadComments = useCallback((apiId: number) => {
    void listComments(apiId).then(setComments).catch(() => setComments([]));
  }, []);
  useEffect(() => {
    if (api) loadComments(api.id);
  }, [api, loadComments]);

  async function postComment() {
    if (!api) return;
    if (!commentBody.trim()) { setCommentMsg("请输入评论内容"); return; }
    setCommentMsg(null);
    try {
      const c = await addComment(api.id, commentBody.trim());
      setComments((cs) => [...cs, c]);
      setCommentBody("");
    } catch (e) {
      setCommentMsg(String(e));
    }
  }

  async function delComment(c: Comment) {
    setCommentMsg(null);
    try { await deleteComment(c.id); setComments((cs) => cs.filter((x) => x.id !== c.id)); }
    catch (e) { setCommentMsg(String(e)); }
  }

  const op: SpecOp | null = useMemo(() => {
    if (!api) return null;
    try { return JSON.parse(api.specJson) as SpecOp; } catch { return null; }
  }, [api]);

  const repo = api ? repos.find((r) => r.id === api.repoId) : undefined;
  const latestVersion = versions[0];

  async function publish() {
    if (!api?.specPath) { setErr("该接口未关联 spec 文件（specPath 为空），先编辑后发布"); return; }
    setPublishing(true);
    try {
      const r = await promoteSpec(api.repoId, api.specPath);
      onPromoted(r.message);
      await loadVersions(api.repoId, api.specPath);
      await loadLog(api.repoId);
    } catch (e) {
      setErr(String(e));
    } finally {
      setPublishing(false);
    }
  }

  if (err) {
    return (
      <div className="p-8">
        <Button variant="ghost" size="sm" onClick={onBack}>← 接口</Button>
        <div className="mt-6 rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">加载失败：{err}</div>
      </div>
    );
  }
  if (!api || !op) {
    return (
      <div className="p-8">
        <Button variant="ghost" size="sm" onClick={onBack}>← 接口</Button>
        <div className="mt-6 text-sm text-muted-foreground">加载中…</div>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col gap-5 overflow-auto p-8">
      <nav className="flex items-center gap-2 text-[13px] text-muted-foreground">
        <Button variant="ghost" size="sm" onClick={onBack}>← 接口</Button>
        <span className="font-mono">{api.path}</span>
        <Badge variant={stage === "release" ? "default" : "outline"} className="ml-auto font-mono text-[10px]">
          {stage}
        </Badge>
      </nav>

      {/* 详情头 */}
      <div className="rounded-xl border border-border/80 bg-card p-6 shadow-sm">
        <div className="flex flex-wrap items-center gap-3">
          <MethodBadge method={api.method} />
          <span className="font-mono text-base">{api.path}</span>
          {api.tag && <Badge variant="outline" className="ml-auto text-muted-foreground">{api.tag}</Badge>}
        </div>
        <p className="mt-2 text-[13.5px] text-muted-foreground">{op.description ?? op.summary ?? api.summary ?? "（无描述）"}</p>
        <div className="mt-5 flex flex-wrap items-center gap-x-6 gap-y-2 border-t border-border/60 pt-4 text-[13px] text-muted-foreground">
          <span>归属仓：<b className="font-mono text-foreground">{repo ? repoDisplay(repo.url) : `#${api.repoId}`}</b></span>
          {repo?.branch && <span>分支：<b className="font-mono text-foreground">{repo.branch}</b></span>}
          {repo && <span>同步：<b className={repo.syncStatus === "ok" ? "text-green-600 dark:text-green-400" : "text-amber-600 dark:text-amber-400"}>{repo.syncStatus}</b></span>}
          {latestVersion && <span>specHash：<b className="font-mono text-foreground">{latestVersion.specHash.slice(0, 12)}…</b></span>}
          <div className="ml-auto flex items-center gap-2">
            {/* I6 发布：draft 档 + 可写 + 关联 spec 文件 */}
            {stage === "draft" && canWrite && api.specPath && (
              <Button size="sm" variant="outline" onClick={() => void publish()} disabled={publishing || latestVersion?.stage === "release"}>
                {publishing ? "发布中…" : latestVersion?.stage === "release" ? "已发布 release" : "发布为 release"}
              </Button>
            )}
            <Button size="sm" onClick={() => canWrite && onEdit(api)} disabled={!canWrite} title={!canWrite ? (stage === "release" ? "release 为只读消费视图，编辑请切到 draft" : "未登录（只读）") : undefined}>
              {canWrite ? "编辑此文件" : stage === "release" ? "切 draft 可编辑" : "登录后可编辑"}
            </Button>
          </div>
        </div>
      </div>

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="contract">契约</TabsTrigger>
          <TabsTrigger value="provenance">溯源</TabsTrigger>
          <TabsTrigger value="debug">交互</TabsTrigger>
          <TabsTrigger value="comments">评论{comments.length > 0 ? ` (${comments.length})` : ""}</TabsTrigger>
        </TabsList>

        {/* ① 契约 */}
        <TabsContent value="contract" className="mt-5 flex flex-col gap-5">
          <OperationContract op={op} />
        </TabsContent>

        {/* ② 溯源：可信表达（specHash / 版本 / 变更记录） */}
        <TabsContent value="provenance" className="mt-5 flex flex-col gap-5">
          <div className="rounded-lg border bg-card p-5">
            <h3 className="mb-3 text-sm font-semibold">溯源</h3>
            <div className="overflow-hidden rounded-md border">
              {[
                ["归属仓", repo ? repoDisplay(repo.url) : `#${api.repoId}`],
                ["branch", repo?.branch ?? "—"],
                ["同步状态", repo?.syncStatus ?? "—"],
                ["method+path", `${api.method} ${api.path}`],
                ["spec 文件", api.specPath ?? "—"],
                ["档位", stage],
                ["最新 specHash", latestVersion?.specHash ?? "—"],
                ["发布状态", latestVersion ? (latestVersion.stage === "release" ? "已发布 release" : "draft（未发布）") : "—"],
              ].map(([k, v], i) => (
                <div key={k} className={`grid grid-cols-[140px_1fr] text-[13px] ${i ? "border-t" : ""}`}>
                  <div className="bg-muted/40 px-3 py-2 font-mono text-muted-foreground">{k}</div>
                  <div className="break-all px-3 py-2 font-mono">{v}</div>
                </div>
              ))}
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              {versions.map((v) => (
                <Badge key={v.id} variant={v.stage === "release" ? "default" : "outline"} className="font-mono text-[10px]">
                  #{v.id} {v.stage} {v.specHash.slice(0, 8)}
                </Badge>
              ))}
              {versions.length === 0 && <span className="text-[12px] text-muted-foreground">暂无版本记录</span>}
            </div>
            <Button size="sm" className="mt-4" onClick={() => window.open(repo?.url, "_blank")} disabled={!repo}>打开仓库 →</Button>
          </div>

          {log.length > 0 && (
            <div className="rounded-lg border bg-card p-5">
              <h3 className="mb-3 text-sm font-semibold">变更记录</h3>
              <div className="flex flex-col gap-1.5">
                {log.map((l) => (
                  <div key={l.id} className="flex items-baseline gap-2 rounded border bg-muted/20 px-3 py-1.5 text-[12.5px]">
                    <Badge variant="outline" className="shrink-0 font-mono text-[10px]">{l.action}</Badge>
                    <span className="shrink-0 text-muted-foreground">{l.actor}</span>
                    <span className="min-w-0 flex-1 truncate">{l.detail}</span>
                    <span className="shrink-0 font-mono text-[11px] text-muted-foreground">{l.createdAt ?? ""}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </TabsContent>

        {/* ③ 交互：Try it out（I1） + Mock 预览（I2/I3） */}
        <TabsContent value="debug" className="mt-5 flex flex-col gap-5">
          <TryPanel apiId={api.id} method={api.method} path={api.path} op={op} />
          <MockPanel apiId={api.id} repoId={api.repoId} path={api.path} canWrite={stage === "draft" && canWrite} />
        </TabsContent>

        {/* ④ 评论（I5） */}
        <TabsContent value="comments" className="mt-5 flex flex-col gap-4">
          <div className="rounded-lg border bg-card p-5">
            <h3 className="mb-3 text-sm font-semibold">评论（{comments.length}）</h3>
            {commentMsg && <p className="mb-2 text-[12px] text-muted-foreground">{commentMsg}</p>}
            <div className="flex flex-col gap-2">
              {comments.map((c) => (
                <div key={c.id} className="rounded-md border bg-muted/20 px-3 py-2">
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-[12.5px] font-medium">{c.author}</span>
                    <span className="font-mono text-[11px] text-muted-foreground">{c.createdAt ?? ""}</span>
                    {meUsername != null && c.author === meUsername && (
                      <button className="ml-auto text-[11px] text-muted-foreground hover:text-destructive" onClick={() => void delComment(c)}>删除</button>
                    )}
                  </div>
                  <p className="mt-1 whitespace-pre-wrap text-[13px] text-foreground/90">{c.body}</p>
                </div>
              ))}
              {comments.length === 0 && <p className="py-2 text-[12.5px] text-muted-foreground">暂无评论</p>}
            </div>

            {canComment ? (
              <div className="mt-4 flex flex-col gap-2">
                <Textarea
                  value={commentBody}
                  onChange={(e) => setCommentBody(e.target.value)}
                  rows={3}
                  placeholder="对这条接口契约的疑问 / 补充 / 变更说明…"
                  className="text-[13px]"
                />
                <div className="flex items-center gap-2">
                  <Button size="sm" variant="outline" onClick={() => void postComment()} disabled={!commentBody.trim()}>发表评论</Button>
                </div>
              </div>
            ) : (
              <p className="mt-3 text-[12px] text-muted-foreground">留言需要登录（账号菜单登录后可见评论区）。</p>
            )}
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
}