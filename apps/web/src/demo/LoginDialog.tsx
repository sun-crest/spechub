import { useState } from "react";
import { KeyRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { login, setAdminToken, type AuthStatus } from "@/lib/api";

/**
 * H6 登录对话框（admin token 占位，§7 O3；RBAC P2 I4 一起换）。
 * 提交 token → 后端校验（SPECHUB_ADMIN_TOKEN / --seed-admin 生成值）→ 存 localStorage → 全请求带 Bearer。
 */
export function LoginDialog({
  open, onOpenChange, onAuthed,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAuthed: (status: AuthStatus) => void;
}) {
  const [token, setToken] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit() {
    if (!token.trim()) { setErr("请输入 admin token"); return; }
    setBusy(true); setErr(null);
    try {
      const status = await login(token.trim());
      setAdminToken(token.trim());
      onAuthed(status);
      setToken("");
      onOpenChange(false);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><KeyRound className="size-4" /> 登录 SpecHub</DialogTitle>
          <DialogDescription>
            使用 admin 令牌（后端 <code className="font-mono">SPECHUB_ADMIN_TOKEN</code> 或
            <code className="font-mono">--seed-admin</code> 生成值）。占位实现，P2 换 RBAC。
          </DialogDescription>
        </DialogHeader>

        {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}

        <Input
          type="password" placeholder="粘贴 admin token"
          value={token} onChange={(e) => setToken(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") void submit(); }}
          autoFocus
        />

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>取消</Button>
          <Button onClick={() => void submit()} disabled={busy || !token.trim()}>
            {busy ? "校验中…" : "登录"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}