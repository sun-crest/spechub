import { useEffect, useState } from "react";
import { Archive, FolderPlus, Settings2, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import {
  archiveProject, createProject, deleteProject, updateProject, type Project,
} from "@/lib/api";

const VISIBILITIES: { value: string; label: string; hint: string }[] = [
  { value: "private", label: "私有", hint: "仅项目成员可读（draft + release）" },
  { value: "internal", label: "组织内", hint: "组织成员可读 release，成员才可读 draft" },
  { value: "public", label: "公开", hint: "匿名也可读 release（不含 draft/评论）" },
];

/**
 * 阶段 A 项目创建 / 设置：
 * - 创建：org_admin；
 * - 设置：项目 owner 可改名称/slug/描述/可见性；admin 仅可改可见性（服务端校验）；
 * - 归档 / 删除：owner（删除要求已归档且无仓库）。
 */
export function ProjectDialog({
  open,
  onOpenChange,
  mode,
  project,
  canCreate,
  canOwner,
  canAdmin,
  onChanged,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  mode: "create" | "settings";
  project: Project | null;
  /** 组织管理员（创建项目） */
  canCreate: boolean;
  /** 项目 owner（改名 / 归档 / 删除） */
  canOwner: boolean;
  /** 项目 admin+（改可见性） */
  canAdmin: boolean;
  onChanged: (project?: Project) => void;
}) {
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [description, setDescription] = useState("");
  const [visibility, setVisibility] = useState("private");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setName(project?.name ?? "");
    setSlug(project?.slug ?? "");
    setDescription(project?.description ?? "");
    setVisibility(project?.visibility ?? "private");
    setErr(null);
    setNotice(null);
    setBusy(false);
  }, [open, project]);

  const creating = mode === "create";
  const canEditMeta = creating ? canCreate : canOwner;
  const canEditVisibility = creating ? canCreate : canAdmin;
  const canArchive = !creating && canOwner && !project?.archived;
  const canDelete = !creating && canOwner;

  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setErr(null);
    setNotice(null);
    try {
      await fn();
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  const doSave = () =>
    run(async () => {
      if (!name.trim()) {
        setErr("项目名称不能为空");
        return;
      }
      if (creating) {
        const p = await createProject({
          name: name.trim(),
          slug: slug.trim() || undefined,
          description: description.trim(),
          visibility,
        });
        onChanged(p);
        setNotice(`项目「${p.name}」已创建`);
        onOpenChange(false);
      } else if (project) {
        const patch: Parameters<typeof updateProject>[1] = {};
        if (canEditMeta) {
          if (name.trim() !== project.name) patch.name = name.trim();
          // slug 不可清空：留空表示保持原值
          if (slug.trim() && slug.trim() !== (project.slug ?? "")) patch.slug = slug.trim();
          if (description.trim() !== (project.description ?? "")) patch.description = description.trim();
        }
        if (canEditVisibility && visibility !== (project.visibility ?? "private")) patch.visibility = visibility;
        if (Object.keys(patch).length === 0) {
          setNotice("没有改动");
          return;
        }
        const p = await updateProject(project.id, patch);
        onChanged(p);
        setNotice("已保存");
      }
    });

  const doArchive = () =>
    run(async () => {
      if (!project) return;
      const p = await archiveProject(project.id);
      onChanged(p);
      setNotice("项目已归档");
    });

  const doDelete = () =>
    run(async () => {
      if (!project) return;
      await deleteProject(project.id);
      onChanged();
      onOpenChange(false);
    });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            {creating ? <FolderPlus className="size-4" /> : <Settings2 className="size-4" />}
            {creating ? "新建项目" : "项目设置"}
          </DialogTitle>
          <DialogDescription>
            {creating
              ? "项目是仓库、成员与权限的边界；创建后可随时归档。"
              : "修改项目信息与可见性；归档/删除只允许项目 owner。"}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-[12.5px]">
            <span className="text-muted-foreground">名称</span>
            <Input value={name} onChange={(e) => setName(e.target.value)} disabled={!canEditMeta || busy} placeholder="例如：支付平台" />
          </label>
          <label className="flex flex-col gap-1 text-[12.5px]">
            <span className="text-muted-foreground">slug（可选，小写字母/数字/连字符，2–64）</span>
            <Input value={slug} onChange={(e) => setSlug(e.target.value)} disabled={!canEditMeta || busy} placeholder="例如：payments" className="font-mono" />
          </label>
          <label className="flex flex-col gap-1 text-[12.5px]">
            <span className="text-muted-foreground">描述</span>
            <Textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              disabled={!canEditMeta || busy}
              placeholder="这个项目负责什么…"
              className="min-h-16 text-[13px]"
            />
          </label>
          <div className="flex flex-col gap-1.5 text-[12.5px]">
            <span className="text-muted-foreground">可见性</span>
            <div className="flex flex-col gap-1.5">
              {VISIBILITIES.map((v) => (
                <label
                  key={v.value}
                  className={`flex cursor-pointer items-start gap-2 rounded-md border px-3 py-2 transition-colors ${
                    visibility === v.value ? "border-primary/50 bg-primary/5" : "hover:bg-muted/40"
                  } ${canEditVisibility && !busy ? "" : "pointer-events-none opacity-60"}`}
                >
                  <input
                    type="radio"
                    name="visibility"
                    className="mt-1"
                    checked={visibility === v.value}
                    onChange={() => setVisibility(v.value)}
                    disabled={!canEditVisibility || busy}
                  />
                  <span className="flex flex-col">
                    <span className="font-medium">{v.label}</span>
                    <span className="text-[11.5px] text-muted-foreground">{v.hint}</span>
                  </span>
                </label>
              ))}
            </div>
          </div>

          {!creating && project && (
            <div className="flex items-center gap-2 rounded-md border border-border/70 bg-muted/20 px-3 py-2 text-[12px]">
              <span className="text-muted-foreground">项目 ID</span>
              <span className="font-mono">{project.id}</span>
              {project.archived && <Badge variant="outline" className="ml-auto">已归档</Badge>}
            </div>
          )}

          {err && <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{err}</div>}
          {notice && <div className="rounded-md border border-emerald-500/30 bg-emerald-500/10 px-3 py-2 text-[12.5px] text-emerald-700 dark:text-emerald-400">{notice}</div>}
        </div>

        <DialogFooter className="flex-wrap gap-2">
          {!creating && (
            <div className="mr-auto flex gap-2">
              <Button variant="outline" size="sm" onClick={doArchive} disabled={!canArchive || busy} title={canArchive ? undefined : "需 owner 且未归档"}>
                <Archive className="size-3.5" /> 归档
              </Button>
              <Button variant="outline" size="sm" onClick={doDelete} disabled={!canDelete || busy} title={canDelete ? "需先归档且无仓库" : "仅 owner 可删除"} className="text-destructive">
                <Trash2 className="size-3.5" /> 删除
              </Button>
            </div>
          )}
          <Button variant="outline" size="sm" onClick={() => onOpenChange(false)} disabled={busy}>取消</Button>
          <Button
            size="sm"
            onClick={doSave}
            disabled={busy || (canEditMeta && !name.trim()) || (creating && !canCreate) || (!creating && !canEditMeta && !canEditVisibility)}
          >
            {creating ? "创建" : "保存"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
