import { useCallback, useEffect, useMemo, useState } from "react";
import Editor, { DiffEditor } from "@monaco-editor/react";
import { parse as yamlParse } from "yaml";
import { ChevronLeft, RefreshCw, Save } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { MethodBadge } from "./method";
import { OperationContract, type SpecOp } from "./contract";

import "@/lib/monaco";
import { listSpecs, readSpec, writeSpec, type Repo, type WriteSpecResp } from "@/lib/api";

/**
 * H4+H5 双栏编辑器：YAML spec 编辑 | 实时渲染预览（渲染引擎复用详情页契约区）。
 * - 文件来源 = 工作树 `GET /repos/{id}/specs|spec`（SpecWriter 现成端点）
 * - 保存 = 强制 diff 确认（O1 拍板）：DiffEditor 对比 original vs 当前 → commit message → PUT /repos/{id}/spec
 */

export interface EditingEntry { repoId: number; path?: string | null }

export function EditorView({
  entry, repos, onBack, onSaved,
}: {
  entry: EditingEntry;
  repos: Repo[];
  onBack: () => void;
  onSaved: (r: WriteSpecResp) => void;
}) {
  return <EditorFrame key={`${entry.repoId}:${entry.path ?? ""}`} entry={entry} repos={repos} onBack={onBack} onSaved={onSaved} />;
}

function EditorFrame({
  entry, repos, onBack, onSaved,
}: {
  entry: EditingEntry;
  repos: Repo[];
  onBack: () => void;
  onSaved: (r: WriteSpecResp) => void;
}) {
  const [repoId, setRepoId] = useState(entry.repoId);
  const [specs, setSpecs] = useState<string[]>([]);
  const [path, setPath] = useState(entry.path ?? "");
  const [original, setOriginal] = useState("");
  const [content, setContent] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // diff 确认
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [commitMsg, setCommitMsg] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [previewKey, setPreviewKey] = useState("");

  const repo = repos.find((r) => r.id === repoId);
  const dirty = content !== original;
  const lang = path.endsWith(".json") ? "json" : "yaml";

  // 加载 spec 文件列表
  useEffect(() => {
    let alive = true;
    setLoading(true); setError(null);
    listSpecs(repoId)
      .then((paths) => {
        if (!alive) return;
        setSpecs(paths);
        if (paths.length) {
          setPath((p) => (p && paths.includes(p) ? p : paths[0]));
        } else {
          setError("该仓没有 spec 文件");
        }
      })
      .catch((e) => alive && setError(String(e)))
      .finally(() => alive && setLoading(false));
    return () => { alive = false; };
  }, [repoId]);

  // 加载当前文件内容（记 original，供 diff 对比）
  useEffect(() => {
    if (!path) return;
    let alive = true;
    setLoading(true); setError(null);
    readSpec(repoId, path)
      .then((text) => { if (alive) { setOriginal(text); setContent(text); setCommitMsg(`update ${path}`); } })
      .catch((e) => alive && setError(String(e)))
      .finally(() => alive && setLoading(false));
    return () => { alive = false; };
  }, [repoId, path]);

  // 实时预览：解析 YAML → 顶层 paths 概览
  type PreviewResult =
    | { kind: "doc"; info: Record<string, unknown> | undefined; ops: { method: string; path: string; op: SpecOp | null }[] }
    | { kind: "error"; error: string };
  const parsed: PreviewResult | null = useMemo(() => {
    if (!content) return null;
    try {
      const doc = yamlParse(content) as Record<string, unknown> | null;
      if (doc && (doc as Record<string, unknown>).paths) {
        const paths = (doc as Record<string, any>).paths ?? {};
        const ops: { method: string; path: string; op: SpecOp | null }[] = [];
        for (const [p, item] of Object.entries(paths)) {
          if (!item || typeof item !== "object") continue;
          for (const method of ["get", "post", "put", "delete", "patch"]) {
            const op = (item as Record<string, SpecOp>)[method];
            if (op) ops.push({ method: method.toUpperCase(), path: p, op });
          }
        }
        return { kind: "doc", info: doc.info as Record<string, unknown> | undefined, ops };
      }
      return { kind: "doc", info: undefined, ops: [] };
    } catch (e) {
      return { kind: "error", error: e instanceof Error ? e.message : String(e) };
    }
  }, [content]);

  const handleSave = useCallback(() => {
    setSaveError(null);
    setConfirmOpen(true);
  }, []);

  const confirmSave = useCallback(async () => {
    setSaving(true); setSaveError(null);
    try {
      const r = await writeSpec(repoId, path, content);
      setOriginal(content);
      setConfirmOpen(false);
      onSaved(r);
    } catch (e) {
      setSaveError(String(e));
    } finally {
      setSaving(false);
    }
  }, [repoId, path, content, onSaved]);

  const ops = parsed?.kind === "doc" ? parsed.ops : [];
  const previewSelected = ops.find((o) => `${o.method} ${o.path}` === previewKey);

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* 顶栏：返回 + 文件选择 + 保存 */}
      <div className="flex h-14 shrink-0 items-center gap-3 border-b border-border/70 px-4">
        <Button variant="ghost" size="sm" onClick={onBack}><ChevronLeft className="size-4" /> 返回</Button>
        <select
          value={repoId}
          onChange={(e) => setRepoId(Number(e.target.value))}
          className="h-8 rounded-md border border-border bg-background px-2 text-[13px]"
          aria-label="选择仓库"
        >
          {repos.map((r) => <option key={r.id} value={r.id}>{repoDisplay(r.url)}</option>)}
        </select>
        <select
          value={path}
          onChange={(e) => setPath(e.target.value)}
          className="h-8 max-w-72 flex-1 truncate rounded-md border border-border bg-background px-2 font-mono text-[12.5px]"
          aria-label="选择 spec 文件"
        >
          {specs.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        {loading && <Badge variant="outline" className="text-muted-foreground">加载中…</Badge>}
        {error && <span className="truncate text-[12px] text-amber-600 dark:text-amber-400">{error}</span>}
        {repo && !error && <span className="ml-auto truncate text-[12px] text-muted-foreground">工作树 @ {repo.branch}</span>}
        <Button size="sm" onClick={handleSave} disabled={!dirty || loading || !path} className="ml-2 shrink-0">
          <Save className="size-3.5" /> {dirty ? "保存" : "已保存"}
        </Button>
      </div>

      {/* 双栏主体 */}
      <div className="grid min-h-0 flex-1 grid-cols-2">
        {/* 左：YAML/JSON 编辑器 */}
        <Editor
          language={lang}
          value={content}
          onChange={(v) => setContent(v ?? "")}
          options={{
            fontSize: 13, minimap: { enabled: false }, scrollBeyondLastLine: false,
            wordWrap: "on", renderWhitespace: "none", tabSize: 2, automaticLayout: true,
          }}
        />
        {/* 右：实时渲染预览（复用契约渲染引擎） */}
        <div className="flex min-h-0 flex-col border-l border-border/70">
          <div className="flex items-center gap-2 border-b border-border/60 px-4 py-2 text-[12.5px] text-muted-foreground">
            <RefreshCw className="size-3.5" /> 实时预览
            {parsed?.kind === "error" && <span className="ml-auto text-destructive">YAML 解析失败</span>}
          </div>
          <div className="min-h-0 flex-1 overflow-auto p-4">
            {parsed?.kind === "error" ? (
              <pre className="rounded-md border border-destructive/30 bg-destructive/5 p-3 font-mono text-[12px] text-destructive">{parsed.error}</pre>
            ) : ops.length > 0 ? (
              <>
                <div className="rounded-lg border bg-card">
                  {ops.map((o) => (
                    <button
                      key={`${o.method} ${o.path}`}
                      onClick={() => setPreviewKey(`${o.method} ${o.path}`)}
                      className={`flex w-full items-center gap-2.5 px-4 py-2 text-left transition-colors hover:bg-accent ${previewSelected === o ? "bg-accent" : ""}`}
                    >
                      <MethodBadge method={o.method} className="!min-w-0 !px-1.5 text-[10px]" />
                      <span className="font-mono text-[13px] text-link">{o.path}</span>
                      {o.op?.summary && <span className="ml-auto truncate text-[12.5px] text-muted-foreground">{o.op.summary}</span>}
                    </button>
                  ))}
                </div>
                {previewSelected?.op && (
                  <div className="mt-4 flex flex-col gap-4">
                    <OperationContract op={previewSelected.op} />
                  </div>
                )}
              </>
            ) : (
              <p className="py-6 text-center text-[13px] text-muted-foreground">{loading ? "加载中…" : "无 paths（非 OpenAPI 文档？）"}</p>
            )}
          </div>
        </div>
      </div>

      {/* 保存 → 强制 diff 确认 */}
      <Dialog open={confirmOpen} onOpenChange={(open) => { setConfirmOpen(open); if (!open) setSaveError(null); }}>
        <DialogContent className="max-h-[85vh] w-full max-w-3xl overflow-auto">
          <DialogHeader>
            <DialogTitle>保存并提交（写回真源仓）</DialogTitle>
            <DialogDescription>
              {repo?.branch ?? ""} · <code className="font-mono">{path}</code> — 确认差异后写入 commit 并 push
            </DialogDescription>
          </DialogHeader>

          {saveError && !saving && <ErrorBox msg={saveError} />}

          <div className="h-[40vh] min-h-64 overflow-hidden rounded-lg border">
            <DiffEditor
              height="100%" language={lang} original={original} modified={content} theme="light"
              options={{ readOnly: true, minimap: { enabled: false }, automaticLayout: true }}
            />
          </div>

          <div className="grid grid-cols-[auto_1fr] items-center gap-2">
            <label htmlFor="commit-msg" className="whitespace-nowrap text-[13px] text-muted-foreground">commit message</label>
            <Input id="commit-msg" value={commitMsg} onChange={(e) => setCommitMsg(e.target.value)} className="font-mono text-[13px]" />
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmOpen(false)} disabled={saving}>取消</Button>
            <Button onClick={() => void confirmSave()} disabled={saving || !commitMsg.trim()}>
              {saving ? "提交中…" : "提交并推送"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ErrorBox({ msg }: { msg: string }) {
  return <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-[12.5px] text-destructive">{msg}</div>;
}

function repoDisplay(url: string): string {
  return url.replace(/\.git$/, "").split("/").pop() ?? url;
}