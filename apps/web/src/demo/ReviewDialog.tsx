import { useCallback, useEffect, useState } from "react";
import { ClipboardCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { approveReview, getReview, listReviews, rejectReview, type Review, type ReviewDetail } from "@/lib/api";

const STATUS_COLOR: Record<string, "default" | "outline" | "secondary"> = {
  pending: "secondary", approved: "default", rejected: "outline",
};

/** I8 评审审批流：待审队列 + 详情（当前真源 vs 新内容两栏）+ 批准 / 打回 */
export function ReviewDialog({ open, onOpenChange, canReview }: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  maxWidth?: never;
  canReview: boolean;
}) {
  const [reviews, setReviews] = useState<Review[]>([]);
  const [selected, setSelected] = useState<ReviewDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reason, setReason] = useState<string>("");
  const [busy, setBusy] = useState(false);

  const load = useCallback((selId?: number | null) => {
    void listReviews("pending", undefined).then((rs) => {
      setReviews(rs);
      if (selId) { void getReview(selId).then(setSelected).catch(() => setSelected(null)); }
      else if (selected && rs.find((r) => r.id === selected.id)) { /* 保持当前选中 */ }
      else setSelected(null);
    }).catch((e) => setErr(String(e)));
  }, []);

  useEffect(() => {
    if (open) { load(); setNotice(null); setErr(null); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  async function openDetail(id: number) {
    setErr(null);
    try { setSelected(await getReview(id)); } catch (e) { setErr(String(e)); }
  }

  async function doApprove(id: number) {
    setBusy(true); setErr(null);
    try {
      await approveReview(id);
      setNotice("已批准：写入真源完成");
      setSelected(null);
      load();
    } catch (e) { setErr(String(e)); } finally { setBusy(false); }
  }

  async function doReject(id: number) {
    setBusy(true); setErr(null);
    try {
      await rejectReview(id, reason.trim() || undefined);
      setNotice("已打回（真源未变）");
      setReason(""); setSelected(null);
      load();
    } catch (e) { setErr(String(e)); } finally { setBusy(false); }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><ClipboardCheck className="size-4" /> 评审队列 · 写操作皆审</DialogTitle>
          <DialogDescription>spec 写回先经人评审：批准后落真源（git commit + push），打回则真源不动。</DialogDescription>
        </DialogHeader>
        {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}
        {notice && <div className="rounded-md border border-emerald-500/30 bg-emerald-500/10 px-3 py-2 text-[12.5px] text-emerald-700 dark:text-emerald-400">{notice}</div>}

        <div className="grid max-h-[64vh] grid-cols-[220px_1fr] gap-3 overflow-hidden">
          {/* 队列 */}
          <div className="flex flex-col gap-1 overflow-auto pr-1">
            {reviews.length === 0 && <p className="py-2 text-center text-[12px] text-muted-foreground">无待审提交</p>}
            {reviews.map((r) => (
              <button
                key={r.id}
                onClick={() => void openDetail(r.id)}
                className={`flex flex-col gap-0.5 rounded-md border px-2.5 py-2 text-left text-[12.5px] transition-colors ${
                  selected?.id === r.id ? "border-primary/40 bg-primary/5" : "hover:bg-muted"
                }`}
              >
                <span className="flex items-center gap-1.5">
                  <span className="font-mono text-[11px] text-muted-foreground">#{r.id}</span>
                  <Badge variant={STATUS_COLOR[r.status] ?? "outline"} className="font-mono text-[9px]">{r.status}</Badge>
                </span>
                <span className="truncate font-mono">{r.path}</span>
                <span className="text-[11px] text-muted-foreground">{r.requester}</span>
              </button>
            ))}
          </div>

          {/* 详情 */}
          <div className="flex min-w-0 flex-col gap-2">
            {!selected ? (
              <p className="py-6 text-center text-[12.5px] text-muted-foreground">← 选一条待审提交查看（旧内容 vs 新内容）</p>
            ) : (
              <>
                <div className="flex items-center gap-2 text-[12.5px]">
                  <span className="font-mono font-medium">{selected.path}</span>
                  <Badge variant="outline" className="ml-auto font-mono text-[10px]">#{selected.id} {selected.status}</Badge>
                </div>
                <div className="grid min-h-0 flex-1 grid-cols-2 gap-2">
                  <div className="flex min-h-0 flex-col overflow-hidden rounded border">
                    <div className="shrink-0 border-b bg-muted/40 px-2 py-1 text-[11px] font-medium text-muted-foreground">当前真源</div>
                    <pre className="min-h-0 flex-1 overflow-auto bg-card px-2 py-2 text-[11px]">{selected.currentContent ?? "（无法读取）"}</pre>
                  </div>
                  <div className="flex min-h-0 flex-col overflow-hidden rounded border">
                    <div className="shrink-0 border-b bg-muted/40 px-2 py-1 text-[11px] font-medium text-muted-foreground">待批复新内容</div>
                    <pre className="min-h-0 flex-1 overflow-auto bg-card px-2 py-2 text-[11px]">{selected.newContent}</pre>
                  </div>
                </div>
                {canReview && selected.status === "pending" && (
                  <div className="flex shrink-0 items-center gap-2 border-t border-border/60 pt-2">
                    <Textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={1}
                      placeholder="打回理由（可选）" className="min-h-9 flex-1 text-[12px]" />
                    <Button size="sm" disabled={busy} onClick={() => void doApprove(selected.id)}>批准</Button>
                    <Button size="sm" variant="outline" disabled={busy} onClick={() => void doReject(selected.id)}>打回</Button>
                  </div>
                )}
                {!canReview && selected.status === "pending" && (
                  <p className="shrink-0 border-t border-border/60 pt-2 text-[11.5px] text-muted-foreground">（需要 editor 及以上角色审批）</p>
                )}
              </>
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}