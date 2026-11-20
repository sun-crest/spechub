import { useCallback, useEffect, useState } from "react";
import { Copy, KeyRound, MonitorSmartphone, Plus, RefreshCw, ShieldCheck, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import {
  changePassword, createToken, listSessions, listTokens, revokeSession, revokeToken,
  type Me, type SessionView, type TokenView,
} from "@/lib/api";

type Tab = "profile" | "password" | "sessions" | "tokens";

const fmt = (v?: string | null) => {
  if (!v) return "—";
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : d.toLocaleString();
};

/**
 * 阶段 C「账号与安全」：
 * - 资料（用户名 / 邮箱 / 状态 / 组织角色 / OIDC 启用状态，只读）；
 * - 修改密码（改密后其它 Web 会话被吊销）；
 * - 活动会话（Spring Session；脱敏 id，可吊销非当前会话）；
 * - 我的令牌（前缀 / 名称 / 过期 / 最近使用；明文只显示一次）。
 *
 * `forcePasswordChange=true`（mustChangePassword）时锁定为改密模式，改密成功前不可关闭。
 */
export function SecurityDialog({
  open, onOpenChange, me, forcePasswordChange = false, onChanged,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  me: Me | null;
  forcePasswordChange?: boolean;
  onChanged?: () => void;
}) {
  const [tab, setTab] = useState<Tab>("profile");
  const [sessions, setSessions] = useState<SessionView[]>([]);
  const [tokens, setTokens] = useState<TokenView[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 改密
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  // 新建令牌
  const [tokenName, setTokenName] = useState("");
  const [tokenDays, setTokenDays] = useState(90);
  const [newSecret, setNewSecret] = useState<string | null>(null);

  const loadSessions = useCallback(() => {
    void listSessions().then(setSessions).catch((e) => setErr(String(e)));
  }, []);
  const loadTokens = useCallback(() => {
    void listTokens().then(setTokens).catch((e) => setErr(String(e)));
  }, []);

  useEffect(() => {
    if (!open) return;
    setErr(null);
    setNotice(null);
    setNewSecret(null);
    setCurrentPassword("");
    setNewPassword("");
    setConfirmPassword("");
    setTab(forcePasswordChange ? "password" : "profile");
    loadSessions();
    loadTokens();
  }, [open, forcePasswordChange, loadSessions, loadTokens]);

  const locked = open && forcePasswordChange;

  async function submitPassword() {
    if (newPassword.length < 10 || newPassword.length > 128) {
      setErr("密码长度须为 10–128"); return;
    }
    if (newPassword !== confirmPassword) {
      setErr("两次输入的新密码不一致"); return;
    }
    if (me?.hasPassword && !me?.mustChangePassword && !currentPassword) {
      setErr("请输入当前密码"); return;
    }
    setBusy(true); setErr(null); setNotice(null);
    try {
      await changePassword(
        me?.hasPassword && !me?.mustChangePassword ? currentPassword : null,
        newPassword,
      );
      setCurrentPassword(""); setNewPassword(""); setConfirmPassword("");
      setNotice("密码已修改，其它 Web 会话已吊销。");
      loadSessions();
      onChanged?.();
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function revokeOne(s: SessionView) {
    if (s.current) return;
    if (!window.confirm("吊销该会话？该设备下一次请求将需要重新登录。")) return;
    setErr(null); setNotice(null);
    try {
      await revokeSession(s.id);
      setNotice("会话已吊销");
      loadSessions();
    } catch (e) { setErr(String(e)); }
  }

  async function revokeOthers() {
    const others = sessions.filter((s) => !s.current);
    if (others.length === 0) return;
    if (!window.confirm(`吊销其余 ${others.length} 个会话？这些设备下一次请求将需要重新登录。`)) return;
    setBusy(true); setErr(null); setNotice(null);
    try {
      for (const s of others) await revokeSession(s.id);
      setNotice(`已吊销其它 ${others.length} 个会话`);
      loadSessions();
    } catch (e) {
      setErr(String(e));
      loadSessions();
    } finally {
      setBusy(false);
    }
  }

  async function doCreateToken() {
    if (!tokenName.trim()) { setErr("请输入令牌名称"); return; }
    setBusy(true); setErr(null); setNotice(null);
    try {
      const r = await createToken(tokenName.trim(), tokenDays);
      setNewSecret(r.secret);
      setTokenName("");
      setNotice("令牌已创建：明文只显示这一次，请立即复制保存。");
      loadTokens();
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function doRevokeToken(t: TokenView) {
    if (!window.confirm(`吊销令牌「${t.name}」？使用该令牌的调用将立即 401。`)) return;
    setErr(null); setNotice(null);
    try {
      await revokeToken(t.id);
      setNotice(`已吊销令牌 ${t.name}`);
      loadTokens();
    } catch (e) { setErr(String(e)); }
  }

  async function copySecret() {
    if (!newSecret) return;
    try {
      await navigator.clipboard.writeText(newSecret);
      setNotice("已复制到剪贴板");
    } catch {
      setNotice("复制失败，请手动选择令牌文本");
    }
  }

  const org = me?.orgs?.find((o) => o.id === me?.currentOrgId) ?? me?.orgs?.[0] ?? null;

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o && locked) return; onOpenChange(o); }}>
      <DialogContent className="sm:max-w-2xl" showCloseButton={!locked}>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><ShieldCheck className="size-4" /> 账号与安全</DialogTitle>
          <DialogDescription>
            {locked
              ? "首次登录或密码已被重置：请先设置新密码，完成后才能继续使用。"
              : "管理资料、密码、活动会话与 API 令牌。浏览器端只持有 HttpOnly 会话 Cookie。"}
          </DialogDescription>
        </DialogHeader>

        {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}
        {notice && <div className="rounded-md border border-emerald-500/30 bg-emerald-500/10 px-3 py-2 text-[12.5px] text-emerald-700 dark:text-emerald-400">{notice}</div>}

        <Tabs value={tab} onValueChange={(v) => setTab(v as Tab)}>
          <TabsList className="w-full">
            <TabsTrigger value="profile">资料</TabsTrigger>
            <TabsTrigger value="password">修改密码</TabsTrigger>
            <TabsTrigger value="sessions">活动会话</TabsTrigger>
            <TabsTrigger value="tokens">我的令牌</TabsTrigger>
          </TabsList>

          <div className="max-h-[55vh] overflow-auto pr-1">
            {/* 资料 */}
            <TabsContent value="profile" className="pt-2">
              <dl className="grid grid-cols-[7rem_1fr] gap-x-3 gap-y-2 text-[13px]">
                <dt className="text-muted-foreground">用户名</dt>
                <dd className="font-mono">{me?.username ?? "—"}</dd>
                <dt className="text-muted-foreground">显示名</dt>
                <dd>{me?.displayName || "—"}</dd>
                <dt className="text-muted-foreground">邮箱</dt>
                <dd className="font-mono">{me?.email || "—"}</dd>
                <dt className="text-muted-foreground">账号状态</dt>
                <dd><Badge variant={me?.status === "active" ? "default" : "outline"} className="font-mono text-[10px]">{me?.status ?? "—"}</Badge></dd>
                <dt className="text-muted-foreground">组织</dt>
                <dd>{org ? `${org.name}（${org.slug}）` : "—"}</dd>
                <dt className="text-muted-foreground">组织角色</dt>
                <dd className="font-mono">{org?.role ?? (me?.orgAdmin ? "org_admin" : "org_member")}</dd>
                <dt className="text-muted-foreground">当前登录方式</dt>
                <dd className="font-mono">{me?.authMethod ?? "session"}</dd>
                <dt className="text-muted-foreground">SSO（OIDC）</dt>
                <dd>
                  {me?.oidcEnabled
                    ? "已启用；首次 SSO 登录按已验证邮箱自动绑定（只读，不提供 Web 解绑）"
                    : "未启用"}
                </dd>
              </dl>
            </TabsContent>

            {/* 修改密码 */}
            <TabsContent value="password" className="flex flex-col gap-2 pt-2">
              {me?.hasPassword && !me?.mustChangePassword && (
                <Input
                  type="password"
                  placeholder="当前密码"
                  value={currentPassword}
                  onChange={(e) => setCurrentPassword(e.target.value)}
                  autoComplete="current-password"
                />
              )}
              <Input
                type="password"
                placeholder="新密码（10–128 位）"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                autoComplete="new-password"
              />
              <Input
                type="password"
                placeholder="确认新密码"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                autoComplete="new-password"
                onKeyDown={(e) => { if (e.key === "Enter") void submitPassword(); }}
              />
              <p className="text-[11.5px] text-muted-foreground">
                密码 10–128 位，不能与用户名 / 邮箱相同；改密成功后其它 Web 会话会被吊销（API 令牌不受影响）。
              </p>
              <div>
                <Button size="sm" onClick={() => void submitPassword()} disabled={busy || !newPassword}>
                  {busy ? "提交中…" : "修改密码"}
                </Button>
              </div>
            </TabsContent>

            {/* 活动会话 */}
            <TabsContent value="sessions" className="flex flex-col gap-2 pt-2">
              <div className="flex items-center justify-between">
                <p className="text-[12px] text-muted-foreground">来自 Spring Session（Redis）；列表只展示脱敏会话 id。</p>
                <div className="flex items-center gap-1">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy || !sessions.some((s) => !s.current)}
                    onClick={() => void revokeOthers()}
                  >
                    吊销其它会话
                  </Button>
                  <Button size="icon" variant="ghost" className="size-7" title="刷新" onClick={loadSessions}>
                    <RefreshCw className="size-3.5" />
                  </Button>
                </div>
              </div>
              {sessions.map((s) => (
                <div key={s.id} className="flex items-start gap-2 rounded-md border bg-muted/20 px-3 py-2">
                  <MonitorSmartphone className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-mono text-[11px] text-muted-foreground">{s.id}</span>
                      {s.current && <Badge variant="default" className="text-[10px]">当前会话</Badge>}
                    </div>
                    <div className="truncate text-[12px]" title={s.userAgent ?? ""}>{s.userAgent || "未知 User-Agent"}</div>
                    <div className="text-[11.5px] text-muted-foreground">
                      IP {s.ip || "—"} · 创建 {fmt(s.createdAt)} · 最近访问 {fmt(s.lastAccessedAt)}
                    </div>
                  </div>
                  <Button
                    size="sm"
                    variant="outline"
                    className="text-destructive"
                    disabled={s.current}
                    title={s.current ? "当前会话请用「退出登录」" : "吊销会话"}
                    onClick={() => void revokeOne(s)}
                  >
                    吊销
                  </Button>
                </div>
              ))}
              {sessions.length === 0 && <p className="py-3 text-center text-[12px] text-muted-foreground">暂无活动会话</p>}
            </TabsContent>

            {/* 我的令牌 */}
            <TabsContent value="tokens" className="flex flex-col gap-2 pt-2">
              {newSecret && (
                <div className="rounded-md border border-amber-500/30 bg-amber-500/5 px-3 py-2">
                  <p className="text-[12px] font-medium text-amber-700 dark:text-amber-400">新令牌（仅此显示，请立即复制）</p>
                  <div className="mt-1 flex items-center gap-2">
                    <code className="min-w-0 flex-1 overflow-auto break-all rounded bg-muted px-2 py-1 font-mono text-[12px]">{newSecret}</code>
                    <Button size="icon" variant="outline" className="size-7 shrink-0" title="复制" onClick={() => void copySecret()}>
                      <Copy className="size-3.5" />
                    </Button>
                  </div>
                </div>
              )}

              <div className="flex flex-wrap items-center gap-2">
                <Input
                  value={tokenName}
                  onChange={(e) => setTokenName(e.target.value)}
                  placeholder="令牌名称（如 ci-mcp）"
                  className="w-48 text-[12.5px]"
                />
                <select
                  value={tokenDays}
                  onChange={(e) => setTokenDays(Number(e.target.value))}
                  className="h-9 rounded-md border bg-background px-2 font-mono text-[12px]"
                  title="有效期（天）"
                >
                  {[30, 90, 180, 365].map((d) => <option key={d} value={d}>{d} 天</option>)}
                </select>
                <Button size="sm" variant="outline" onClick={() => void doCreateToken()} disabled={busy || !tokenName.trim()}>
                  <Plus className="size-3.5" /> 创建令牌
                </Button>
              </div>

              <div className="flex flex-col gap-1.5">
                {tokens.map((t) => (
                  <div key={t.id} className="flex items-center gap-2 rounded-md border px-3 py-2">
                    <KeyRound className="size-3.5 shrink-0 text-muted-foreground" />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="truncate text-[12.5px] font-medium">{t.name}</span>
                        <Badge variant={t.status === "active" ? "default" : "outline"} className="font-mono text-[10px]">{t.status}</Badge>
                      </div>
                      <div className="font-mono text-[11.5px] text-muted-foreground">
                        {t.prefix || "legacy"}… · 过期 {fmt(t.expiresAt)} · 最近使用 {fmt(t.lastUsedAt)}
                      </div>
                    </div>
                    <Button
                      size="icon"
                      variant="ghost"
                      className="size-7 text-destructive"
                      title="吊销令牌"
                      disabled={t.status === "revoked"}
                      onClick={() => void doRevokeToken(t)}
                    >
                      <Trash2 className="size-3.5" />
                    </Button>
                  </div>
                ))}
                {tokens.length === 0 && <p className="py-3 text-center text-[12px] text-muted-foreground">暂无 API 令牌</p>}
              </div>
            </TabsContent>
          </div>
        </Tabs>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={locked}>关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
