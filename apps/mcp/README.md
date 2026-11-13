# apps/mcp —— MCP server（P1 F 组）

当前推荐 **TS(Node)**：贴 MCP 官方 SDK 生态、新能力先落地；**remote HTTP 优先**，stdio 仅单机/调试。

- F1 脚手架 + MCP TS SDK（streamable HTTP）
- F2 工具集 v1：`list_projects / search_api / get_api / create_api / update_api / doc_sync_status`
- F3 权限分层：默认只读，写操作需确认（对接 server 审批开关，§6.4 决策 3）

只连接 `spec-client`，不依赖 kernel。骨架阶段占位。