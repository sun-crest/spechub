import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { randomUUID } from "node:crypto";
import { readTools, writeTools } from "./tools.js";
import { config } from "./config.js";

/*
 * SpecHub MCP server 入口（F 组）。
 * - transport：默认 stdio（本地/调试）；`--http` 起 streamable HTTP（remote 优先，§5.2）。
 * - F3 权限：readTools 恒注册；writeTools 仅 SPECHUB_ALLOW_WRITE=1 时注册（默认只读）。
 * - 工具名与该模块的 CLI/bot 语义对得上 F2 工具集 v1。
 */

const server = new McpServer({ name: "spechub", version: "0.1.0" });

function register(tool: { description: string; schema: any; execute: (a: any) => Promise<any> }, name: string) {
  server.registerTool(name, { description: tool.description, inputSchema: tool.schema }, tool.execute);
}

for (const [name, tool] of Object.entries(readTools)) register(tool, name);
if (config.allowWrite) {
  for (const [name, tool] of Object.entries(writeTools)) {
    server.registerTool(name, { description: tool.description, inputSchema: tool.schema }, tool.execute);
  }
}

async function main() {
  if (process.argv.includes("--http")) {
    await serveHttp();
  } else {
    const transport = new StdioServerTransport();
    await server.connect(transport);
    process.stderr.write(`[spechub-mcp] stdio 已就绪（allowWrite=${config.allowWrite}\n`);
  }
}

/** streamable HTTP：会话按 sessionId 管理（node:http 最小实现；SDK 1.32 onsessioninitialized 回调只带 sessionId 字符串） */
async function serveHttp() {
  const { createServer } = await import("node:http");
  const sessions = new Map<string, StreamableHTTPServerTransport>();

  const httpServer = createServer(async (req, res) => {
    try {
      const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);

      if (url.pathname.endsWith("/health")) {
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.end("ok");
        return;
      }
      if (url.pathname !== "/mcp") {
        res.writeHead(404, { "Content-Type": "text/plain" });
        res.end("not found");
        return;
      }

      const explicit = url.searchParams.get("sessionId");

      if (req.method === "DELETE") {
        if (explicit) {
          const t = sessions.get(explicit);
          if (t) {
            await t.close();
            sessions.delete(explicit);
          }
        }
        res.writeHead(200);
        res.end();
        return;
      }

      let transport = explicit ? sessions.get(explicit) : undefined;
      if (!transport) {
        const id = explicit ?? randomUUID();
        transport = new StreamableHTTPServerTransport({
          sessionIdGenerator: () => id,
          onsessioninitialized: (sid) => {
            sessions.set(sid, transport!);
          },
        });
      }
      await transport.handleRequest(req, res);
    } catch (e) {
      console.error("[spechub-mcp] http 处理错误:", e);
      res.writeHead(500, { "Content-Type": "text/plain" });
      res.end("internal error");
    }
  });

  httpServer.listen(config.httpPort, () => {
    process.stderr.write(`[spechub-mcp] streamable HTTP 已就绪 :${config.httpPort}/mcp（allowWrite=${config.allowWrite}\n`);
  });
}

main().catch((e) => {
  console.error("[spechub-mcp] 启动失败:", e);
  process.exit(1);
});