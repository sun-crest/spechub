import { useCallback, useEffect, useState } from "react";
import { ClipboardList, RefreshCw, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from "@/components/ui/table";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { listAudit, type AuditEvent } from "@/lib/api";

const PAGE_SIZE = 50;

const fmt = (v?: string | null) => {
  if (!v) return "—";
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : d.toLocaleString();
};

const toInstant = (local: string): string | undefined => {
  if (!local) return undefined;
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
};

/**
 * 阶段 C：管理动作审计（仅 org_admin 显示入口，后端同样强制）。
 * 过滤：操作者 / 动作 / 目标类型 / 结果 / 时间范围；分页 limit 50。
 */
export function AuditDialog({ open, onOpenChange }: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [actor, setActor] = useState("");
  const [action, setAction] = useState("");
  const [targetType, setTargetType] = useState("");
  const [outcome, setOutcome] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [offset, setOffset] = useState(0);

  const run = useCallback((nextOffset: number) => {
    setBusy(true); setErr(null);
    void listAudit({
      actor: actor || undefined,
      action: action || undefined,
      targetType: targetType || undefined,
      outcome: outcome || undefined,
      from: toInstant(from),
      to: toInstant(to),
      limit: PAGE_SIZE,
      offset: nextOffset,
    })
      .then((list) => {
        setEvents(list);
        setOffset(nextOffset);
      })
      .catch((e) => setErr(String(e)))
      .finally(() => setBusy(false));
  }, [actor, action, targetType, outcome, from, to]);

  useEffect(() => {
    if (!open) return;
    setErr(null);
    run(0);
    // 打开时按当前过滤条件回到第一页
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><ClipboardList className="size-4" /> 审计日志</DialogTitle>
          <DialogDescription>
            组织内管理动作留痕（登录 / 禁用 / 会话 / 令牌 / 服务账号 / 项目 / 团队等），按时间倒序；仅 org_admin 可查。
          </DialogDescription>
        </DialogHeader>

        {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}

        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1 text-[11px] text-muted-foreground">
            操作者
            <Input value={actor} onChange={(e) => setActor(e.target.value)} placeholder="username" className="h-8 w-32 font-mono text-[12px]" />
          </label>
          <label className="flex flex-col gap-1 text-[11px] text-muted-foreground">
            动作
            <Input value={action} onChange={(e) => setAction(e.target.value)} placeholder="auth.login.success" className="h-8 w-40 font-mono text-[12px]" />
          </label>
          <label className="flex flex-col gap-1 text-[11px] text-muted-foreground">
            目标类型
            <Input value={targetType} onChange={(e) => setTargetType(e.target.value)} placeholder="user / token …" className="h-8 w-28 font-mono text-[12px]" />
          </label>
          <label className="flex flex-col gap-1 text-[11px] text-muted-foreground">
            结果
            <select
              value={outcome}
              onChange={(e) => setOutcome(e.target.value)}
              className="h-8 rounded-md border bg-background px-2 font-mono text-[12px]"
            >
              <option value="">全部</option>
              <option value="success">success</option>
              <option value="failure">failure</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-[11px] text-muted-foreground">
            开始
            <Input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} className="h-8 w-48 text-[12px]" />
          </label>
          <label className="flex flex-col gap-1 text-[11px] text-muted-foreground">
            结束
            <Input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} className="h-8 w-48 text-[12px]" />
          </label>
          <Button size="sm" onClick={() => run(0)} disabled={busy}>
            <Search className="size-3.5" /> 查询
          </Button>
          <Button size="icon" variant="ghost" className="size-8" title="刷新" onClick={() => run(offset)} disabled={busy}>
            <RefreshCw className="size-3.5" />
          </Button>
        </div>

        <div className="max-h-[55vh] overflow-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-40">时间</TableHead>
                <TableHead className="w-32">操作者</TableHead>
                <TableHead className="w-48">动作</TableHead>
                <TableHead className="w-40">目标</TableHead>
                <TableHead className="w-20">结果</TableHead>
                <TableHead className="w-28">IP</TableHead>
                <TableHead>摘要</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {events.map((e) => (
                <TableRow key={e.id}>
                  <TableCell className="font-mono text-[11.5px] text-muted-foreground">{fmt(e.createdAt)}</TableCell>
                  <TableCell className="font-mono text-[12px]">
                    {e.actorName}
                    <span className="ml-1 text-[10px] text-muted-foreground">({e.actorType})</span>
                  </TableCell>
                  <TableCell className="font-mono text-[11.5px]">{e.action}</TableCell>
                  <TableCell className="font-mono text-[11.5px] text-muted-foreground">
                    {e.targetType ?? "—"}{e.targetId ? `:${e.targetId}` : ""}
                  </TableCell>
                  <TableCell>
                    <Badge variant={e.outcome === "success" ? "default" : "destructive"} className="font-mono text-[10px]">
                      {e.outcome}
                    </Badge>
                  </TableCell>
                  <TableCell className="font-mono text-[11.5px] text-muted-foreground">{e.ip || "—"}</TableCell>
                  <TableCell className="max-w-[22rem] truncate text-[12px]" title={e.detail ?? ""}>{e.detail || "—"}</TableCell>
                </TableRow>
              ))}
              {events.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="py-8 text-center text-[12.5px] text-muted-foreground">
                    {busy ? "查询中…" : "没有匹配的审计记录"}
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>

        <DialogFooter className="sm:justify-between">
          <span className="text-[11.5px] text-muted-foreground">
            第 {Math.floor(offset / PAGE_SIZE) + 1} 页 · 每页 {PAGE_SIZE} 条
          </span>
          <div className="flex gap-2">
            <Button size="sm" variant="outline" disabled={busy || offset === 0} onClick={() => run(Math.max(0, offset - PAGE_SIZE))}>
              上一页
            </Button>
            <Button size="sm" variant="outline" disabled={busy || events.length < PAGE_SIZE} onClick={() => run(offset + PAGE_SIZE)}>
              下一页
            </Button>
            <Button size="sm" variant="outline" onClick={() => onOpenChange(false)}>关闭</Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
