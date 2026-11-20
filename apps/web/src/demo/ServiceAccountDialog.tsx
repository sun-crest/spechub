import { useCallback, useEffect, useMemo, useState } from "react";
import { Copy, KeyRound, Plus, RefreshCw, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import {
  createServiceAccount, listServiceAccounts, revokeServiceAccount, rotateServiceAccount,
  SERVICE_ACCOUNT_ROLES, type Project, type ServiceAccount,
} from "@/lib/api";

const fmt = (v?: string | null) => {
  if (!v) return "—";
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : d.toLocaleString();
};

const isExpired = (s: ServiceAccount) => {
  if (!s.expiresAt) return false;
  const t = new Date(s.expiresAt).getTime();
  return !Number.isNaN(t) && t < Date.now();
};

/**
 * 阶段 C：项目级服务账号（CI / MCP / CLI）。
 * - 入口：当前项目 admin+ / org_admin；普通成员不显示；
 * - 密钥只在创建 / 轮换响应里显示一次；
 * - viewer 只读、editor 可写；吊销 / 轮换立即生效。
 */
export function ServiceAccountDialog({
  open, onOpenChange, projects, projectRoles, orgAdmin, defaultProjectId,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projects: Project[];
  projectRoles?: Record<string, string>;
  orgAdmin: boolean;
  defaultProjectId: number | null;
}) {
  const manageable = useMemo(
    () => projects.filter((p) => orgAdmin || ["admin", "owner"].includes(projectRoles?.[String(p.id)] ?? "")),
    [projects, projectRoles, orgAdmin],
  );

  const [projectId, setProjectId] = useState<number | null>(null);
  const [accounts, setAccounts] = useState<ServiceAccount[]>([]);
  const [name, setName] = useState("");
  const [role, setRole] = useState("viewer");
  const [days, setDays] = useState(90);
  const [newSecret, setNewSecret] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback((pid: number | null) => {
    if (pid == null) { setAccounts([]); return; }
    void listServiceAccounts(pid).then(setAccounts).catch((e) => setErr(String(e)));
  }, []);

  useEffect(() => {
    if (!open) return;
    setErr(null);
    setNotice(null);
    setNewSecret(null);
    const fallback =
      defaultProjectId != null && manageable.some((p) => p.id === defaultProjectId)
        ? defaultProjectId
        : (manageable[0]?.id ?? null);
    // 保留仍可管理的当前选择；否则回落到默认 / 第一个可管理项目
    setProjectId((cur) => (cur != null && manageable.some((p) => p.id === cur) ? cur : fallback));
  }, [open, defaultProjectId, manageable]);

  useEffect(() => {
    if (open) load(projectId);
  }, [open, projectId, load]);

  function selectProject(pid: number) {
    setProjectId(pid);
    setErr(null);
    setNotice(null);
    setNewSecret(null);
  }

  async function doCreate() {
    if (projectId == null) { setErr("请先选择项目"); return; }
    if (!name.trim()) { setErr("请输入服务账号名称"); return; }
    setBusy(true); setErr(null); setNotice(null);
    try {
      const r = await createServiceAccount({
        projectId,
        name: name.trim(),
        role,
        expiresInDays: days,
      });
      setNewSecret(r.token);
      setName("");
      setNotice(`已创建服务账号 ${r.account.name}；密钥只显示这一次。`);
      load(projectId);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function doRotate(a: ServiceAccount) {
    if (!window.confirm(`轮换服务账号「${a.name}」的密钥？旧密钥将立即失效。`)) return;
    if (projectId == null) return;
    setErr(null); setNotice(null);
    try {
      const r = await rotateServiceAccount(a.id);
      setNewSecret(r.token);
      setNotice(`已轮换 ${a.name}；新密钥只显示这一次。`);
      load(projectId);
    } catch (e) { setErr(String(e)); }
  }

  async function doRevoke(a: ServiceAccount) {
    if (!window.confirm(`吊销服务账号「${a.name}」？使用该账号的 CI / MCP 将立即 401。`)) return;
    if (projectId == null) return;
    setErr(null); setNotice(null);
    try {
      await revokeServiceAccount(a.id);
      setNotice(`已吊销 ${a.name}`);
      load(projectId);
    } catch (e) { setErr(String(e)); }
  }

  async function copySecret() {
    if (!newSecret) return;
    try {
      await navigator.clipboard.writeText(newSecret);
      setNotice("已复制到剪贴板");
    } catch {
      setNotice("复制失败，请手动选择密钥文本");
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><KeyRound className="size-4" /> 服务账号</DialogTitle>
          <DialogDescription>
            项目级机器身份（viewer 只读 / editor 可写），用于 CI、MCP、CLI；不可登录 Web，
            跨项目访问统一 404。密钥仅在创建 / 轮换时显示一次。
          </DialogDescription>
        </DialogHeader>

        {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}
        {notice && <div className="rounded-md border border-emerald-500/30 bg-emerald-500/10 px-3 py-2 text-[12.5px] text-emerald-700 dark:text-emerald-400">{notice}</div>}

        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[12px] text-muted-foreground">项目</span>
          <select
            value={projectId ?? ""}
            onChange={(e) => selectProject(Number(e.target.value))}
            className="h-9 min-w-40 rounded-md border bg-background px-2 text-[12.5px]"
          >
            {manageable.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            {manageable.length === 0 && <option value="">无可管理项目</option>}
          </select>
          <Button size="icon" variant="ghost" className="size-7" title="刷新" onClick={() => load(projectId)}>
            <RefreshCw className="size-3.5" />
          </Button>
        </div>

        {newSecret && (
          <div className="rounded-md border border-amber-500/30 bg-amber-500/5 px-3 py-2">
            <p className="text-[12px] font-medium text-amber-700 dark:text-amber-400">新密钥（仅此显示，请立即复制保存）</p>
            <div className="mt-1 flex items-center gap-2">
              <code className="min-w-0 flex-1 overflow-auto break-all rounded bg-muted px-2 py-1 font-mono text-[12px]">{newSecret}</code>
              <Button size="icon" variant="outline" className="size-7 shrink-0" title="复制" onClick={() => void copySecret()}>
                <Copy className="size-3.5" />
              </Button>
            </div>
            <p className="mt-1 text-[11px] text-muted-foreground">用法：<code className="font-mono">SPECHUB_TOKEN=shs_…</code> 作为 Bearer 发送，无需 Cookie / CSRF。</p>
          </div>
        )}

        <div className="flex max-h-[42vh] flex-col gap-1.5 overflow-auto pr-1">
          {accounts.map((a) => {
            const expired = isExpired(a);
            const state = a.status !== "active" ? a.status : expired ? "expired" : "active";
            return (
              <div key={a.id} className="flex items-center gap-2 rounded-md border px-3 py-2">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="truncate text-[13px] font-medium">{a.name}</span>
                    <Badge variant="outline" className="font-mono text-[10px]">{a.role}</Badge>
                    <Badge variant={state === "active" ? "default" : "outline"} className="font-mono text-[10px]">{state}</Badge>
                  </div>
                  <div className="font-mono text-[11.5px] text-muted-foreground">
                    {a.prefix}… · 过期 {fmt(a.expiresAt)} · 最近使用 {fmt(a.lastUsedAt)}
                  </div>
                </div>
                <Button size="sm" variant="outline" disabled={state === "revoked"} onClick={() => void doRotate(a)}>
                  轮换
                </Button>
                <Button
                  size="icon"
                  variant="ghost"
                  className="size-7 text-destructive"
                  title="吊销"
                  disabled={state === "revoked"}
                  onClick={() => void doRevoke(a)}
                >
                  <Trash2 className="size-3.5" />
                </Button>
              </div>
            );
          })}
          {accounts.length === 0 && <p className="py-3 text-center text-[12px] text-muted-foreground">当前项目暂无服务账号</p>}
        </div>

        <div className="border-t border-border/60 pt-3">
          <div className="mb-2 text-[12px] font-medium text-muted-foreground">创建服务账号</div>
          <div className="flex flex-wrap items-center gap-2">
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="名称（如 ci-mcp）"
              className="w-44 text-[12.5px]"
            />
            <select
              value={role}
              onChange={(e) => setRole(e.target.value)}
              className="h-9 rounded-md border bg-background px-2 font-mono text-[12px]"
            >
              {SERVICE_ACCOUNT_ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
            <select
              value={days}
              onChange={(e) => setDays(Number(e.target.value))}
              className="h-9 rounded-md border bg-background px-2 font-mono text-[12px]"
              title="有效期（1–365 天）"
            >
              {[30, 90, 180, 365].map((d) => <option key={d} value={d}>{d} 天</option>)}
            </select>
            <Button size="sm" variant="outline" onClick={() => void doCreate()} disabled={busy || projectId == null || !name.trim()}>
              <Plus className="size-3.5" /> 创建
            </Button>
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
