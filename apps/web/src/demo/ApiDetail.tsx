import { useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { MethodBadge } from "./method";
import { getApi, repoDisplay, type Api, type Repo } from "@/lib/api";
import { OperationContract, type SpecOp } from "./contract";

export function ApiDetail({ id, onBack, repos, canWrite, onEdit }: { id: number; onBack: () => void; repos: Repo[]; canWrite: boolean; onEdit: (api: Api) => void }) {
  const [api, setApi] = useState<Api | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [tab, setTab] = useState("contract");

  useEffect(() => {
    let alive = true;
    getApi(id).then((a) => { if (alive) { setApi(a); setErr(null); } }).catch((e) => alive && setErr(String(e)));
    return () => { alive = false; };
  }, [id]);

  const op: SpecOp | null = useMemo(() => {
    if (!api) return null;
    try { return JSON.parse(api.specJson) as SpecOp; } catch { return null; }
  }, [api]);

  const repo = api ? repos.find((r) => r.id === api.repoId) : undefined;

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
          <Button size="sm" className="ml-auto" onClick={() => canWrite && onEdit(api)} disabled={!canWrite} title={canWrite ? undefined : "未登录（只读）"}>
            {canWrite ? "编辑此文件" : "登录后可编辑"}
          </Button>
        </div>
      </div>

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="contract">契约</TabsTrigger>
          <TabsTrigger value="provenance">溯源</TabsTrigger>
          <TabsTrigger value="debug">交互</TabsTrigger>
        </TabsList>

        {/* ① 契约 */}
        <TabsContent value="contract" className="mt-5 flex flex-col gap-5">
          <OperationContract op={op} />
        </TabsContent>

        {/* ② 溯源 */}
        <TabsContent value="provenance" className="mt-5 rounded-lg border bg-card p-5">
          <h3 className="mb-3 text-sm font-semibold">溯源</h3>
          <div className="overflow-hidden rounded-md border">
            {[
              ["归属仓", repo ? repoDisplay(repo.url) : `#${api.repoId}`],
              ["branch", repo?.branch ?? "—"],
              ["同步状态", repo?.syncStatus ?? "—"],
              ["method+path", `${api.method} ${api.path}`],
              ["spec 文件", api.specPath ?? "—"],
              ["specHash", "（P2 后端扩展 §6.2 后提供）"],
            ].map(([k, v], i) => (
              <div key={k} className={`grid grid-cols-[140px_1fr] text-[13px] ${i ? "border-t" : ""}`}>
                <div className="bg-muted/40 px-3 py-2 font-mono text-muted-foreground">{k}</div>
                <div className="px-3 py-2 font-mono">{v}</div>
              </div>
            ))}
          </div>
          <Button size="sm" className="mt-4" onClick={() => window.open(repo?.url, "_blank")} disabled={!repo}>打开仓库 →</Button>
        </TabsContent>

        {/* ③ 交互（P2 占位） */}
        <TabsContent value="debug" className="mt-5 rounded-lg border border-dashed bg-card p-5">
          <h3 className="text-sm font-semibold">Try it out <Badge variant="secondary" className="ml-1 font-mono text-[10px]">P2</Badge></h3>
          <p className="mt-1 text-[13px] text-muted-foreground">在线调试、Mock 预览在 P2 I1/I3 落地；此处为占位骨架。</p>
        </TabsContent>
      </Tabs>
    </div>
  );
}