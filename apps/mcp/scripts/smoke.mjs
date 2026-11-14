import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const transport = new StdioClientTransport({
  command: "node", args: ["dist/index.js"],
  env: { ...process.env, SPECHUB_BASE_URL: "http://localhost:8080" },
});
const client = new Client({ name: "smoke", version: "0.0.0" });
await client.connect(transport);

const tools = await client.listTools();
console.log("T 工具列表:", tools.tools.map((t) => t.name).join(", "));
const p = await client.callTool({ name: "list_projects", arguments: {} });
console.log("T list_projects:", p.content.map((c) => c.text).join("\n").slice(0, 200));
const s = await client.callTool({ name: "search_api", arguments: { q: "listPets" } });
console.log("T search_api(listPets):", s.content.map((c) => c.text).join("\n").slice(0, 200));
await client.close();
console.log("SMOKE OK");
