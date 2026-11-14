import { useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { MethodBadge } from "./method";
import { getApi, repoDisplay, type Api, type Repo } from "@/lib/api";

type SchemaNode = Record<string, unknown> & { type?: string; $ref?: string; properties?: Record<string, SchemaNode>; items?: SchemaNode; description?: string; enum?: unknown[]; format?: string };

/** 递归渲染 OpenAPI schema（specJson 前端直出，H3） */
function SchemaView({ node, depth = 0 }: { node?: SchemaNode | null; depth?: number }) {
  if (!node) return null;
  if (node.$ref) {
    return <span className="font-mono italic text-violet-600 dark:text-violet-400">{node.$ref}</span>;
  }
  if (node.type === "array") {
    return (
      <div className="py-1">
        <span className="font-mono text-muted-foreground">array&lt;</span>
        <SchemaView node={node.items} depth={depth} />
        <span className="font-mono text-muted-foreground">&gt;</span>
      </div>
    );
  }
  if (node.type === "object" && node.properties) {
    if (depth > 3) return <span className="font-mono text-muted-foreground">object…</span>;
    return (
      <div className={depth ? "ml-4 border-l border-border pl-3" : ""}>
        {Object.entries(node.properties).map(([k, v]) => (
          <div key={k} className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 py-0.5">
            <span className="font-mono text-[12.5px]">{k}</span>
            <span className="font-mono text-[12px] text-muted-foreground">{v.type ?? (v.$ref ? "ref" : "*")}{v.enum ? ` · enum[${v.enum.join("|")}]` : ""}</span>
            {typeof v.description === "string" && <span className="font-sans text-[12px] text-muted-foreground">— {v.description}</span>}
            {v.type === "object" && v.properties && <SchemaView node={v} depth={depth + 1} />}
            {v.type === "array" && v.items && <SchemaView node={v} depth={depth + 1} />}
          </div>
        ))}
      </div>
    );
  }
  return (
    <span className="py-1 font-mono text-[12px] text-muted-foreground">
      {node.type ?? "*"}
      {node.format ? `·${node.format}` : ""}
      {node.enum ? ` · enum[${node.enum.join("|")}]` : ""}
      {typeof node.description === "string" ? <span className="ml-2 font-sans text-muted-foreground">— {node.description}</span> : null}
    </span>
  );
}

interface SpecOp {
  summary?: string;
  description?: string;
  parameters?: any[]; // OpenAPI 参数字段表（name/in/schema/required/description…）形状不一，放宽
  responses?: Record<string, { description?: string; content?: Record<string, { schema?: SchemaNode }>; schema?: SchemaNode }>;
}

export function ApiDetail({ id, onBack, repos }: { id: number; onBack: () => void; repos: Repo[] }) {
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
  const responses = op?.responses ? Object.entries(op.responses) : [];

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
          <Button size="sm" className="ml-auto">编辑此文件（H4）</Button>
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
          <div className="rounded-lg border bg-card p-5">
            <h3 className="mb-3 text-sm font-semibold">Parameters <span className="ml-1 text-xs font-normal text-muted-foreground">{(op.parameters ?? []).length}</span></h3>
            {op.parameters?.length ? (
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    {["名称", "位置", "类型", "必需", "说明"].map((h) => (
                      <TableHead key={h} className="text-[11px] font-semibold uppercase text-muted-foreground">{h}</TableHead>
                    ))}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {op.parameters.map((p) => (
                    <TableRow key={String(p.name)}>
                      <TableCell className="font-mono">{String(p.name)}</TableCell>
                      <TableCell className="text-muted-foreground">{String(p["in"] ?? "")}</TableCell>
                      <TableCell className="font-mono text-muted-foreground">{String((p.schema as SchemaNode | undefined)?.type ?? "") || (!p.schema ? (p.type as string) ?? "*" : "")}</TableCell>
                      <TableCell>{p.required ? <span className="font-mono text-destructive">*</span> : <span className="text-muted-foreground">—</span>}</TableCell>
                      <TableCell className="text-muted-foreground">{typeof p.description === "string" ? p.description : ""}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <p className="py-3 text-sm text-muted-foreground">无参数</p>
            )}
          </div>

          <div className="rounded-lg border bg-card p-5">
            <h3 className="mb-3 text-sm font-semibold">Responses <span className="ml-1 text-xs font-normal text-muted-foreground">{responses.length}</span></h3>
            <div className="flex flex-col gap-4">
              {responses.map(([code, r]) => {
                const schema = r.content?.["application/json"]?.schema ?? r.schema;
                return (
                  <div key={code} className="rounded-md border border-border/70">
                    <div className="flex items-center gap-2 border-b border-border/60 px-4 py-2">
                      <span className="rounded bg-emerald-500/15 px-1.5 font-mono text-[11px] font-semibold text-emerald-600 dark:text-emerald-400">{code}</span>
                      <span className="text-[12.5px] text-muted-foreground">{r.description ?? ""}</span>
                    </div>
                    {schema && (
                      <div className="px-4 py-2.5">
                        <SchemaView node={schema} />
                      </div>
                    )}
                  </div>
                );
              })}
              {responses.length === 0 && <p className="py-3 text-sm text-muted-foreground">无响应定义</p>}
            </div>
          </div>
        </TabsContent>

        {/* ② 溯源 */}
        <TabsContent value="provenance" className="mt-5 rounded-lg border bg-card p-5">
          <h3 className="mb-3 text-sm font-semibold">溯源</h3>
          <div className="overflow-hidden rounded-md border">
            {[
              ["归属仓", repo ? repoDisplay(repo.url) : `#${api.repoId}`],
              ["分支", repo?.branch ?? "—"],
              ["同步状态", repo?.syncStatus ?? "—"],
              ["method+path", `${api.method} ${api.path}`],
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