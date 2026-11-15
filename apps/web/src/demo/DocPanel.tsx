import { useEffect, useMemo, useRef } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Badge } from "@/components/ui/badge";
import { MethodBadge } from "./method";
import { DOCS } from "./data";

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

export function DocPanel({ docId }: { docId: string }) {
  const doc = DOCS.find((d) => d.id === docId) ?? DOCS[0];
  const topRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    topRef.current?.scrollTo({ top: 0 });
  }, [docId]);

  const toc = useMemo(() => extractToc(doc.markdown), [doc]);

  function jumpTo(id: string) {
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  return (
    <div ref={topRef} className="h-full min-h-0 overflow-auto">
      <div className="mx-auto grid max-w-[1120px] grid-cols-[minmax(0,1fr)_208px] gap-10 px-10 py-10">
        <article className="min-w-0">
          <h1 className="text-[26px] font-semibold tracking-tight">{doc.title}</h1>
          <p className="mt-2 mb-6 text-[13.5px] text-muted-foreground">{doc.lede}</p>

          <div className="prose prose-sm dark:prose-invert prose-headings:scroll-mt-8 prose-h2:mt-7 prose-h2:mb-2 prose-h2:text-lg prose-h2:font-semibold prose-h3:text-[15px] prose-p:mb-2.5 prose-p:leading-relaxed prose-p:text-[14px] prose-code:rounded prose-code:bg-muted prose-code:px-1 prose-code:py-0.5 prose-code:font-mono prose-code:text-[12.5px] prose-pre:my-4 prose-pre:overflow-x-auto prose-pre:rounded-lg prose-pre:border prose-pre:bg-muted/50 prose-pre:p-4 prose-pre:font-mono prose-pre:text-[12.5px] prose-table:my-4 prose-table:border-collapse prose-th:border prose-th:px-3 prose-th:py-1.5 prose-th:text-left prose-th:text-[12px] prose-td:border prose-td:px-3 prose-td:py-1.5 prose-td:text-[13px] prose-ul:mb-2.5 prose-ol:mb-2.5 prose-strong:font-semibold prose-a:text-link max-w-none">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                h2: ({ children }) => (
                  <h2 id={slug(String(children))}>{children}</h2>
                ),
                h3: ({ children }) => (
                  <h3 id={slug(String(children))}>{children}</h3>
                ),
                a: (props) => <a className="text-link underline underline-offset-2" {...props} />,
              }}
            >
              {doc.markdown}
            </ReactMarkdown>
          </div>

          <div className="mt-8 border-t border-border/70 pt-5">
            <h2 className="mb-2 text-[15px] font-semibold">关联接口</h2>
            <div className="flex flex-wrap gap-2">
              {doc.related.map((r) => (
                <Badge key={r.path} variant="outline" className="gap-2 py-1 pl-1 pr-3 text-muted-foreground">
                  <MethodBadge method={r.method} className="!min-w-0 !px-1.5 text-[10px]" />
                  <span className="font-mono text-[12px]">{r.path}</span>
                </Badge>
              ))}
            </div>
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
                    <button
                      onClick={() => jumpTo(s.id)}
                      className="block py-0.5 text-left text-[12.5px] text-muted-foreground transition-colors hover:text-primary"
                    >
                      {s.title}
                    </button>
                    {s.children.length > 0 && (
                      <ul className="ml-3 flex flex-col">
                        {s.children.map((x) => (
                          <li key={x.id}>
                            <button
                              onClick={() => jumpTo(x.id)}
                              className="block py-0.5 text-left text-[12px] text-muted-foreground/70 transition-colors hover:text-primary"
                            >
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