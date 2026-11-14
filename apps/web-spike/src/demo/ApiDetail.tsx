import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { MethodBadge } from "./method";
import { APIs, PROVENANCE } from "./data";

export function ApiDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const api = APIs.find((a) => a.id === id) ?? APIs[0];
  const [tab, setTab] = useState("contract");

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
          <Badge variant="secondary" className="font-mono text-muted-foreground">v{api.version}</Badge>
          <Badge variant="outline" className="ml-auto text-muted-foreground">{api.tag}</Badge>
        </div>
        <p className="mt-2 text-[13.5px] text-muted-foreground">{api.desc}</p>
        <div className="mt-5 flex flex-wrap items-center gap-x-6 gap-y-2 border-t border-border/60 pt-4 text-[13px] text-muted-foreground">
          <span>归属仓：<b className="font-mono text-foreground">{api.repo}</b></span>
          <span>specHash：<b className="font-mono text-foreground">3f2a9c1b6e</b></span>
          <span>更新于 <b className="text-foreground">2 天前</b></span>
          <Button size="sm" className="ml-auto">编辑此文件</Button>
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
            <h3 className="mb-3 text-sm font-semibold">Parameters <span className="ml-1 font-normal text-xs text-muted-foreground">{api.params.length}</span></h3>
            {api.params.length ? (
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    {["名称", "位置", "类型", "必需", "说明"].map((h) => (
                      <TableHead key={h} className="text-[11px] font-semibold uppercase text-muted-foreground">{h}</TableHead>
                    ))}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {api.params.map((p) => (
                    <TableRow key={p.name}>
                      <TableCell className="font-mono">{p.name}</TableCell>
                      <TableCell className="text-muted-foreground">{p.in}</TableCell>
                      <TableCell className="font-mono text-muted-foreground">{p.type}</TableCell>
                      <TableCell>{p.required ? <span className="font-mono text-destructive">*</span> : <span className="text-muted-foreground">—</span>}</TableCell>
                      <TableCell className="text-muted-foreground">{p.desc}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <p className="py-4 text-sm text-muted-foreground">无参数</p>
            )}
          </div>

          <div className="rounded-lg border bg-card p-5">
            <h3 className="mb-3 text-sm font-semibold">Responses <span className="ml-1 font-normal text-xs text-muted-foreground">{api.responses.length}</span></h3>
            <div className="flex flex-col gap-4">
              {api.responses.map((r) => (
                <div key={r.code} className="rounded-md border border-border/70">
                  <div className="flex items-center gap-2 border-b border-border/60 px-4 py-2">
                    <span className="rounded bg-emerald-500/15 font-mono text-[11px] font-semibold text-emerald-600 dark:text-emerald-400">{r.code}</span>
                    <span className="text-[12.5px] text-muted-foreground">{r.desc}</span>
                  </div>
                  {r.props.length > 0 && (
                    <div className="px-4 py-2">
                      <span className="font-mono text-[12.5px] text-violet-600 dark:text-violet-400">object</span>
                      <div className="mt-1 flex flex-col divide-y divide-border/50">
                        {r.props.map((p) => (
                          <div key={p.name} className="flex items-baseline gap-2 py-1.5 font-mono text-[12.5px]">
                            <span>{p.name}</span>
                            <span className="text-muted-foreground">{p.type}</span>
                            {p.doc && <span className="flex-1 truncate font-sans text-muted-foreground">{p.doc}</span>}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        </TabsContent>

        {/* ② 溯源 */}
        <TabsContent value="provenance" className="mt-5 rounded-lg border bg-card p-5">
          <h3 className="mb-3 text-sm font-semibold">溯源</h3>
          <div className="overflow-hidden rounded-md border">
            {PROVENANCE.map(([k, v], i) => (
              <div key={k} className={`grid grid-cols-[140px_1fr] text-[13px] ${i ? "border-t" : ""}`}>
                <div className="bg-muted/40 px-3 py-2 font-mono text-muted-foreground">{k}</div>
                <div className="px-3 py-2 font-mono">{v}</div>
              </div>
            ))}
          </div>
          <Button size="sm" className="mt-4">查看 diff →</Button>
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