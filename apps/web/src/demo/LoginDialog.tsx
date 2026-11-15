import { useState } from "react";
import { KeyRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { login, setAdminToken, type AuthStatus } from "@/lib/api";

/**
 * I4 登录对话框（由 H6 admin-token 占位升级为「用户名 + 令牌」）。
 * 用户名留空 = 仅令牌（兼容 admin-token 直登）；普通用户输入平台发放的用户名 + 一次性令牌。
 */
export function LoginDialog({
  open, onOpenChange, onAuthed,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAuthed: (status: AuthStatus) => void;
}) {
  const [username, setUsername] = useState("");
  const [token, setToken] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit() {
    if (!token.trim()) { setErr("请输入令牌"); return; }
    setBusy(true); setErr(null);
    try {
      const status = await login(token.trim(), username.trim() || undefined);
      setAdminToken(token.trim());
      onAuthed(status);
      setToken("");
      setUsername("");
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
            平台管理员：用户名 <code className="font-mono">admin</code> + 令牌（<code className="font-mono">SPECHUB_ADMIN_TOKEN</code>）。
            用户名留空则仅用令牌（兼容旧版 admin-token）。
          </DialogDescription>
        </DialogHeader>

        {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}

        <div className="flex flex-col gap-2">
          <Input
            placeholder="用户名（平台 admin 为 admin；可留空仅令牌）"
            value={username} onChange={(e) => setUsername(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") void submit(); }}
            autoComplete="username"
          />
          <Input
            type="password" placeholder="令牌"
            value={token} onChange={(e) => setToken(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") void submit(); }}
            autoComplete="current-password"
            autoFocus
          />
        </div>

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