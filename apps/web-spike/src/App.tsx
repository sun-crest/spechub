import { useEffect, useState } from "react";
import { Boxes, ChevronsUpDown, Moon, PanelLeftClose, Search, Shield, Sun } from "lucide-react";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ScrollArea } from "@/components/ui/scroll-area";
import { ApiList } from "./demo/ApiList";
import { ApiDetail } from "./demo/ApiDetail";
import { DocPanel } from "./demo/DocPanel";
import { APIs, DOCS } from "./demo/data";

const ALL = "全部";

export default function App() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains("dark"));
  const [domain, setDomain] = useState("api");
  const [selected, setSelected] = useState<string | null>(null);
  const [tag, setTag] = useState(ALL);
  const [selectedDoc, setSelectedDoc] = useState("overview");
  const [siderCollapsed, setSiderCollapsed] = useState(false);

  useEffect(() => {
    document.documentElement.classList.toggle("dark", dark);
  }, [dark]);

  // tag 树（按仓库分组）
  const cnt = (t: string) => APIs.filter((a) => a.tag === t).length;
  const repoTags: Array<[string, string[]]> = [
    ["petstore", ["宠物", "商店", "用户", "Webhook"]],
    ["billing-micro", ["计费"]],
  ];

  return (
    <div className="flex h-svh bg-background text-foreground">
      {/* 侧栏 */}
      <aside className={`shrink-0 flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground ${siderCollapsed ? "hidden" : "flex"} w-60`}>
        <div className="p-3 pb-2">
          <Tabs value={domain} onValueChange={setDomain} className="w-full">
            <TabsList className="w-full bg-muted">
              <TabsTrigger value="api" className="flex-1">接口</TabsTrigger>
              <TabsTrigger value="doc" className="flex-1">AI 文档</TabsTrigger>
            </TabsList>
          </Tabs>
        </div>

        {domain === "api" ? (
          <ScrollArea className="min-h-0 flex-1 px-2">
            <div className="flex flex-col gap-4">
              {repoTags.map(([repo, tags]) => (
                <div key={repo}>
                  <div className="mb-1 flex items-center gap-2 px-2.5 py-1.5 text-sm">
                    <Boxes className="size-4 shrink-0" />
                    <span className="flex-1 truncate">{repo}</span>
                    <span className="size-2 shrink-0 rounded-full bg-green-500" aria-hidden="true" />
                    <span className="text-xs text-muted-foreground">ok</span>
                  </div>
                  <div className="flex flex-col gap-0.5 pl-3">
                    <button
                      onClick={() => setTag(ALL)}
                      className={`flex items-center rounded-md px-2.5 py-1.5 text-sm transition-colors ${
                        tag === ALL ? "bg-primary/8 font-medium text-primary" : "text-muted-foreground hover:bg-sidebar-accent"
                      }`}
                    >
                      <span className="flex-1">全部</span>
                      <span className="font-mono text-xs">{APIs.length}</span>
                    </button>
                    {tags.map((t) => (
                      <button
                        key={t}
                        onClick={() => setTag(t)}
                        className={`flex items-center rounded-md px-2.5 py-1.5 text-sm transition-colors ${
                          tag === t ? "bg-primary/8 font-medium text-primary" : "text-muted-foreground hover:bg-sidebar-accent"
                        }`}
                      >
                        <span className="flex-1">{t}</span>
                        <span className="font-mono text-xs">{cnt(t)}</span>
                      </button>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </ScrollArea>
        ) : (
          <ScrollArea className="min-h-0 flex-1 px-2">
            <div className="flex flex-col gap-0.5">
              <div className="px-2.5 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">docs/</div>
              {DOCS.map((d) => (
                <button
                  key={d.id}
                  onClick={() => setSelectedDoc(d.id)}
                  className={`flex flex-col gap-0.5 rounded-md px-2.5 py-2 text-left text-sm transition-colors ${
                    d.id === selectedDoc ? "bg-primary/8 text-primary" : "text-foreground/80 hover:bg-sidebar-accent"
                  }`}
                >
                  <span className="font-mono text-[12.5px]">{d.name}</span>
                  <span className={`text-[11.5px] ${d.id === selectedDoc ? "text-primary/70" : "text-muted-foreground"}`}>{d.title}</span>
                </button>
              ))}
            </div>
          </ScrollArea>
        )}
      </aside>

      {/* 主区 */}
      <div className="flex min-w-0 flex-1 flex-col">
        {/* 顶栏 */}
        <header className="flex h-16 shrink-0 items-center gap-3 border-b border-border/70 px-6">
          <button
            className="grid size-8 place-items-center rounded-md text-muted-foreground hover:bg-muted"
            aria-label="折叠侧栏"
            onClick={() => setSiderCollapsed((s) => !s)}
          >
            <PanelLeftClose className="size-4" />
          </button>
          <div className="flex items-center gap-2 font-semibold tracking-tight">
            <Shield className="size-5 shrink-0 text-link" />
            SpecHub
          </div>
          <DropdownMenu>
            <DropdownMenuTrigger className="flex items-center gap-1.5 rounded-md border px-2.5 py-1.5 text-[13px] text-muted-foreground hover:bg-muted">
              契书 demo 项目
              <ChevronsUpDown className="size-3.5" aria-hidden="true" />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              <DropdownMenuItem onSelect={() => {}}>petstore</DropdownMenuItem>
              <DropdownMenuItem onSelect={() => {}}>billing-micro</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
          <div className="relative ml-2 w-72">
            <Search className="absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input placeholder="搜索接口 / 文档…" className="bg-muted/60 pl-9 text-[13px] focus-visible:bg-background" />
            <kbd className="absolute top-1/2 right-2.5 -translate-y-1/2 rounded border bg-background px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground">⌘K</kbd>
          </div>
          <div className="ml-auto flex items-center gap-2">
            <Button variant="ghost" size="icon" onClick={() => setDark((d) => !d)} aria-label="切换主题">
              {dark ? <Sun className="size-4" /> : <Moon className="size-4" />}
            </Button>
            <Avatar className="size-8">
              <AvatarFallback className="bg-secondary text-xs font-semibold text-foreground">A</AvatarFallback>
            </Avatar>
          </div>
        </header>

        {/* 内容 */}
        <main className="min-h-0 flex-1">
          {domain === "api" ? (
            selected ? (
              <ApiDetail id={selected} onBack={() => setSelected(null)} />
            ) : (
              <ApiList tag={tag} onTagChange={setTag} onSelect={setSelected} />
            )
          ) : (
            <DocPanel docId={selectedDoc} />
          )}
        </main>
      </div>
    </div>
  );
}