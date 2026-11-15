import { useMemo, useState } from "react";
import { Search, ChevronRight, RefreshCw } from "lucide-react";
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { MethodBadge } from "./method";
import { repoDisplay, type Api, type Repo } from "@/lib/api";

export interface Filter {
  repoId?: number;
  tag?: string;
}

const ALL = "全部";

export function ApiList({
  apis, repos, filter, onFilter, onSelect, onEdit, loading, error, onRetry,
}: {
  apis: Api[];
  repos: Repo[];
  filter: Filter;
  onFilter: (f: Filter) => void;
  onSelect: (id: number) => void;
  onEdit: () => void;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
}) {
  const [q, setQ] = useState("");

  const tags = useMemo(() => {
    const s = new Set<string>();
    for (const a of apis) if (a.tag) s.add(a.tag);
    return [ALL, ...s];
  }, [apis]);

  const rows = useMemo(() => {
    const ql = q.trim().toLowerCase();
    return apis.filter((a) => {
      if (filter.repoId && a.repoId !== filter.repoId) return false;
      if (filter.tag && a.tag !== filter.tag) return false;
      if (ql && !((a.method + a.path + " " + (a.summary ?? "") + " " + (a.tag ?? "")).toLowerCase().includes(ql))) return false;
      return true;
    });
  }, [apis, filter, q]);

  const repoName = (id: number) => repoDisplay(repos.find((r) => r.id === id)?.url ?? `#${id}`);

  return (
    <div className="flex h-full flex-col gap-6 overflow-auto p-8">
      <div className="flex items-center gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">接口</h1>
        <Badge variant="outline" className="text-muted-foreground">{rows.length} 命中</Badge>
        <Button size="sm" className="ml-auto" onClick={onEdit}>
          <span className="text-base leading-none">＋</span>
          新建 / 编辑 spec
        </Button>
      </div>

      <div className="flex flex-wrap gap-x-10 gap-y-2">
        {[
          [apis.length, "接口"], [repos.length, "文档仓库"], [tags.length - 1, "分组"], [rows.length, "当前过滤"],
        ].map(([v, label]) => (
          <div key={String(label)} className="flex items-baseline gap-2">
            <span className="text-2xl font-semibold tracking-tight tabular-nums">{v}</span>
            <span className="text-[13px] text-muted-foreground">{label}</span>
          </div>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-1.5">
        {tags.map((t) => (
          <button
            key={t}
            onClick={() => onFilter(t === ALL ? { repoId: filter.repoId } : { ...filter, tag: t === ALL ? undefined : t })}
            className={`rounded-full border px-3 py-1 text-[12.5px] transition-colors ${
              (t === ALL ? !filter.tag : filter.tag === t)
                ? "border-transparent bg-foreground text-background font-medium"
                : "border-border text-muted-foreground hover:border-foreground/30 hover:text-foreground"
            }`}
          >
            {t}
          </button>
        ))}
      </div>

      <div className="relative w-72">
        <Search className="absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
        <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="按方法 / 路径 / 摘要过滤…" className="bg-muted/60 pl-9 focus-visible:bg-background" />
      </div>

      {error && (
        <div className="flex items-center gap-3 rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-2.5 text-[13px] text-destructive">
          <span className="flex-1">后端不可达（{error}）——请确认 backend 在运行（docker compose up）</span>
          <Button size="sm" variant="outline" onClick={onRetry}><RefreshCw className="size-3.5" /> 重试</Button>
        </div>
      )}

      <Table className="min-w-[820px] flex-1">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            {["接口", "摘要", "分组", "归属仓", ""].map((h) => (
              <TableHead key={h} className="text-[11px] font-semibold uppercase text-muted-foreground">{h}</TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {loading && (
            <TableRow><TableCell colSpan={6} className="py-14 text-center text-sm text-muted-foreground">加载中…</TableCell></TableRow>
          )}
          {!loading && rows.map((a) => (
            <TableRow key={a.id} className="cursor-pointer" onClick={() => onSelect(a.id)} aria-label={`查看 ${a.method} ${a.path}`}>
              <TableCell>
                <span className="flex items-center gap-2.5">
                  <MethodBadge method={a.method} />
                  <span className="font-mono text-[13px] text-link">{a.path}</span>
                </span>
              </TableCell>
              <TableCell className="max-w-[320px] text-[13.5px]">
                <div className="truncate">{a.summary ?? "—"}</div>
              </TableCell>
              <TableCell>
                {a.tag ? <Badge variant="outline" className="text-muted-foreground">{a.tag}</Badge> : <span className="text-muted-foreground">—</span>}
              </TableCell>
              <TableCell className="text-muted-foreground">{repoName(a.repoId)}</TableCell>
              <TableCell className="text-muted-foreground"><ChevronRight className="size-4" /></TableCell>
            </TableRow>
          ))}
          {!loading && rows.length === 0 && (
            <TableRow>
              <TableCell colSpan={6} className="py-14 text-center text-sm text-muted-foreground">没有匹配的接口，换个关键词试试</TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
}