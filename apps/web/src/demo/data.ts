/** AI 文档域样例（H3：真 Markdown 文本，DocPanel 用 react-markdown 渲染；P2 K 摄取落地后切换后端） */

export interface Doc {
  id: string;
  name: string;
  title: string;
  lede: string;
  markdown: string;
  related: { method: string; path: string }[];
}

export const DOCS: Doc[] = [
  {
    id: "overview",
    name: "overview.md",
    title: "petstore 服务概览",
    lede: "AI 生成的认知文档 · 归属 doc 域（与接口契约分离管理）",
    related: [
      { method: "GET", path: "/api/pets/{id}" },
      { method: "POST", path: "/api/pets" },
      { method: "GET", path: "/api/store/orders" },
    ],
    markdown: `## 用途

提供宠物商店 REST API，对内被移动端、Web 门店与对账系统多端消费。**契约真源在 \`specs/petstore/openapi.yaml\`**，由 SpecHub 摄取并归一化为 OpenAPI 3.1 单一模型。

消费方统一通过 API 网关访问，网关负责限流与鉴权；契约变更需经 draft → review → release 三步，release 即当前快照。

## 快速开始

先 pull 契约到本地，查看是否有未同步改动，再 push。常用三连如下：

\`\`\`bash
$ spechub pull pets:main
$ spechub status   # 3 个接口未同步到本地
$ spechub push
\`\`\`

### CLI 约定

仓库名 \`pets\` 对应 Web 侧标签 **petstore**；tag 同 MCP \`search_api\` 的入参语义。

### 环境名

draft / release 两个视图，P2 前 release = 当前快照。

## 鉴权

除 \`/health\` 外全部接口走 **Bearer 令牌**；令牌由 \`--seed-admin\` 生成，可在管理台轮换。过期返回 401，携带无效 scope 返回 403。

| 头 | 格式 | 说明 |
|---|---|---|
| Authorization | \`Bearer <token>\` | 全接口必需 |
| X-Request-Id | UUID | 可观测追踪，选填 |

## 版本与兼容

契约以 specHash 标注版本；主版本变更（破坏性）会在 release 前经过第三方告警。

## 错误码约定

统一错误结构 \`{ code, message }\`；业务错误用 4xx，服务异常 5xx 且有可观测 traceId。
`,
  },
  {
    id: "auth-guide",
    name: "auth.md",
    title: "鉴权与令牌",
    lede: "认证体系深度说明 · 建议先读 overview",
    related: [
      { method: "GET", path: "/api/users/me" },
      { method: "PUT", path: "/api/repos/{id}/docs/*" },
    ],
    markdown: `## 令牌生命周期

\`--seed-admin\` 生成管理员令牌；普通用户令牌由 MCP 工具 \`create_token\` 发放，均不落库存原文，仅存哈希。

令牌默认 24h 过期，可配置 opt-in 的 refresh token；泄露即在管理台吊销。

## Scope 权限表

admin：全部操作；member：读写其被加入的仓库；viewer：只读。scope 校验在网关层完成。

\`\`\`
admin   api.read api.write repo.admin doc.admin
member  api.read api.write
viewer  api.read
\`\`\`

### 请求头

\`Authorization: Bearer <token>\`；头组参数测试在交互 tab 的 curl 示例中。
`,
  },
  {
    id: "changelog",
    name: "changelog.md",
    title: "变更日志",
    lede: "按 release 整理的契约变更 · 自动生成",
    related: [{ method: "PUT", path: "/api/repos/{id}/docs/*" }],
    markdown: `## v4 · 2026-10

- 新增 \`POST /api/store/orders\` 库存扣减。
- \`GET /api/pets\` 增加 cursor 分页，旧 \`page\` 参数已废弃。
- 错误结构统一为 \`{ code, message }\`（破坏性变更）。

## v3 · 2026-08

引入 specHash 版本标注；启动 draft/release 双视图。

## v2 · 2026-05

Webhook 订阅改为 token 不落库，secret 加密暂存。
`,
  },
  {
    id: "errors-guide",
    name: "errors.md",
    title: "错误码表",
    lede: "全接口统一错误枚举 · 按 HTTP 分类",
    related: [{ method: "DELETE", path: "/api/pets/{id}" }],
    markdown: `## 4xx 业务错误

- 40001 参数校验失败
- 40010 版本冲突（带 expectedVersion）
- 40020 库存不足

\`\`\`
40001  VALIDATION_FAILED
40010  VERSION_CONFLICT
40020  STOCK_INSUFFICIENT
\`\`\`

## 5xx 服务错误

50000 内部错误（响应带 traceId，可对账）。
`,
  },
];