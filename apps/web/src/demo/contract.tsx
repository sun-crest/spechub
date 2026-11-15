import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/** 共享契约渲染（ApiDetail 详情页 + H5 EditorView 实时预览 复用） */

export type SchemaNode = Record<string, unknown> & { type?: string; $ref?: string; properties?: Record<string, SchemaNode>; items?: SchemaNode; description?: string; enum?: unknown[]; format?: string };

/** 递归渲染 OpenAPI schema */
export function SchemaView({ node, depth = 0 }: { node?: SchemaNode | null; depth?: number }) {
  if (!node) return null;
  if (node.$ref) {
    return <span className="font-mono italic text-violet-600 dark:text-violet-400">{node.$ref}</span>;
  }
  if (node.type === "array") {
    return (
      <div className="py-1">
        <span className="font-mono text-muted-foreground">array&lt;</span>
        <SchemaView node={node.items} depth={depth} />
        <span className="font-mono text-muted-foreground">&gt;</span>
      </div>
    );
  }
  if (node.type === "object" && node.properties) {
    if (depth > 3) return <span className="font-mono text-muted-foreground">object…</span>;
    return (
      <div className={depth ? "ml-4 border-l border-border pl-3" : ""}>
        {Object.entries(node.properties).map(([k, v]) => (
          <div key={k} className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 py-0.5">
            <span className="font-mono text-[12.5px]">{k}</span>
            <span className="font-mono text-[12px] text-muted-foreground">
              {v.type ?? (v.$ref ? "ref" : "*")}
              {v.enum ? ` · enum[${v.enum.join("|")}]` : ""}
            </span>
            {typeof v.description === "string" && <span className="font-sans text-[12px] text-muted-foreground">— {v.description}</span>}
            {v.type === "object" && v.properties && <SchemaView node={v} depth={depth + 1} />}
            {v.type === "array" && v.items && <SchemaView node={v} depth={depth + 1} />}
          </div>
        ))}
      </div>
    );
  }
  return (
    <span className="py-1 font-mono text-[12px] text-muted-foreground">
      {node.type ?? "*"}
      {node.format ? `·${node.format}` : ""}
      {node.enum ? ` · enum[${node.enum.join("|")}]` : ""}
      {typeof node.description === "string" ? <span className="ml-2 font-sans text-muted-foreground">— {node.description}</span> : null}
    </span>
  );
}

export interface SpecOp {
  summary?: string;
  description?: string;
  parameters?: any[];
  responses?: Record<string, { description?: string; content?: Record<string, { schema?: SchemaNode }>; schema?: SchemaNode }>;
}

/** 单个 operation 的契约渲染参数（Parameters 表 + Responses schema 树） */
export function OperationContract({ op }: { op: SpecOp }) {
  const responses = op.responses ? Object.entries(op.responses) : [];
  return (
    <>
      <div className="rounded-lg border bg-card p-5">
        <h3 className="mb-3 text-sm font-semibold">Parameters <span className="ml-1 text-xs font-normal text-muted-foreground">{(op.parameters ?? []).length}</span></h3>
        {op.parameters?.length ? (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                {["名称", "位置", "类型", "必需", "说明"].map((h) => (
                  <TableHead key={h} className="text-[11px] font-semibold uppercase text-muted-foreground">{h}</TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {op.parameters.map((p) => (
                <TableRow key={String(p.name)}>
                  <TableCell className="font-mono">{String(p.name)}</TableCell>
                  <TableCell className="text-muted-foreground">{String(p["in"] ?? "")}</TableCell>
                  <TableCell className="font-mono text-muted-foreground">{String((p.schema as SchemaNode | undefined)?.type ?? "") || (!p.schema ? (p.type as string) ?? "*" : "")}</TableCell>
                  <TableCell>{p.required ? <span className="font-mono text-destructive">*</span> : <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell className="text-muted-foreground">{typeof p.description === "string" ? p.description : ""}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : (
          <p className="py-3 text-sm text-muted-foreground">无参数</p>
        )}
      </div>

      <div className="rounded-lg border bg-card p-5">
        <h3 className="mb-3 text-sm font-semibold">Responses <span className="ml-1 text-xs font-normal text-muted-foreground">{responses.length}</span></h3>
        <div className="flex flex-col gap-4">
          {responses.map(([code, r]) => {
            const schema = r.content?.["application/json"]?.schema ?? r.schema;
            return (
              <div key={code} className="rounded-md border border-border/70">
                <div className="flex items-center gap-2 border-b border-border/60 px-4 py-2">
                  <span className="rounded bg-emerald-500/15 px-1.5 font-mono text-[11px] font-semibold text-emerald-600 dark:text-emerald-400">{code}</span>
                  <span className="text-[12.5px] text-muted-foreground">{r.description ?? ""}</span>
                </div>
                {schema && (
                  <div className="px-4 py-2.5">
                    <SchemaView node={schema} />
                  </div>
                )}
              </div>
            );
          })}
          {responses.length === 0 && <p className="py-3 text-sm text-muted-foreground">无响应定义</p>}
        </div>
      </div>
    </>
  );
}