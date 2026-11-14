import { z } from "zod";
import { spechub } from "./spechubClient.js";

/**
 * F2 工具集 v1 + F3 权限分层。
 * 读工具（readTools）默认可用；写工具（writeTools）仅在 SPECHUB_ALLOW_WRITE=1 时注册，
 * 且 handler 本身就是对真源仓的 commit/push（写操作皆审由 MCP 托管侧确认）。
 */

type Tool = {
  description: string;
  schema: z.ZodType;
  execute: (args: any) => Promise<TextResult>;
};

type TextResult = { content: Array<{ type: "text"; text: string }> };

const text = (s: string): TextResult => ({ content: [{ type: "text", text: s }] });
const asList = (a: unknown): Array<Record<string, any>> => (Array.isArray(a) ? a : []);
const orEmpty = (v: unknown): string => (v == null ? "" : String(v));

export const readTools: Record<string, Tool> = {
  list_projects: {
    description: "列出 SpecHub 全部项目空间",
    schema: z.object({}),
    execute: async () => {
      const projects = await spechub.listProjects();
      if (projects.length === 0) return text("（无项目）");
      return text(projects.map((p) => `#${p.id} ${p.name}`).join("\n"));
    },
  },

  search_api: {
    description: "按关键字/HTTP 方法检索归一化接口（全仓投影）",
    schema: z.object({
      q: z.string().optional().describe("关键字：匹配 path/operationId/summary"),
      method: z.string().optional().describe("HTTP 方法：GET/POST/PUT/DELETE…"),
    }),
    execute: async ({ q, method }) => {
      const apis = await spechub.searchApis(q, method);
      if (apis.length === 0) return text("（未命中）");
      return text(
        asList(apis)
          .map((a) => `#${a.id} ${a.method} ${a.path}\top=${orEmpty(a.operationId)}\ttag=${orEmpty(a.tag)}\trepo#${a.repoId}`)
          .join("\n"),
      );
    },
  },

  get_api: {
    description: "取单个接口的归一化契约明细（含 request/response 结构）",
    schema: z.object({ id: z.number().int().describe("api 投影 id（来自 search_api 的 #id）") }),
    execute: async ({ id }) => {
      const api = await spechub.getApi(Number(id));
      const spec = JSON.parse(api.specJson);
      return text(`#${api.id} ${api.method} ${api.path}\n操作: ${orEmpty(api.operationId)}\n归一化契约:\n${JSON.stringify(spec, null, 2)}`);
    },
  },

  list_specs: {
    description: "列出某真源仓内的 spec 文件（specs/ 目录）",
    schema: z.object({ repo_id: z.number().int().describe("repo 投影 id") }),
    execute: async ({ repo_id }) => {
      const files = await spechub.listSpecs(Number(repo_id));
      return text(files.length ? files.join("\n") : "（空）");
    },
  },

  doc_sync_status: {
    description: "查看各真源仓（repo）的索引同步状态与异常",
    schema: z.object({}),
    execute: async () => {
      const repos = await spechub.listRepos();
      if (repos.length === 0) return text("（无注册仓）");
      return text(
        asList(repos)
          .map((r) => `repo#${r.id} ${r.url}\tbranch=${orEmpty(r.branch)}\t状态=${orEmpty(r.syncStatus)}\t错误=${orEmpty(r.lastError)}`)
          .join("\n"),
      );
    },
  },
};

export const writeTools: Record<string, Tool> = {
  create_api: {
    description: "在指定真源仓创建一份 spec 文档（写入 specs/ 并平台提交 push，随后自动摄取）",
    schema: z.object({
      repo_id: z.number().int().describe("repo 投影 id"),
      path: z.string().describe("仓内路径，如 specs/pet-api/openapi.yaml"),
      content: z.string().describe("OpenAPI 3.0/3.1 文档全文（YAML 或 JSON）"),
    }),
    execute: async ({ repo_id, path, content }) => {
      const r = await spechub.writeSpec(Number(repo_id), path, content);
      return text(`已提交 ${r.commit.slice(0, 12)}（branch ${r.branch}），摄取后 api=${r.apiCount}`);
    },
  },

  update_api: {
    description: "覆写某 spec 文件并触发平台提交（等同 create_api 的 PUT 语义，适合已有文件修订）",
    schema: z.object({
      repo_id: z.number().int(),
      path: z.string().describe("已有 spec 路径，如 specs/pet/openapi.yaml"),
      content: z.string().describe("修订后的 OpenAPI 文档全文"),
    }),
    execute: async ({ repo_id, path, content }) => {
      const r = await spechub.writeSpec(Number(repo_id), path, content);
      return text(`已更新 ${path} → ${r.commit.slice(0, 12)}，摄取后 api=${r.apiCount}`);
    },
  },
};