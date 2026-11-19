import { useCallback, useEffect, useMemo, useState } from "react";
import { Plus, Trash2, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import {
  addTeamMember,
  createTeam,
  deleteTeam,
  listTeamMembers,
  listTeams,
  removeTeamMember,
  updateTeam,
  updateTeamMemberRole,
  TEAM_ROLES,
  type Me,
  type Team,
  type TeamMember,
} from "@/lib/api";

/**
 * B6 团队管理：org_admin 创建/删除团队；team lead 改名、管理成员；
 * 普通成员只看自己所在团队。订阅与通知按团队投递。
 */
export function TeamDialog({
  open,
  onOpenChange,
  me,
  onChanged,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  me: Me | null;
  /** 团队/成员变更后刷新 me.teams（订阅下拉等） */
  onChanged?: () => void;
}) {
  const [teams, setTeams] = useState<Team[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [members, setMembers] = useState<TeamMember[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [newName, setNewName] = useState("");
  const [newDesc, setNewDesc] = useState("");
  const [renameName, setRenameName] = useState("");
  const [addUsername, setAddUsername] = useState("");
  const [addRole, setAddRole] = useState("member");

  // dev 免登录：后端团队端点全放行，前端同样展示全部团队与管理入口
  const devMode = me?.authEnabled === false;
  const fullAccess = me?.orgAdmin === true || me?.platformAdmin === true || devMode;
  const visibleTeams = useMemo(
    () => (fullAccess ? teams : teams.filter((t) => t.myRole != null)),
    [teams, fullAccess],
  );
  const selected = teams.find((t) => t.id === selectedId) ?? null;
  const canManageSelected = selected?.canManage === true;

  const load = useCallback(() => {
    void listTeams()
      .then((ts) => {
        setTeams(ts);
        const visible = fullAccess ? ts : ts.filter((t) => t.myRole != null);
        setSelectedId((cur) => (cur != null && visible.some((t) => t.id === cur) ? cur : (visible[0]?.id ?? null)));
      })
      .catch((e) => setErr(String(e)));
  }, [fullAccess]);

  const loadMembers = useCallback((teamId: number) => {
    void listTeamMembers(teamId)
      .then(setMembers)
      .catch((e) => { setMembers([]); setErr(String(e)); });
  }, []);

  useEffect(() => {
    if (!open) return;
    setErr(null);
    setNotice(null);
    load();
  }, [open, load]);

  useEffect(() => {
    if (selectedId == null) {
      setMembers([]);
      setRenameName("");
      return;
    }
    setRenameName(teams.find((t) => t.id === selectedId)?.name ?? "");
    loadMembers(selectedId);
  }, [selectedId, teams, loadMembers]);

  async function doCreate() {
    if (!newName.trim()) { setErr("团队名不能为空"); return; }
    setErr(null); setNotice(null);
    try {
      const t = await createTeam({ name: newName.trim(), description: newDesc.trim() });
      setNewName(""); setNewDesc("");
      setNotice(`已创建团队「${t.name}」，你已加入为 lead`);
      setSelectedId(t.id);
      load();
      onChanged?.();
    } catch (e) { setErr(String(e)); }
  }

  async function doRename() {
    if (selected == null || !renameName.trim()) return;
    setErr(null); setNotice(null);
    try {
      const t = await updateTeam(selected.id, { name: renameName.trim() });
      setNotice(`已改名为「${t.name}」；订阅与通知保持关联`);
      load();
      onChanged?.();
    } catch (e) { setErr(String(e)); }
  }

  async function doDelete() {
    if (selected == null) return;
    if (!window.confirm(`删除团队「${selected.name}」？有订阅时需先取消订阅。`)) return;
    setErr(null); setNotice(null);
    try {
      await deleteTeam(selected.id);
      setNotice(`已删除团队「${selected.name}」`);
      setSelectedId(null);
      load();
      onChanged?.();
    } catch (e) { setErr(String(e)); }
  }

  async function doAddMember() {
    if (selected == null) return;
    if (!addUsername.trim()) { setErr("用户名不能为空"); return; }
    setErr(null); setNotice(null);
    try {
      const m = await addTeamMember(selected.id, { username: addUsername.trim(), role: addRole });
      setAddUsername("");
      setNotice(`已加入 ${m.username}（${m.role}）`);
      loadMembers(selected.id);
      onChanged?.();
    } catch (e) { setErr(String(e)); }
  }

  async function doRole(m: TeamMember, role: string) {
    if (selected == null) return;
    setErr(null); setNotice(null);
    try {
      await updateTeamMemberRole(selected.id, m.userId, role);
      setNotice(`已更新 ${m.username} → ${role}`);
      loadMembers(selected.id);
    } catch (e) { setErr(String(e)); }
  }

  async function doRemove(m: TeamMember) {
    if (selected == null) return;
    if (!window.confirm(`从团队「${selected.name}」移除 ${m.username}？`)) return;
    setErr(null); setNotice(null);
    try {
      await removeTeamMember(selected.id, m.userId);
      setNotice(`已移除 ${m.username}`);
      loadMembers(selected.id);
      onChanged?.();
    } catch (e) { setErr(String(e)); }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><Users className="size-4" /> 团队管理</DialogTitle>
          <DialogDescription>
            org_admin 可创建/删除团队；team lead 可改名与管理成员。订阅与变更通知按团队投递，团队改名不影响订阅。
          </DialogDescription>
        </DialogHeader>

        {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}
        {notice && <div className="rounded-md border border-emerald-500/30 bg-emerald-500/10 px-3 py-2 text-[12.5px] text-emerald-700 dark:text-emerald-400">{notice}</div>}

        {fullAccess && (
          <div className="rounded-md border bg-muted/20 p-3">
            <div className="mb-2 text-[12px] font-medium text-muted-foreground">创建团队</div>
            <div className="flex flex-wrap items-center gap-2">
              <Input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="团队名" className="w-40 text-[12.5px]" />
              <Input value={newDesc} onChange={(e) => setNewDesc(e.target.value)} placeholder="描述（可选）" className="w-52 text-[12.5px]" />
              <Button size="sm" onClick={() => void doCreate()}>
                <Plus className="size-3.5" /> 创建
              </Button>
            </div>
          </div>
        )}

        <div className="grid gap-4 sm:grid-cols-[220px_1fr]">
          <div className="flex max-h-72 flex-col gap-1 overflow-auto rounded-md border p-1.5">
            {visibleTeams.map((t) => (
              <button
                key={t.id}
                onClick={() => setSelectedId(t.id)}
                className={`flex flex-col gap-0.5 rounded-md px-2.5 py-2 text-left transition-colors ${
                  t.id === selectedId ? "bg-primary/8 text-primary" : "text-foreground/80 hover:bg-muted"
                }`}
              >
                <span className="flex w-full items-center gap-1.5">
                  <span className="min-w-0 flex-1 truncate text-[13px] font-medium">{t.name}</span>
                  {t.myRole && <Badge variant="outline" className="font-mono text-[9px]">{t.myRole}</Badge>}
                </span>
                <span className="text-[10.5px] text-muted-foreground">{t.memberCount} 人 · {t.subscriptionCount} 订阅</span>
              </button>
            ))}
            {visibleTeams.length === 0 && (
              <p className="px-2.5 py-2 text-[12px] text-muted-foreground">
                {fullAccess ? "还没有团队，先创建一个。" : "你还没有加入任何团队。请联系 org_admin 或 team lead。"}
              </p>
            )}
          </div>

          <div className="flex min-w-0 flex-col gap-3">
            {selected ? (
              <>
                <div className="rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-semibold">{selected.name}</span>
                    {selected.myRole && <Badge variant="outline" className="font-mono text-[10px]">{selected.myRole}</Badge>}
                    {selected.canManage && <Badge className="text-[10px]">可管理</Badge>}
                  </div>
                  {selected.description && <p className="mt-1 text-[12px] text-muted-foreground">{selected.description}</p>}
                  {canManageSelected && (
                    <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-border/60 pt-3">
                      <Input value={renameName} onChange={(e) => setRenameName(e.target.value)} placeholder="团队名" className="w-44 text-[12.5px]" />
                      <Button size="sm" variant="outline" onClick={() => void doRename()} disabled={!renameName.trim() || renameName.trim() === selected.name}>
                        保存名称
                      </Button>
                      {fullAccess && (
                        <Button size="sm" variant="destructive" onClick={() => void doDelete()}>
                          <Trash2 className="size-3.5" /> 删除团队
                        </Button>
                      )}
                    </div>
                  )}
                </div>

                <div className="rounded-md border p-3">
                  <h4 className="mb-2 text-[12px] font-medium text-muted-foreground">成员（{members.length}）</h4>
                  <div className="flex flex-col gap-1.5">
                    {members.map((m) => (
                      <div key={m.userId} className="flex items-center gap-2 rounded-md border px-2.5 py-1.5">
                        <span className="min-w-0 flex-1 truncate font-mono text-[12.5px]">{m.username}</span>
                        {m.orgRole && <Badge variant="outline" className="font-mono text-[9px]">{m.orgRole}</Badge>}
                        {canManageSelected ? (
                          <>
                            <select
                              value={m.role}
                              onChange={(e) => void doRole(m, e.target.value)}
                              className="h-7 rounded-md border bg-background px-1.5 font-mono text-[11.5px]"
                              title="改团队角色"
                            >
                              {TEAM_ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                            </select>
                            <Button
                              size="icon-xs"
                              variant="ghost"
                              className="text-destructive"
                              title="移除成员"
                              onClick={() => void doRemove(m)}
                            >
                              <Trash2 className="size-3" />
                            </Button>
                          </>
                        ) : (
                          <Badge className="font-mono text-[10px]">{m.role}</Badge>
                        )}
                      </div>
                    ))}
                    {members.length === 0 && <p className="text-[12px] text-muted-foreground">暂无成员</p>}
                  </div>

                  {canManageSelected && (
                    <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-border/60 pt-3">
                      <Input
                        value={addUsername}
                        onChange={(e) => setAddUsername(e.target.value)}
                        placeholder="组织用户名"
                        className="w-40 font-mono text-[12.5px]"
                      />
                      <select
                        value={addRole}
                        onChange={(e) => setAddRole(e.target.value)}
                        className="h-8 rounded-md border bg-background px-1.5 font-mono text-[12px]"
                      >
                        {TEAM_ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                      </select>
                      <Button size="sm" variant="outline" onClick={() => void doAddMember()}>添加成员</Button>
                    </div>
                  )}
                </div>
              </>
            ) : (
              <div className="flex h-40 items-center justify-center rounded-md border text-[12.5px] text-muted-foreground">
                选择左侧团队查看成员与设置
              </div>
            )}
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
