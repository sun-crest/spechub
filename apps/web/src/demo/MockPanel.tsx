import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { addMockRule, deleteMockRule, listMockRules, mockPreview, type MockPreview, type MockRule } from "@/lib/api";

/** I2/I3 Mock 预览 + 规则管理（value / pattern / script 三档） */
export function MockPanel({ apiId, repoId, path, canWrite }: {
  apiId: number;
  repoId: number;
  path: string;
  canWrite: boolean;
}) {
  const [rules, setRules] = useState<MockRule[]>([]);
  const [preview, setPreview] = useState<MockPreview | null>(null);
  const [busy, setBusy] = useState(false);

  const [newField, setNewField] = useState("");
  const [newType, setNewType] = useState("value");
  const [newValue, setNewValue] = useState("");
  const [msg, setMsg] = useState<string | null>(null);

  const loadRules = useCallback(() => {
    void listMockRules(repoId).then(setRules).catch(() => setRules([]));
  }, [repoId]);

  useEffect(() => { loadRules(); }, [loadRules]);

  async function doPreview() {
    setBusy(true);
    setMsg(null);
    try { setPreview(await mockPreview(apiId)); } catch (e) { setMsg(String(e)); } finally { setBusy(false); }
  }

  async function doAdd() {
    if (!newField.trim()) return;
    try {
      await addMockRule(repoId, { fieldPattern: newField.trim(), ruleType: newType, ruleValue: newValue.trim(), apiId });
      setNewField(""); setNewValue("");
      setMsg("规则已保存");
      setPreview(null);
      loadRules();
    } catch (e) { setMsg(String(e)); }
  }

  async function doDelete(id: number) {
    try { await deleteMockRule(id); setPreview(null); loadRules(); } catch (e) { setMsg(String(e)); }
  }

  return (
    <div className="rounded-lg border bg-card p-5">
      <h3 className="flex items-center gap-2 text-sm font-semibold">
        Mock 预览 <Badge variant="secondary" className="font-mono text-[10px]">spec 生成</Badge>
      </h3>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <code className="rounded border bg-muted/50 px-2 py-0.5 font-mono text-[12px]">/mock/{repoId}{path}</code>
        <Button size="sm" variant="outline" onClick={() => void doPreview()} disabled={busy}>{busy ? "生成中…" : "预览"}</Button>
      </div>
      <p className="mt-1.5 text-[11.5px] text-muted-foreground">
        真实 mock 服务即上面的 URL，任意 HTTP 方法命中后按 spec 生成样例；consumer 默认读 release 快照，未发布时回退 draft。
      </p>

      {msg && <p className="mt-2 text-[12px] text-muted-foreground">{msg}</p>}

      {preview && (
        <div className="mt-3 overflow-hidden rounded-md border">
          <div className="border-b bg-muted/40 px-3 py-1.5 text-[12px]">
            <span className="font-mono font-semibold text-green-600 dark:text-green-400">{preview.status}</span>
            <Badge variant="outline" className="ml-2 font-mono text-[10px]">{preview.stage}</Badge>
            <span className="ml-2 font-mono text-[11px] text-muted-foreground">{preview.mediaType}</span>
          </div>
          <pre className="max-h-80 overflow-auto bg-card px-3 py-2 text-[12px]">{preview.body}</pre>
        </div>
      )}

      <div className="mt-5 border-t border-border/60 pt-4">
        <h4 className="text-[12px] font-semibold text-muted-foreground">Mock 规则（本接口 · {rules.length}）</h4>
        {rules.length > 0 && (
          <ul className="mt-2 flex flex-col gap-1">
            {rules.map((r) => (
              <li key={r.id ?? r.fieldPattern} className="flex items-center gap-2 rounded border bg-muted/30 px-2 py-1 text-[12px]">
                <Badge variant="secondary" className="font-mono text-[10px]">{r.ruleType}</Badge>
                <span className="font-mono">{r.fieldPattern}</span>
                <span className="min-w-0 flex-1 truncate text-muted-foreground">= {r.ruleValue}</span>
                <button className="text-muted-foreground hover:text-destructive" onClick={() => r.id != null && doDelete(r.id)} aria-label="删除规则">✕</button>
              </li>
            ))}
          </ul>
        )}

        {canWrite && (
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Input value={newField} onChange={(e) => setNewField(e.target.value)} placeholder="字段路径，如 address.city 或 data.items.*.name" className="w-60 font-mono text-[12px]" />
            <select
              value={newType}
              onChange={(e) => setNewType(e.target.value)}
              className="h-9 rounded-md border bg-background px-2 font-mono text-[12px]"
            >
              <option value="value">value（固定值）</option>
              <option value="pattern">pattern（格式样例）</option>
              <option value="script">script（表达式）</option>
            </select>
            <Input value={newValue} onChange={(e) => setNewValue(e.target.value)} placeholder='值：如 "上海" / email / RANDOM_INT(1,100)' className="w-56 font-mono text-[12px]" />
            <Button size="sm" variant="outline" onClick={() => void doAdd()}>添加</Button>
          </div>
        )}
        {!canWrite && <p className="mt-2 text-[11.5px] text-muted-foreground">只读视图：登录与发布管理后可用规则覆盖 mock 值。</p>}
      </div>
    </div>
  );
}