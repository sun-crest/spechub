import { useCallback, useEffect, useState } from "react";
import { Users, RefreshCw, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import {
  addProjectMember,
  createUser,
  deleteUser,
  listProjectMembers,
  listUsers,
  removeProjectMember,
  resetUserToken,
  updateProjectMemberRole,
  PROJECT_ROLES,
  type ProjectMember,
  type UserView,
} from "@/lib/api";

const ORG_ROLES = ["org_member", "org_admin"];

/** 阶段 A 项目成员管理：按项目列成员、加成员、改项目角色、移除；org_admin 额外可管理组织用户。 */
export function MembersDialog({
  open,
  onOpenChange,
  projectId,
  projectName,
  canManageOrg,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projectId: number | null;
  projectName: string | null;
  canManageOrg: boolean;
}) {
  const [members, setMembers] = useState<ProjectMember[]>([]);
  const [orgUsers, setOrgUsers] = useState<UserView[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // 加入项目
  const [addUsername, setAddUsername] = useState("");
  const [addRole, setAddRole] = useState("viewer");

  // 创建组织用户
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [orgRole, setOrgRole] = useState("org_member");
  const [newToken, setNewToken] = useState<string | null>(null);

  const load = useCallback(() => {
    if (projectId == null) {
      setMembers([]);
      setOrgUsers([]);
      return;
    }
    void listProjectMembers(projectId).then(setMembers).catch((e) => setErr(String(e)));
    if (canManageOrg) {
      void listUsers().then(setOrgUsers).catch(() => setOrgUsers([]));
    } else {
      setOrgUsers([]);
    }
  }, [projectId, canManageOrg]);

  useEffect(() => {
    if (open) {
      setNewToken(null);
      setNotice(null);
      setErr(null);
      load();
    }
  }, [open, load]);

  async function doAdd() {
    if (projectId == null) { setErr("请先选择项目"); return; }
    if (!addUsername.trim()) { setErr("用户名不能为空"); return; }
    setErr(null); setNotice(null);
    try {
      const member = await addProjectMember(projectId, { username: addUsername.trim(), role: addRole });
      setAddUsername("");
      setNotice(`已将 ${member.username} 加入项目（${member.role}）`);
      load();
    } catch (e) { setErr(String(e)); }
  }

  async function doRole(member: ProjectMember, next: string) {
    if (projectId == null || member.role === "owner") return;
    setErr(null); setNotice(null);
    try {
      await updateProjectMemberRole(projectId, member.userId, next);
      setNotice(`已更新 ${member.username} → ${next}`);
      load();
    } catch (e) { setErr(String(e)); }
  }

  async function doRemove(member: ProjectMember) {
    if (projectId == null || member.role === "owner") return;
    if (!window.confirm(`从项目移除 ${member.username}？`)) return;
    setErr(null); setNotice(null);
    try {
      await removeProjectMember(projectId, member.userId);
      setNotice(`已移除 ${member.username}`);
      load();
    } catch (e) { setErr(String(e)); }
  }

  async function doCreateOrgUser() {
    if (!username.trim()) { setErr("用户名不能为空"); return; }
    setErr(null); setNotice(null);
    try {
      const r = await createUser({ username: username.trim(), displayName: displayName.trim(), orgRole });
      setUsername(""); setDisplayName("");
      setNewToken(r.token);
      setNotice(`已创建组织用户 ${r.user.username}；可用用户名将其加入项目`);
      load();
    } catch (e) { setErr(String(e)); }
  }

  async function doReset(u: UserView) {
    setErr(null); setNotice(null);
    try {
      const r = await resetUserToken(u.id);
      setNewToken(r.token);
      setNotice(`已重置 ${u.username} 的令牌（见下方一次性令牌）`);
    } catch (e) { setErr(String(e)); }
  }

  async function doDeleteOrgUser(u: UserView) {
    if (!window.confirm(`删除组织用户 ${u.username}？该操作不可撤销。`)) return;
    setErr(null); setNotice(null);
    try {
      await deleteUser(u.id);
      setNotice(`已删除组织用户 ${u.username}`);
      load();
    } catch (e) { setErr(String(e)); }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Users className="size-4" /> 成员与角色</DialogTitle>
          <DialogDescription>
            {projectName ? `项目「${projectName}」：viewer 只读 · editor 可编辑 · admin 可管成员 · owner 全量（转移走项目接口）。` : "请先在顶栏选择项目。"}
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
          {members.map((m) => (
            <div key={m.userId} className="flex items-center gap-2 rounded-md border bg-muted/20 px-3 py-2">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate font-mono text-[13px]">{m.username}</span>
                  {m.role === "owner" && <Badge variant="default" className="font-mono text-[10px]">owner</Badge>}
                </div>
                <div className="text-[11.5px] text-muted-foreground">
                  {m.displayName || m.username}{m.orgRole ? ` · ${m.orgRole}` : ""}
                </div>
              </div>
              <select
                value={m.role}
                disabled={m.role === "owner"}
                onChange={(e) => void doRole(m, e.target.value)}
                className="h-8 rounded-md border bg-background px-1.5 font-mono text-[12px] disabled:opacity-50"
                title={m.role === "owner" ? "owner 需通过项目转移接口变更" : "改项目角色"}
              >
                {m.role === "owner" && <option value="owner">owner</option>}
                {PROJECT_ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
              <Button
                size="icon"
                variant="ghost"
                className="size-7 text-destructive"
                title={m.role === "owner" ? "owner 需先转移" : "移除"}
                disabled={m.role === "owner"}
                onClick={() => void doRemove(m)}
              >
                <Trash2 className="size-3.5" />
              </Button>
            </div>
          ))}
          {members.length === 0 && <p className="text-[12.5px] text-muted-foreground">暂无项目成员</p>}
        </div>

        <div className="mt-1 border-t border-border/60 pt-3">
          <div className="mb-2 text-[12px] font-medium text-muted-foreground">加入已有组织用户</div>
          <div className="flex flex-wrap gap-2">
            <Input value={addUsername} onChange={(e) => setAddUsername(e.target.value)} placeholder="用户名" className="w-40 font-mono text-[12.5px]" />
            <select value={addRole} onChange={(e) => setAddRole(e.target.value)} className="h-9 rounded-md border bg-background px-2 font-mono text-[12px]">
              {PROJECT_ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
            <Button size="sm" variant="outline" onClick={() => void doAdd()} disabled={projectId == null}>加入项目</Button>
          </div>
        </div>

        {canManageOrg && (
          <div className="mt-1 border-t border-border/60 pt-3">
            <div className="mb-2 text-[12px] font-medium text-muted-foreground">创建组织用户</div>
            <div className="flex flex-wrap gap-2">
              <Input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="用户名" className="w-36 font-mono text-[12.5px]" />
              <Input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="显示名（可选）" className="w-32 text-[12.5px]" />
              <select value={orgRole} onChange={(e) => setOrgRole(e.target.value)} className="h-9 rounded-md border bg-background px-2 font-mono text-[12px]">
                {ORG_ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
              <Button size="sm" variant="outline" onClick={() => void doCreateOrgUser()}>创建</Button>
            </div>
            <p className="mt-1 text-[11px] text-muted-foreground">新用户会收到一次性令牌；创建后可用用户名加入项目。</p>

            {orgUsers.length > 0 && (
              <div className="mt-3 flex max-h-40 flex-col gap-1.5 overflow-auto">
                {orgUsers.map((u) => (
                  <div key={u.id} className="flex items-center gap-2 rounded-md border px-2.5 py-1.5">
                    <span className="min-w-0 flex-1 truncate font-mono text-[12px]">{u.username}</span>
                    <Badge variant="outline" className="font-mono text-[10px]">{u.orgRole ?? "org_member"}</Badge>
                    <Button size="icon" variant="ghost" className="size-6" title="重置令牌" onClick={() => void doReset(u)}>
                      <RefreshCw className="size-3" />
                    </Button>
                    <Button
                      size="icon"
                      variant="ghost"
                      className="size-6 text-destructive"
                      title="删除组织用户"
                      onClick={() => void doDeleteOrgUser(u)}
                    >
                      <Trash2 className="size-3" />
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
