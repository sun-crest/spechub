import { useCallback, useEffect, useMemo, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { getDoc, listDocComments, addDocComment, type Doc, type DocComment } from "@/lib/api";

/** 从 markdown 提取标题做 TOC（仅 ## / ###；锚点 = 标题转 ASCII slug） */
interface Toc { id: string; title: string; level: 2 | 3; children: Toc[] }

function slug(s: string): string {
  return s.toLowerCase().trim().replace(/[^\w一-鿿]+/g, "-").replace(/^-+|-+$/g, "");
}

function isHeading(line: string): 2 | 3 | null {
  if (/^##\s+/.test(line) && !/^###/.test(line)) return 2;
  if (/^###\s+/.test(line)) return 3;
  return null;
}

function extractToc(md: string): Toc[] {
  const out: Toc[] = [];
  let stack2: Toc | null = null;
  for (const line of md.split("\n")) {
    const level = isHeading(line);
    if (!level) continue;
    const title = line.replace(/^#{2,3}\s+/, "").replace(/[*_`]/g, "").trim();
    const toc = { id: slug(title), title, level, children: [] as Toc[] };
    if (level === 2) { out.push(toc); stack2 = toc; }
    else if (level === 3) { stack2 ? stack2.children.push(toc) : out.push(toc); }
  }
  return out;
}
// 去除 BOM：markdown 源可能以 U+FEFF 开头（UTF-8 BOM），影响首行标题识别
function stripBom(s: string): string { return s.charCodeAt(0) === 0xfeff ? s.slice(1) : s; }

/** K3 —— AI 文档（Doc 域）阅读页：目录树渲染 / Markdown + TOC / 评论 / Doc↔Api 链接 */
export function DocPanel({ docId, canComment, meUsername, onBack, onNavigateApi }: {
  docId: number;
  canComment: boolean;
  meUsername: string | null;
  onBack: () => void;
  onNavigateApi: (path: string) => void;
}) {
  const [doc, setDoc] = useState<Doc | null>(null);
  const [comments, setComments] = useState<DocComment[]>([]);
  const [commentBody, setCommentBody] = useState("");
  const [msg, setMsg] = useState<string | null>(null);

  const loadComments = useCallback((id: number) => void listDocComments(id).then(setComments).catch(() => setComments([])), []);

  useEffect(() => {
    let alive = true;
    getDoc(docId).then((d) => { if (alive) setDoc(d); }).catch((e) => alive && setMsg(String(e)));
    loadComments(docId);
    return () => { alive = false; };
  }, [docId, loadComments]);

  const toc = useMemo(() => (doc ? extractToc(doc.markdown) : []), [doc]);

  function jumpTo(id: string) {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  async function postComment() {
    if (!commentBody.trim()) return;
    setMsg(null);
    try {
      const c = await addDocComment(docId, commentBody.trim());
      setComments((cs) => [...cs, c]);
      setCommentBody("");
    } catch (e) { setMsg(String(e)); }
  }

  if (!doc) {
    return (
      <div className="p-8">
        <Button variant="ghost" size="sm" onClick={onBack}>← 文档</Button>
        <div className="mt-6 text-sm text-muted-foreground">{msg ?? "加载中…"}</div>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col gap-5 overflow-auto p-8">
      <nav className="flex items-center gap-2 text-[13px] text-muted-foreground">
        <Button variant="ghost" size="sm" onClick={onBack}>← 文档</Button>
        <span className="truncate font-mono">{doc.path}</span>
      </nav>

      <div className="flex items-start gap-8">
        <article className="min-w-0 flex-1">
          <h1 className="mb-6 text-2xl font-semibold tracking-tight">{doc.title}</h1>
          <div className="doc-prose text-[14px] leading-7 text-foreground/90">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                h2: ({ children }) => <h2 id={slug(String(children))}>{children}</h2>,
                h3: ({ children }) => <h3 id={slug(String(children))}>{children}</h3>,
                a: ({ href, children }) => {
                  // Doc↔Api 链接：front-matter related 渲染成 `[`/pets`](#/api/pets)` → 跳接口详情
                  if (href?.startsWith("#/api/")) {
                    const path = href.slice(6);
                    return (
                      <button
                        className="font-mono text-link underline underline-offset-2"
                        onClick={() => onNavigateApi(path)}
                        title={`打开接口 ${href.slice(5)}`}
                      >
                        {children}
                      </button>
                    );
                  }
                  return <a className="text-link underline underline-offset-2" href={href}>{children}</a>;
                },
              }}
            >
              {stripBom(doc.markdown)}
            </ReactMarkdown>
          </div>

          {/* 评论（K3 轻评论） */}
          <div className="mt-10 rounded-lg border bg-card p-5">
            <h3 className="mb-3 text-sm font-semibold">评论（{comments.length}）</h3>
            {msg && <p className="mb-2 text-[12px] text-muted-foreground">{msg}</p>}
            <div className="flex flex-col gap-2">
              {comments.map((c) => (
                <div key={c.id} className="rounded-md border bg-muted/20 px-3 py-2">
                  <span className="font-mono text-[12.5px] font-medium">{c.author}</span>
                  <span className="ml-2 font-mono text-[11px] text-muted-foreground">{c.createdAt ?? ""}</span>
                  <p className="mt-1 whitespace-pre-wrap text-[13px]">{c.body}</p>
                </div>
              ))}
              {comments.length === 0 && <p className="py-1 text-[12.5px] text-muted-foreground">暂无评论</p>}
            </div>
            {canComment ? (
              <div className="mt-3 flex flex-col gap-2">
                <Textarea value={commentBody} onChange={(e) => setCommentBody(e.target.value)} rows={2} placeholder="对这份 AI 分析的疑问 / 补充…" className="text-[13px]" />
                <Button size="sm" variant="outline" onClick={() => void postComment()} disabled={!commentBody.trim()} className="w-fit">发表评论</Button>
              </div>
            ) : (
              <p className="mt-2 text-[12px] text-muted-foreground">登录后可评论（meUsername 用于作者标识）。</p>
            )}
          </div>
        </article>

        {/* 右列标题索引 */}
        <nav className="sticky top-10 hidden self-start lg:block" aria-label="文档标题索引">
          {toc.length > 0 && (
            <>
              <div className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">本页目录</div>
              <ol className="flex flex-col gap-1 border-l border-border pl-3">
                {toc.map((s) => (
                  <li key={s.id}>
                    <button onClick={() => jumpTo(s.id)} className="block py-0.5 text-left text-[12.5px] text-muted-foreground transition-colors hover:text-primary">
                      {s.title}
                    </button>
                    {s.children.length > 0 && (
                      <ul className="ml-3 flex flex-col">
                        {s.children.map((x) => (
                          <li key={x.id}>
                            <button onClick={() => jumpTo(x.id)} className="block py-0.5 text-left text-[12px] text-muted-foreground/70 transition-colors hover:text-primary">
                              {x.title}
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </li>
                ))}
              </ol>
            </>
          )}
        </nav>
      </div>
    </div>
  );
}