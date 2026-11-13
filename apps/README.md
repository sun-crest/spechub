# apps/ —— 端侧接入层（P1 起；语言单列可换，见 §7.4）

- `web/`        React + Vite + Monaco 双栏 UI（P1 H 组：列表/详情/编辑器）
- `mcp/`        MCP server —— 当前推荐 TS(Node)，remote HTTP 优先（P1 F 组）
- `cli/`        CLI —— 当前推荐 Go，单二进制进 CI 零依赖（P1 G 组）
- `spec-client/` server API 生成的薄 client（TS / Go 各一份），供 mcp / cli / vscode 复用，近零维护

TS 侧走 npm workspace，Go 侧走 Go module，各自独立构建，物理分目录。
**硬规则**：TS 侧与 Go 侧永不 import 任何 JVM 产物，只走 HTTP / CLI 子进程连接 server。

骨架阶段为占位；各子目录在对应工作包落地时建立脚手架。