import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { tryApi, type TryResponse } from "@/lib/api";
import type { SpecOp } from "./contract";

/** I1 在线调试：读 spec 构造请求 → 后端代理发真实请求 */
export function TryPanel({ apiId, method, path, op }: {
  apiId: number;
  method: string;
  path: string;
  op: SpecOp;
}) {
  const pathParams = (op.parameters ?? []).filter((p) => String(p["in"]) === "path");
  const queryParams = (op.parameters ?? []).filter((p) => String(p["in"]) === "query");
  const headerParams = (op.parameters ?? []).filter((p) => String(p["in"]) === "header");
  const hasBody = !["GET", "HEAD", "DELETE"].includes(method.toUpperCase());

  const [baseUrl, setBaseUrl] = useState("");
  const [pathVals, setPathVals] = useState<Record<string, string>>({});
  const [queryVals, setQueryVals] = useState<Record<string, string>>({});
  const [headerVals, setHeaderVals] = useState<Record<string, string>>({});
  const [body, setBody] = useState("{}");
  const [resp, setResp] = useState<TryResponse | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function send() {
    if (!baseUrl.trim()) { setErr("请填目标服务 baseUrl"); return; }
    setBusy(true); setErr(null); setResp(null);
    try {
      const r = await tryApi(apiId, {
        baseUrl: baseUrl.trim(),
        pathParams: pathVals,
        queryParams: queryVals,
        headers: headerVals,
        body: hasBody && body.trim() ? body : null,
      });
      setResp(r);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="rounded-lg border bg-card p-5">
      <h3 className="text-sm font-semibold">Try it out <span className="ml-1 font-mono text-[10px] text-muted-foreground">真实请求 · 目标是你的服务</span></h3>

      <div className="mt-4 flex flex-col gap-3">
        <div>
          <label className="mb-1 block text-[12px] font-medium text-muted-foreground">目标服务 baseUrl</label>
          <div className="flex items-center gap-2">
            <Input
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="https://api.example.com"
              className="flex-1 font-mono text-[13px]"
            />
            <kbd className="shrink-0 rounded border bg-muted px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground">{method}</kbd>
          </div>
          <p className="mt-1 text-[11px] text-muted-foreground">请求经 SpecHub 后端代理发出；目标主机的可用性/安全由你负责。</p>
        </div>

        {pathParams.length > 0 && (
          <div>
            <label className="mb-1 block text-[12px] font-medium text-muted-foreground">Path 参数</label>
            <div className="flex flex-wrap gap-2">
              {pathParams.map((p) => (
                <Input
                  key={String(p.name)}
                  value={pathVals[String(p.name)] ?? ""}
                  onChange={(e) => setPathVals((v) => ({ ...v, [String(p.name)]: e.target.value }))}
                  placeholder={`{${String(p.name)}}${p.required ? "" : "（可选）"}`}
                  className="w-44 font-mono text-[13px]"
                />
              ))}
            </div>
          </div>
        )}

        {queryParams.length > 0 && (
          <div>
            <label className="mb-1 block text-[12px] font-medium text-muted-foreground">Query 参数</label>
            <div className="flex flex-wrap gap-2">
              {queryParams.map((p) => (
                <Input
                  key={String(p.name)}
                  value={queryVals[String(p.name)] ?? ""}
                  onChange={(e) => setQueryVals((v) => ({ ...v, [String(p.name)]: e.target.value }))}
                  placeholder={`${String(p.name)}${p.required ? "" : "（可选）"}`}
                  className="w-44 font-mono text-[13px]"
                />
              ))}
            </div>
          </div>
        )}

        {headerParams.length > 0 && (
          <div>
            <label className="mb-1 block text-[12px] font-medium text-muted-foreground">Header 参数</label>
            <div className="flex flex-wrap gap-2">
              {headerParams.map((p) => (
                <Input
                  key={String(p.name)}
                  value={headerVals[String(p.name)] ?? ""}
                  onChange={(e) => setHeaderVals((v) => ({ ...v, [String(p.name)]: e.target.value }))}
                  placeholder={String(p.name)}
                  className="w-44 font-mono text-[13px]"
                />
              ))}
            </div>
          </div>
        )}

        {hasBody && (
          <div>
            <label className="mb-1 block text-[12px] font-medium text-muted-foreground">请求体（JSON）</label>
            <Textarea
              value={body}
              onChange={(e) => setBody(e.target.value)}
              rows={4}
              className="font-mono text-[13px]"
            />
          </div>
        )}

        <div className="flex items-center gap-3">
          <Button size="sm" onClick={() => void send()} disabled={busy}>
            {busy ? "发送中…" : `发送 ${method}`}
          </Button>
          {path && <span className="font-mono text-[12px] text-muted-foreground">{path}</span>}
          {err && <span className="text-[12px] text-destructive">{err}</span>}
        </div>
      </div>

      {resp && (
        <div className="mt-4 overflow-hidden rounded-md border">
          <div className="flex items-center gap-2 border-b bg-muted/40 px-3 py-1.5 text-[12px]">
            <span className={`font-mono font-semibold ${resp.status < 400 ? "text-green-600 dark:text-green-400" : "text-red-600 dark:text-red-400"}`}>{resp.status}</span>
            <span className="text-muted-foreground">{(resp.durationMs / 1000).toFixed(2)}s</span>
            {resp.truncated && <span className="text-amber-600 dark:text-amber-400">响应体已截断</span>}
          </div>
          {resp.headers && Object.keys(resp.headers).length > 0 && (
            <div className="border-b bg-card px-3 py-1.5">
              {Object.entries(resp.headers).map(([k, vs]) => (
                <div key={k} className="flex gap-2 text-[11.5px]">
                  <span className="shrink-0 font-mono text-muted-foreground">{k}:</span>
                  <span className="min-w-0 break-all font-mono">{vs.join("; ")}</span>
                </div>
              ))}
            </div>
          )}
          <pre className="max-h-80 overflow-auto bg-card px-3 py-2 text-[12px]">{resp.body}</pre>
        </div>
      )}
    </div>
  );
}