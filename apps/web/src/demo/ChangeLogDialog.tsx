import { useCallback, useEffect, useState } from "react";
import { History } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { listGlobalChangelog, type ChangelogEntry } from "@/lib/api";

/** I5 独立变更记录：全局 changelog 流（跨 repo，写操作留痕） */
export function ChangeLogDialog({ open, onOpenChange }: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [entries, setEntries] = useState<ChangelogEntry[]>([]);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(() => {
    void listGlobalChangelog(80).then(setEntries).catch((e) => setErr(String(e)));
  }, []);

  useEffect(() => {
    if (open) { load(); setErr(null); }
  }, [open, load]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><History className="size-4" /> 变更记录</DialogTitle>
          <DialogDescription>平台写操作留痕：spec 写回 / 发布 / Mock 规则 / 成员 / 评论等（最新在前）。</DialogDescription>
        </DialogHeader>
        {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}
        <div className="flex max-h-[60vh] flex-col gap-1.5 overflow-auto pr-1">
          {entries.length === 0 && <p className="py-4 text-center text-[12.5px] text-muted-foreground">暂无变更记录</p>}
          {entries.map((l) => (
            <div key={l.id} className="flex items-baseline gap-2 rounded border bg-muted/20 px-3 py-1.5 text-[12.5px]">
              <Badge variant={l.action === "spec.write" ? "default" : "outline"} className="shrink-0 font-mono text-[10px]">{l.action}</Badge>
              <span className="shrink-0 font-mono text-[12px] text-muted-foreground">{l.actor}</span>
              <span className="min-w-0 flex-1 break-words">{l.detail}</span>
              <span className="shrink-0 font-mono text-[11px] text-muted-foreground">{l.createdAt ?? ""}</span>
            </div>
          ))}
        </div>
        <div className="flex justify-end border-t border-border/60 pt-3">
          <Button variant="outline" size="sm" onClick={() => onOpenChange(false)}>关闭</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}