import { useEffect, useState } from "react";
import { ChevronDown, ChevronRight, KeyRound, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { ApiError, loginWithPassword, loginWithToken, type LoginResult } from "@/lib/api";

/** dev 构建可预填 admin-token（`VITE_DEV_ADMIN_TOKEN`）；生产构建中该变量不注入。 */
const DEV_TOKEN = import.meta.env.DEV ? (import.meta.env.VITE_DEV_ADMIN_TOKEN ?? "") : "";

/**
 * 阶段 C 登录对话框：
 * - 默认：用户名 / 邮箱 + 密码（Spring Session + CSRF，成功后不写 localStorage）；
 * - OIDC 启用时显示「使用 SSO 登录」，走同源 `/oauth2/authorization/...`；
 * - 「使用令牌登录」为兼容高级选项（admin-token / 用户 API 令牌），同样换取真实会话。
 */
export function LoginDialog({
  open, onOpenChange, onAuthed, oidcEnabled, oidcLoginUrl, initialError,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAuthed: (result: LoginResult) => void;
  oidcEnabled?: boolean;
  oidcLoginUrl?: string | null;
  initialError?: string | null;
}) {
  const [identifier, setIdentifier] = useState("");
  const [password, setPassword] = useState("");
  const [token, setToken] = useState(DEV_TOKEN);
  const [tokenUsername, setTokenUsername] = useState("");
  const [advanced, setAdvanced] = useState(DEV_TOKEN !== "");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setErr(initialError ?? null);
    if (DEV_TOKEN) setAdvanced(true);
  }, [open, initialError]);

  /** 统一错误展示：不区分用户不存在 / 密码错误；429 单独提示。 */
  function describe(e: unknown): string {
    if (e instanceof ApiError) {
      if (e.status === 429) return "尝试过于频繁，请稍后再试";
      return e.message;
    }
    return String(e);
  }

  async function submitPassword() {
    if (!identifier.trim() || !password) { setErr("请输入用户名 / 邮箱和密码"); return; }
    setBusy(true); setErr(null);
    try {
      const result = await loginWithPassword(identifier.trim(), password);
      setPassword("");
      onAuthed(result);
    } catch (e) {
      setErr(describe(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitToken() {
    if (!token.trim()) { setErr("请输入令牌"); return; }
    setBusy(true); setErr(null);
    try {
      const result = await loginWithToken(token.trim(), tokenUsername);
      setToken(DEV_TOKEN); // 兼容令牌仅本次登录使用，不持久化
      onAuthed(result);
    } catch (e) {
      setErr(describe(e));
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
            使用用户名或邮箱与密码登录。登录态由 HttpOnly 会话 Cookie 承载，浏览器不保存长期令牌。
          </DialogDescription>
        </DialogHeader>

        {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}

        <div className="flex flex-col gap-2">
          <Input
            placeholder="用户名或邮箱"
            value={identifier}
            onChange={(e) => setIdentifier(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") void submitPassword(); }}
            autoComplete="username"
            autoFocus
          />
          <Input
            type="password"
            placeholder="密码"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") void submitPassword(); }}
            autoComplete="current-password"
          />
        </div>

        {oidcEnabled && oidcLoginUrl && (
          <Button
            variant="outline"
            className="w-full"
            disabled={busy}
            onClick={() => window.location.assign(oidcLoginUrl)}
          >
            <ShieldCheck className="size-4" /> 使用 SSO 登录
          </Button>
        )}

        <div className="border-t border-border/60 pt-2">
          <button
            type="button"
            className="flex w-full items-center gap-1 text-left text-[12px] text-muted-foreground hover:text-foreground"
            onClick={() => setAdvanced((v) => !v)}
            aria-expanded={advanced}
          >
            {advanced ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />}
            使用令牌登录（兼容 admin-token / 用户 API 令牌）
          </button>
          {advanced && (
            <div className="mt-2 flex flex-col gap-2">
              <Input
                placeholder="用户名（admin-token 时填 admin；可留空）"
                value={tokenUsername}
                onChange={(e) => setTokenUsername(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") void submitToken(); }}
                autoComplete="username"
                className="font-mono text-[12.5px]"
              />
              <Input
                type="password"
                placeholder="令牌"
                value={token}
                onChange={(e) => setToken(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") void submitToken(); }}
                autoComplete="off"
                className="font-mono text-[12.5px]"
              />
              <Button variant="outline" size="sm" onClick={() => void submitToken()} disabled={busy || !token.trim()}>
                令牌登录
              </Button>
              <p className="text-[11px] text-muted-foreground">令牌只用于本次登录换取会话，不会写入 localStorage。</p>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>取消</Button>
          <Button onClick={() => void submitPassword()} disabled={busy || !identifier.trim() || !password}>
            {busy ? "校验中…" : "登录"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
