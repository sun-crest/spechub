import { useState } from "react";
import { Search, ChevronRight } from "lucide-react";
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { MethodBadge } from "./method";
import { APIs, DOCS } from "./data";

const ALL = "全部";

export function ApiList({
  tag,
  onTagChange,
  onSelect,
}: {
  tag: string;
  onTagChange: (t: string) => void;
  onSelect: (id: string) => void;
}) {
  const [q, setQ] = useState("");
  const tags = [ALL, ...Array.from(new Set(APIs.map((a) => a.tag)))];
  const rows = APIs.filter(
    (a) =>
      (tag === ALL || a.tag === tag) &&
      (!q || (a.method + a.path + a.summary + a.tag).toLowerCase().includes(q.toLowerCase())),
  );
  const groupCount = new Set(APIs.map((a) => a.tag)).size;

  return (
    <div className="flex h-full flex-col gap-6 overflow-auto p-8">
      <div className="flex items-center gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">接口</h1>
        <Badge variant="secondary" className="font-mono text-muted-foreground">release · v4 · 3f2a9c1b</Badge>
        <Badge variant="outline" className="ml-1 hidden text-muted-foreground sm:inline-flex">Azure · {APIs.length} 端点</Badge>
        <Button size="sm" className="ml-auto">
          <span className="text-base leading-none">＋</span>
          新建 / 编辑 spec
        </Button>
      </div>

      <div className="flex flex-wrap gap-x-10 gap-y-2">
        {[
          [APIs.length, "接口"], [2, "文档仓库"], [groupCount, "分组"], [DOCS.length, "AI 文档"], ["+12", "本 7 天新增"],
        ].map(([v, label]) => (
          <div key={String(label)} className="flex items-baseline gap-2">
            <span className="text-2xl font-semibold tracking-tight tabular-nums">{v}</span>
            <span className="text-[13px] text-muted-foreground">{label}</span>
          </div>
        ))}
      </div>

      {/* Tag 过滤 */}
      <div className="flex flex-wrap items-center gap-1.5">
        {tags.map((t) => (
          <button
            key={t}
            onClick={() => onTagChange(t)}
            className={`rounded-full border px-3 py-1 text-[12.5px] transition-colors ${
              tag === t
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
        <Input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="按方法 / 路径 / 摘要过滤…"
          className="bg-muted/60 pl-9 focus-visible:bg-background"
        />
      </div>

      <Table className="min-w-[820px] flex-1">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            {["接口", "摘要", "分组", "归属仓", "版本", ""].map((h) => (
              <TableHead key={h} className="text-[11px] font-semibold uppercase text-muted-foreground">{h}</TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((a) => (
            <TableRow key={a.id} className="cursor-pointer" onClick={() => onSelect(a.id)} aria-label={`查看 ${a.method} ${a.path}`}>
              <TableCell>
                <span className="flex items-center gap-2.5">
                  <MethodBadge method={a.method} />
                  <span className="font-mono text-[13px] text-link">{a.path}</span>
                </span>
              </TableCell>
              <TableCell className="max-w-[320px] text-[13.5px]">
                <div className="truncate">{a.summary}</div>
              </TableCell>
              <TableCell>
                <Badge variant="outline" className="text-muted-foreground">{a.tag}</Badge>
              </TableCell>
              <TableCell className="text-muted-foreground">{a.repo}</TableCell>
              <TableCell>
                <Badge variant="secondary" className="font-mono text-muted-foreground">{a.version}</Badge>
              </TableCell>
              <TableCell className="text-muted-foreground"><ChevronRight className="size-4" /></TableCell>
            </TableRow>
          ))}
          {rows.length === 0 && (
            <TableRow>
              <TableCell colSpan={6} className="py-14 text-center text-sm text-muted-foreground">
                没有匹配的接口，换个关键词试试
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
}