# @spechub/web

H 组 Web（由 `apps/web-spike` 于 2026-11-14 转正）。Vite 6 + React 19 + TypeScript + Tailwind v4 + [shadcn/ui](https://ui.shadcn.com)（Base UI 引擎）。

## 状态（开发计划 H 组）

- **H1** 三区壳 + 侧栏分段控件（接口 / AI 文档）✅
- **H2** 项目 / 仓库 / 接口列表（读）✅ —— 接后端 `GET /api/projects|repos|apis`；tag / repo 过滤 + 搜索；侧栏 sync 状态
- **H3** 接口详情三段式 ✅ —— 契约从 `specJson` 前端解析渲染（Parameters 表 + Responses + schema 递归）；Doc 域**真 Markdown 渲染**（react-markdown + remark-gfm，标题索引从 md 提取）
- **H4** 编辑即提交 ✅ —— 双栏编辑器保存 = 强制 diff 确认（Monaco DiffEditor + commit message）→ `PUT /api/repos/{id}/spec` 写回真源（platform commit → 自动摄取）；e2e 实证
- **H5** Monaco 双栏编辑器 ✅ —— 左 YAML/JSON 编辑（本地打包 monaco，dev/构建均验证）｜右实时渲染预览（`yaml` 解析 → 操作列表 + 复用契约渲染引擎）
- **H6 / C8** 登录与账号安全 ✅ —— 阶段 C：Spring Security 双链 + Spring Session（Redis），密码 / OIDC / 兼容令牌登录；浏览器只持有 HttpOnly 会话 Cookie，CSRF token 仅存内存；账号与安全（资料 / 改密 / 活动会话 / API 令牌）、服务账号、审计日志；Web 端不再持久化长期令牌

## 运行

**方式 A · docker compose（推荐，含 gitea）**：
```bash
docker compose -f compose/docker-compose.yml up -d --build   # 起 gitea(:3000) + backend(:8080)
cd apps/web && npm install && npm run dev
# 打开 http://localhost:5173（5173 被占时 vite 自动顺延端口），/api 代理到 :8080
```

**方式 B · 本地目录档（纯本地 git 仓，无需 docker；2026-11-15 实测通过）**：
```bash
# 1) 构建后端 bootJar
./gradlew :server:bootJar

# 2) 起一个本地 bare origin（真源仓），内含一个 spec（可复用 kernel 黄金样例）
rm -rf /tmp/spechub-origin && git init -q --bare -b main /tmp/spechub-origin
mkdir -p /tmp/spechub-seed/specs/pet
cp modules/kernel/spec-model/src/test/resources/openapi/PetStore-3.1.yaml /tmp/spechub-seed/specs/pet/openapi.yaml
cd /tmp/spechub-seed && git init -q -b main && git add -A \
  && git -c user.name=t -c user.email=t@x commit -qm init && git remote add origin /tmp/spechub-origin && git push -q origin main

# 3) 启动 backend（本地目录档）
SPECHUB_GIT_SOURCE_DIR=/tmp/spechub-gitsource SPECHUB_SQLITE_DIR=/tmp/spechub-sqlite \
  SPECHUB_ARTIFACTS_DIR=/tmp/spechub-artifacts \
  java -jar modules/server/build/libs/spechub-server.jar --server.port=18080 &

# 4) 注册 repo（本地目录指向 bare 仓）
curl -X POST http://localhost:18080/api/repos -H 'Content-Type: application/json' \
  -d '{"url":"/tmp/spechub-origin"}'
# → 返回 apiCount:3 schemaCount:1 versionCount:1，即摄取已跑通

# 5) 起前端，代理指向本地 backend
cd apps/web && VITE_API_BASE=http://localhost:18080 npm run dev
# 打开 http://localhost:5174 → 侧栏应见仓库 + 接口列表；点「编辑此文件」进 Monaco 双栏编辑器
```

**build**：`cd apps/web && npm run build`（monaco 独立 chunk）。

**登录（阶段 C / C8）**：所有环境都必须真实登录（后端要求配置 `SPECHUB_ADMIN_TOKEN`、Redis 可用）。前端启动即探测 `/api/auth/status` + `/api/auth/me`，无有效会话弹出登录框：

- 默认：用户名或邮箱 + 密码；
- OIDC 启用时显示「使用 SSO 登录」；Vite dev 已代理 `/oauth2`、`/login/oauth2`（OIDC 回调必须与前端同源，否则 Cookie / 回调会落到错误端口）；
- 兼容 admin-token / 用户 API 令牌：展开「使用令牌登录」。令牌只用于本次 `/api/auth/login`，**不写入 localStorage**；历史 `spechub.admin-token` 键在加载时清除。

前端请求一律同源（`/api`，Vite 代理到 `VITE_API_BASE`），`credentials: "same-origin"` 携带会话 Cookie；非 GET/HEAD 请求自动带 `X-CSRF-Token`。本地 dev 可设 `VITE_DEV_ADMIN_TOKEN=<token>` 预填兼容令牌输入框（仅 dev 构建注入，不进生产包）。

**运行自检（冒烟命令）**：起好后可快速验证读写链路
```bash
curl localhost:18080/api/repos/1/specs          # 应列 ["specs/pet/openapi.yaml"]
curl "localhost:18080/api/apis/1" | grep specPath  # 详情应带 specPath
```
⚠ 本会话为纯文本模型，验证止于 HTTP 层（模块可加载 + 数据链路通 + 写回闭环）；Monaco 交互/布局需浏览器肉眼确认。

## 对接说明

- `src/lib/api.ts`：REST 客户端，类型对齐 `modules/kernel/spec-index .../Records.kt`。
- `specJson` = 归一化后**单个 Operation** 的 JSON；详情页 `JSON.parse` 后直接渲染 `parameters` / `responses`。
- 编辑器读文件走 `GET /repos/{id}/specs|spec`（SpecWriter 工作树），写回走 `PUT /repos/{id}/spec`。
- 详情页「编辑此文件」用 `GET /apis/{id}` 返回的 `specPath`（source spec 文件路径，后端 join version 带出）直达编辑器。
- 溯源区的 `specHash` / 最近 commit 需后端小扩展（docs/SpecHub-UI方案.md §6.2）。
- AI 文档域目前为内置样例（`src/demo/data.ts`，markdown 文本），P2 K 摄取落地后切换后端。
- Monaco 本地打包（`src/lib/monaco.ts`）：`loader.config` 指向本地 esm、`?worker` 独立 chunk，离线/内网可用；构建时 monaco 单独拆 chunk 不阻塞首屏。