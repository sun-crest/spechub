import { useCallback, useEffect, useState } from "react";
import { Users, RefreshCw, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { createUser, deleteUser, listUsers, resetUserToken, setUserRole, WRITE_ROLES, type UserView } from "@/lib/api";

const ROLES = ["owner", "admin", "editor", "viewer"];

/** I4 成员管理弹窗：列出成员、创建用户（返回一次性 token）、改角色、重置令牌、删除 */
export function MembersDialog({ open, onOpenChange }: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [users, setUsers] = useState<UserView[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // 创建表单
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [role, setRole] = useState("editor");
  const [newToken, setNewToken] = useState<string | null>(null); // 一次性 token 展示

  const load = useCallback(() => {
    void listUsers().then(setUsers).catch((e) => setErr(String(e)));
  }, []);

  useEffect(() => {
    if (open) { load(); setNewToken(null); setNotice(null); setErr(null); }
  }, [open, load]);

  async function doCreate() {
    if (!username.trim()) { setErr("用户名不能为空"); return; }
    setErr(null); setNotice(null);
    try {
      const r = await createUser({ username: username.trim(), displayName: displayName.trim(), role });
      setUsername(""); setDisplayName("");
      setNewToken(r.token);
      setNotice(`已创建 ${r.user.username} 并加入默认项目（角色 ${role}）`);
      load();
    } catch (e) { setErr(String(e)); }
  }

  async function doRole(id: number, name: string, next: string) {
    setErr(null); setNotice(null);
    try { await setUserRole(id, next); setNotice(`已更新 ${name} → ${next}`); load(); }
    catch (e) { setErr(String(e)); }
  }

  async function doReset(u: UserView) {
    setErr(null); setNotice(null);
    try { const r = await resetUserToken(u.id); setNewToken(r.token); setNotice(`已重置 ${u.username} 的令牌（见下方一次性令牌）`); }
    catch (e) { setErr(String(e)); }
  }

  async function doDelete(u: UserView) {
    if (!window.confirm(`删除用户 ${u.username}？该操作不可撤销。`)) return;
    setErr(null); setNotice(null);
    try { await deleteUser(u.id); setNotice(`已删除 ${u.username}`); load(); }
    catch (e) { setErr(String(e)); }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Users className="size-4" /> 成员与角色</DialogTitle>
          <DialogDescription>
            RBAC：viewer 只读 · editor 可编辑/promote/mock 规则 · admin +成员管理 · owner 全量。
          </DialogDescription>
        </DialogHeader>

        {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}
        {notice && <div className="rounded-md border border-emerald-500/30 bg-emerald-500/10 px-3 py-2 text-[12.5px] text-emerald-700 dark:text-emerald-400">{notice}</div>}

        {newToken && (
          <div className="rounded-md border border-amber-500/30 bg-amber-500/5 px-3 py-2">
            <p className="text-[12px] font-medium text-amber-700 dark:text-amber-400">一次性令牌（仅此显示，请立即交付给用户）</p>
            <code className="mt-1 block overflow-auto break-all rounded bg-muted px-2 py-1 font-mono text-[12px]">{newToken}</code>
          </div>
        )}

        <div className="flex flex-col gap-2">
          {users.map((u) => (
            <div key={u.id} className="flex items-center gap-2 rounded-md border bg-muted/20 px-3 py-2">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate font-mono text-[13px]">{u.username}</span>
                  {u.platformAdmin && <Badge variant="default" className="font-mono text-[10px]">平台 admin</Badge>}
                </div>
                <div className="text-[11.5px] text-muted-foreground">{u.displayName || u.username}</div>
              </div>
              {!u.platformAdmin ? (
                <select
                  value={u.role ?? "viewer"}
                  onChange={(e) => void doRole(u.id, u.username, e.target.value)}
                  className="h-8 rounded-md border bg-background px-1.5 font-mono text-[12px]"
                  title="改角色"
                >
                  {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                </select>
              ) : (
                <span className="font-mono text-[12px] text-muted-foreground">owner*</span>
              )}
              {!u.platformAdmin && (
                <>
                  <Button size="icon" variant="ghost" className="size-7" title="重置令牌" onClick={() => void doReset(u)}>
                    <RefreshCw className="size-3.5" />
                  </Button>
                  <Button size="icon" variant="ghost" className="size-7 text-destructive" title="删除" onClick={() => void doDelete(u)}>
                    <Trash2 className="size-3.5" />
                  </Button>
                </>
              )}
            </div>
          ))}
          {users.length === 0 && <p className="text-[12.5px] text-muted-foreground">暂无成员（平台 admin 恒为 owner*）</p>}
        </div>

        <div className="mt-1 border-t border-border/60 pt-3">
          <div className="mb-2 text-[12px] font-medium text-muted-foreground">新增成员</div>
          <div className="flex flex-wrap gap-2">
            <Input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="用户名" className="w-36 font-mono text-[12.5px]" />
            <Input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="显示名（可选）" className="w-32 text-[12.5px]" />
            <select value={role} onChange={(e) => setRole(e.target.value)} className="h-9 rounded-md border bg-background px-2 font-mono text-[12px]">
              {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
            <Button size="sm" variant="outline" onClick={() => void doCreate()}>创建用户</Button>
          </div>
          <p className="mt-1 text-[11px] text-muted-foreground">新用户会收到一次性令牌，用「用户名 + 令牌」登录。</p>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}