import { useEffect, useState } from "react";
import { Boxes, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { registerRepo, type Project } from "@/lib/api";

/**
 * 阶段 A 仓库注册：必须归属当前项目（projectId 由外部传入，表单可切换）。
 * token 仅随请求发送、后端只存内存（不落库）。
 */
export function RepoRegisterDialog({
  open,
  onOpenChange,
  projects,
  projectId,
  onRegistered,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projects: Project[];
  projectId: number | null;
  onRegistered: (msg: string) => void;
}) {
  const [pid, setPid] = useState<number | null>(projectId);
  const [url, setUrl] = useState("");
  const [branch, setBranch] = useState("main");
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setPid(projectId);
    setUrl("");
    setBranch("main");
    setToken("");
    setBusy(false);
    setErr(null);
  }, [open, projectId]);

  async function submit() {
    if (pid == null) { setErr("请选择归属项目"); return; }
    if (!url.trim()) { setErr("仓库地址不能为空"); return; }
    setBusy(true);
    setErr(null);
    try {
      const r = await registerRepo({
        projectId: pid,
        url: url.trim(),
        branch: branch.trim() || "main",
        token: token.trim() || undefined,
      });
      onRegistered(`仓库已注册：${r.apiCount} 个接口 · ${r.schemaCount} 个 schema`);
      onOpenChange(false);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Boxes className="size-4" /> 注册仓库</DialogTitle>
          <DialogDescription>
            仓库必须归属项目；注册后平台按 active 真源摄取 spec（specs/ 或 openapi/swagger 命名文件）。
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-[12.5px]">
            <span className="text-muted-foreground">归属项目</span>
            <select
              value={pid ?? ""}
              onChange={(e) => setPid(e.target.value ? Number(e.target.value) : null)}
              disabled={busy}
              className="h-9 rounded-md border border-input bg-transparent px-3 text-[13px] outline-none focus-visible:border-ring"
            >
              <option value="">请选择项目…</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>{p.name}{p.archived ? "（已归档）" : ""}</option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-[12.5px]">
            <span className="text-muted-foreground">Git 地址（https:// 或 file://）</span>
            <Input value={url} onChange={(e) => setUrl(e.target.value)} disabled={busy} placeholder="https://github.com/org/specs.git" className="font-mono text-[12.5px]" />
          </label>
          <div className="grid grid-cols-2 gap-3">
            <label className="flex flex-col gap-1 text-[12.5px]">
              <span className="text-muted-foreground">分支</span>
              <Input value={branch} onChange={(e) => setBranch(e.target.value)} disabled={busy} className="font-mono text-[12.5px]" />
            </label>
            <label className="flex flex-col gap-1 text-[12.5px]">
              <span className="text-muted-foreground">访问 token（可选，不落库）</span>
              <Input type="password" value={token} onChange={(e) => setToken(e.target.value)} disabled={busy} placeholder="仅本次会话内存" />
            </label>
          </div>
          {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}
        </div>

        <DialogFooter>
          <Button variant="outline" size="sm" onClick={() => onOpenChange(false)} disabled={busy}>取消</Button>
          <Button size="sm" onClick={submit} disabled={busy || !url.trim() || pid == null}>
            <Plus className="size-3.5" /> 注册并摄取
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
