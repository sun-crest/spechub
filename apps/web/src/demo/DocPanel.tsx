import { useEffect, useRef } from "react";
import { Badge } from "@/components/ui/badge";
import { MethodBadge } from "./method";
import { DOCS } from "./data";

export function DocPanel({ docId }: { docId: string }) {
  const doc = DOCS.find((d) => d.id === docId) ?? DOCS[0];
  const topRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    topRef.current?.scrollTo({ top: 0 });
  }, [docId]);

  function jumpTo(id: string) {
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  return (
    <div ref={topRef} className="h-full min-h-0 overflow-auto">
      <div className="mx-auto grid max-w-[1120px] grid-cols-[minmax(0,1fr)_208px] gap-10 px-10 py-10">
        <article className="min-w-0">
          <h1 className="text-[26px] font-semibold tracking-tight">{doc.title}</h1>
          <p className="mt-2 mb-6 text-[13.5px] text-muted-foreground">{doc.lede}</p>

          {doc.sections.map((s) => (
            <section key={s.id} className="scroll-mt-8" id={s.id}>
              <h2 className="mt-7 mb-2 text-lg font-semibold tracking-tight">{s.title}</h2>
              {s.paras.map((p, i) => (
                <p key={i} className="mb-2.5 leading-relaxed text-[14px] text-foreground/85">{p}</p>
              ))}
              {s.code && (
                <pre className="my-4 overflow-x-auto rounded-lg border bg-muted/50 p-4 font-mono text-[12.5px] leading-relaxed">{s.code}</pre>
              )}
              {s.subs?.map((sub) => (
                <div key={sub.id} className="mt-4" id={sub.id}>
                  <h3 className="mb-1.5 text-[15px] font-semibold">{sub.title}</h3>
                  {sub.paras.map((p, i) => (
                    <p key={i} className="mb-2 leading-relaxed text-[14px] text-foreground/85">{p}</p>
                  ))}
                </div>
              ))}
            </section>
          ))}

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
          <div className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">本页目录</div>
          <ol className="flex flex-col gap-1 border-l border-border pl-3">
            {doc.sections.map((s) => (
              <li key={s.id}>
                <button
                  onClick={() => jumpTo(s.id)}
                  className="block py-0.5 text-left text-[12.5px] text-muted-foreground transition-colors hover:text-primary"
                >
                  {s.title}
                </button>
                {s.subs && (
                  <ul className="ml-3 flex flex-col">
                    {s.subs.map((x) => (
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
        </nav>
      </div>
    </div>
  );
}