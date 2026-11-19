# apps/mcp —— SpecHub MCP server（P1 F 组，当前推荐 TS/Node）

Claude Code 接入 SpecHub 契约层的工具集：**读默认、写需显式开启**（F3 权限分层，§6.4 决策 3）。

## 工具集（F2 v1）

| 工具 | 读/写 | 落到 backend |
|---|---|---|
| `list_projects` | 读 | `GET /api/projects` |
| `search_api` | 读 | `GET /api/apis?q=&method=`（全仓投影检索） |
| `get_api` | 读 | `GET /api/apis/{id}`（归一化契约明细） |
| `list_specs` | 读 | `GET /api/repos/{id}/specs` |
| `doc_sync_status` | 读 | `GET /api/repos`（各仓同步状态） |
| `create_api` | 写 | `PUT /api/repos/{id}/spec`（创建 specs 文件→平台 commit→push） |
| `update_api` | 写 | 同上 PUT 语义（修订既有规范文件） |

## 运行

```bash
npm install
npm run build                # → dist/
SPECHUB_BASE_URL=http://localhost:8080 node dist/index.js          # stdio（本地/调试）
node dist/index.js --http    # streamable HTTP（remote 优先），端口 SPECHUB_MCP_PORT 默认 8780 → /mcp
```

### 环境变量

- `SPECHUB_BASE_URL`：backend 基址（默认 `http://localhost:8080`）
- `SPECHUB_TOKEN`：Bearer 令牌——用户 API 令牌（`shu_`）或项目级服务账号令牌（`shs_`）；服务账号只能访问所属项目
- `SPECHUB_ALLOW_WRITE=1`：开启 `create_api` / `update_api` 写工具（**默认只读；写入即对真源仓 commit/push**，Claude 侧会确认）
- `SPECHUB_MCP_PORT`：HTTP 模式监听端口

### 服务账号令牌（C5）

- 只读工具（`list_projects` / `search_api` / `get_api` / `list_specs` / `doc_sync_status`）：服务账号 `viewer` 即可；
- 写工具（`create_api` / `update_api`）：服务账号需 `editor`，且仍需 `SPECHUB_ALLOW_WRITE=1`；
- 令牌过期 / 吊销后 backend 返回 **401 `AUTH_UNAUTHORIZED`**；在 Web「服务账号」轮换密钥后更新 `SPECHUB_TOKEN`，无需改客户端代码。

### 冒烟

```bash
node scripts/smoke.mjs       # stdio 会话：列工具 + list_projects + search_api（连真实 backend）
```

## 设计说明

- transport：stdio 为主（Claude Code 本地案例）；`--http` 走 streamable HTTP（§5.2 remote 优先）。
- 会话（HTTP）：`/mcp` + `sessionId` 复用同一 transport；`DELETE /mcp?sessionId=` 关闭；`/health` 探活。
- 写操作 = 真源仓写回（R1-C）：对 `specs/` 文件执行平台 commit 并 push 回真源分支 → 摄取自动重建投影；写工具仅 `SPECHUB_ALLOW_WRITE=1` 时暴露。
- 只连 `spechub` backend REST（spec-client 薄封装，不碰 JVM 内核）。