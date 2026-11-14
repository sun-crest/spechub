// F 组 e2e：MCP create_api 写回真实 gitea 仓 → 摄取 → 可检索
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { readFileSync } from "node:fs";

const BASE = "http://localhost:8080";
const TOK = readFileSync("/tmp/gitea-token", "utf8").trim();

// 1) 恢复 repo#2（gitea）的 token override + 摄取（backend 重启后内存 registry 已清）
const url = "http://gitea:3000/spechubadmin/petstore.git";
const reg = await fetch(`${BASE}/api/repos`, {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ url, token: TOK }),
}).then((r) => r.json());
console.log("E 注册/恢复 repo#2 → apiCount:", reg.apiCount);

// 2) MCP stdio 会话（允许写）
const transport = new StdioClientTransport({
  command: "node", args: ["dist/index.js"],
  env: { ...process.env, SPECHUB_BASE_URL: BASE, SPECHUB_ALLOW_WRITE: "1" },
});
const client = new Client({ name: "e2e", version: "0.0.0" });
await client.connect(transport);

const tools = await client.listTools();
console.log("E 工具列表:", tools.tools.map((t) => t.name).join(", "));

// 3) create_api：写一个新 spec 文件到 gitea（平台提交 push → 摄取）
const specDoc = [
  "openapi: 3.1.0",
  'info: { title: MCP Demo, version: "1.0.0" }',
  "paths:",
  "  /widgets:",
  "    get:",
  "      operationId: listWidgets",
  "      tags: [mcp]",
  '      responses: { "200": { description: ok } }',
].join("\n");
const created = await client.callTool({
  name: "create_api",
  arguments: { repo_id: reg.repo.id, path: "specs/mcp-demo/openapi.yaml", content: specDoc },
});
console.log("E create_api →", created.content.map((c) => c.text).join("\n"));

// 4) 检索新接口
await new Promise((r) => setTimeout(r, 2000));
const found = await client.callTool({ name: "search_api", arguments: { q: "listWidgets" } });
console.log("E search_api(listWidgets) →", found.content.map((c) => c.text).join("\n").slice(0, 200));

await client.close();
console.log("E2E OK");
