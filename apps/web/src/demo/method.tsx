import { Badge } from "@/components/ui/badge";

/** swagger 方法 → 主题色 class（tokens §3/§8 @theme --color-method-*） */
export const METHOD_CLASS: Record<string, string> = {
  GET: "bg-method-get",
  POST: "bg-method-post",
  PUT: "bg-method-put",
  DELETE: "bg-method-delete",
  PATCH: "bg-method-patch",
};

export function MethodBadge({ method, className }: { method: string; className?: string }) {
  return (
    <Badge
      className={`${METHOD_CLASS[method] ?? "bg-secondary"} border-transparent font-mono text-white ${className ?? ""}`}
    >
      {method}
    </Badge>
  );
}