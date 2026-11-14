export interface Param {
  name: string;
  in: string;
  type: string;
  required?: boolean;
  desc: string;
}

export interface SchemaProp {
  name: string;
  type: string;
  doc?: string;
}

export interface Resp {
  code: string;
  desc: string;
  props: SchemaProp[];
}

export interface Api {
  id: string;
  method: string;
  path: string;
  summary: string;
  tag: string;
  repo: string;
  version: number;
  desc: string;
  params: Param[];
  responses: Resp[];
}

export const TAGS = ["宠物", "商店", "用户", "计费", "仓库", "Webhook"];

export const APIs: Api[] = [
  {
    id: "list-pets", method: "GET", path: "/api/pets", summary: "列出宠物（分页 + 状态过滤）", tag: "宠物", repo: "petstore", version: 96,
    desc: "返回宠物分页列表，支持 status / limit / cursor 过滤。契约真源 specs/petstore/openapi.yaml。",
    params: [
      { name: "status", in: "query", type: "string", desc: "available | sold，默认全部" },
      { name: "limit", in: "query", type: "integer", desc: "每页数量，默认 20，最大 100" },
      { name: "cursor", in: "query", type: "string", desc: "分页游标" },
    ],
    responses: [
      { code: "200", desc: "分页结果", props: [
        { name: "items[]", type: "Pet", doc: "宠物数组" },
        { name: "nextCursor", type: "string|null", doc: "下一页游标" },
        { name: "total", type: "integer", doc: "命中总数" },
      ]},
    ],
  },
  {
    id: "get-pet", method: "GET", path: "/api/pets/{id}", summary: "查询宠物详情", tag: "宠物", repo: "petstore", version: 121,
    desc: "按 ID 返回宠物完整信息，含 owner 与医疗记录。",
    params: [
      { name: "id", in: "path", type: "integer", required: true, desc: "宠物 ID，如 42" },
      { name: "verbose", in: "query", type: "boolean", desc: "是否返回医疗记录" },
    ],
    responses: [
      { code: "200", desc: "宠物详情", props: [
        { name: "id", type: "integer" },
        { name: "name", type: "string", doc: "如 契书" },
        { name: "status", type: "enum", doc: "available / sold" },
        { name: "owner", type: "User|null" },
      ]},
      { code: "404", desc: "宠物不存在", props: [{ name: "code", type: "string" }, { name: "message", type: "string" }] },
    ],
  },
  {
    id: "create-pet", method: "POST", path: "/api/pets", summary: "创建宠物", tag: "宠物", repo: "petstore", version: 47,
    desc: "写入 specs/petstore/openapi.yaml 并提交；返回创建的宠物。",
    params: [
      { name: "body", in: "body", type: "PetInput", required: true, desc: "name / status(可选) / tagIds" },
    ],
    responses: [
      { code: "201", desc: "创建成功", props: [
        { name: "id", type: "integer" }, { name: "name", type: "string" }, { name: "status", type: "string" },
      ]},
      { code: "422", desc: "校验失败", props: [{ name: "errors[]", type: "object" }] },
    ],
  },
  {
    id: "update-pet", method: "PUT", path: "/api/pets/{id}", summary: "更新宠物（全量）", tag: "宠物", repo: "petstore", version: 23,
    desc: "PUT 为全量更新；部分字段请用 PATCH。",
    params: [
      { name: "id", in: "path", type: "integer", required: true, desc: "宠物 ID" },
      { name: "body", in: "body", type: "PetInput", required: true, desc: "全量字段" },
    ],
    responses: [
      { code: "200", desc: "更新后宠物", props: [{ name: "id", type: "integer" }, { name: "name", type: "string" }] },
      { code: "409", desc: "版本冲突（version 不匹配）", props: [{ name: "expectedVersion", type: "integer" }] },
    ],
  },
  {
    id: "delete-pet", method: "DELETE", path: "/api/pets/{id}", summary: "删除宠物", tag: "宠物", repo: "petstore", version: 12,
    desc: "删除后返回 204；误删不可恢复，需在溯源页确认。",
    params: [{ name: "id", in: "path", type: "integer", required: true, desc: "宠物 ID" }],
    responses: [{ code: "204", desc: "已删除", props: [] }],
  },
  {
    id: "list-orders", method: "GET", path: "/api/store/orders", summary: "列出订单", tag: "商店", repo: "petstore", version: 31,
    desc: "商店域订单列表，按创建时间倒序。",
    params: [{ name: "petId", in: "query", type: "integer", desc: "按宠物筛订单" }],
    responses: [{ code: "200", desc: "订单数组", props: [
      { name: "items[]", type: "Order", doc: "订单数组" },
      { name: "count", type: "integer" },
    ]}],
  },
  {
    id: "create-order", method: "POST", path: "/api/store/orders", summary: "下单（扣库存）", tag: "商店", repo: "petstore", version: 18,
    desc: "创建订单并扣减库存；库存不足返回 409。",
    params: [{ name: "body", in: "body", type: "OrderInput", required: true, desc: "petId + quantity" }],
    responses: [
      { code: "201", desc: "下单成功", props: [{ name: "id", type: "integer" }, { name: "shipDate", type: "string" }] },
      { code: "409", desc: "库存不足", props: [{ name: "available", type: "integer" }] },
    ],
  },
  {
    id: "get-me", method: "GET", path: "/api/users/me", summary: "当前登录用户", tag: "用户", repo: "petstore", version: 33,
    desc: "Bearer 令牌对应账号信息，不落日志。",
    params: [{ name: "X-Token", in: "header", type: "string", desc: "实际走 Authorization" }],
    responses: [{ code: "200", desc: "用户信息", props: [
      { name: "id", type: "integer" }, { name: "name", type: "string" }, { name: "role", type: "enum", doc: "admin / member" },
      { name: "repos[]", type: "string", doc: "可访问仓库名" },
    ]}],
  },
  {
    id: "get-user", method: "GET", path: "/api/users/{id}", summary: "用户信息（admin）", tag: "用户", repo: "petstore", version: 8,
    desc: "仅 admin 可查其他用户。",
    params: [{ name: "id", in: "path", type: "integer", required: true, desc: "用户 ID" }],
    responses: [{ code: "200", desc: "用户信息", props: [{ name: "id", type: "integer" }, { name: "name", type: "string" }] }],
  },
  {
    id: "list-invoices", method: "GET", path: "/api/invoices", summary: "发票列表", tag: "计费", repo: "billing-micro", version: 19,
    desc: "按月份聚合的计费发票；billing-micro 仓库自动摄取。",
    params: [{ name: "month", in: "query", type: "string", required: true, desc: "YYYY-MM" }],
    responses: [{ code: "200", desc: "发票数组", props: [
      { name: "items[]", type: "Invoice" }, { name: "totalAmount", type: "number" },
    ]}],
  },
  {
    id: "create-invoice", method: "POST", path: "/api/invoices", summary: "生成当月发票", tag: "计费", repo: "billing-micro", version: 11,
    desc: "幂等：当月已存在则返回现有发票。",
    params: [{ name: "body", in: "body", type: "object", required: true, desc: "{ month }" }],
    responses: [{ code: "201", desc: "生成/已存在", props: [{ name: "id", type: "integer" }, { name: "month", type: "string" }] }],
  },
  {
    id: "list-plans", method: "GET", path: "/api/plans", summary: "套餐列表", tag: "计费", repo: "billing-micro", version: 7,
    desc: "当前可选套餐及价目。",
    params: [],
    responses: [{ code: "200", desc: "套餐数组", props: [{ name: "items[]", type: "Plan" }, { name: "currency", type: "string" }] }],
  },
  {
    id: "update-plan", method: "PUT", path: "/api/plans/{id}", summary: "改套餐", tag: "计费", repo: "billing-micro", version: 5,
    desc: "改套餐下周期生效；即时切换需有权限。",
    params: [{ name: "id", in: "path", type: "integer", required: true, desc: "套餐 ID" }],
    responses: [{ code: "200", desc: "切换结果", props: [{ name: "effectiveFrom", type: "string" }] }],
  },
  {
    id: "create-webhook", method: "POST", path: "/api/webhooks", summary: "注册 webhook（token 不落库）", tag: "Webhook", repo: "petstore", version: 21,
    desc: "注册仓库变更事件回调；secret 仅加密后暂存。",
    params: [{ name: "body", in: "body", type: "object", required: true, desc: "{ url, events[] }" }],
    responses: [{ code: "201", desc: "已注册", props: [
      { name: "id", type: "string" }, { name: "events[]", type: "string" }, { name: "secretHint", type: "string" },
    ]}],
  },
  {
    id: "delete-webhook", method: "DELETE", path: "/api/webhooks/{id}", summary: "删除 webhook 订阅", tag: "Webhook", repo: "petstore", version: 2,
    desc: "删除后不再接收事件回调。",
    params: [{ name: "id", in: "path", type: "string", required: true, desc: "webhook ID" }],
    responses: [{ code: "204", desc: "已删除", props: [] }],
  },
  {
    id: "put-doc", method: "PUT", path: "/api/repos/{id}/docs/*", summary: "写回文档 → commit → push → 自动摄取", tag: "仓库", repo: "petstore", version: 6,
    desc: "把 docs/ 下的 Markdown 写回 git 真源，触发 doc 域摄取。",
    params: [{ name: "id", in: "path", type: "string", required: true, desc: "仓库名" }],
    responses: [{ code: "200", desc: "写回结果", props: [
      { name: "commit", type: "string" }, { name: "pushed", type: "boolean" },
    ]}],
  },
];

export const PROVENANCE: Array<[string, string]> = [
  ["归属仓", "petstore"],
  ["分支", "main"],
  ["specHash", "3f2a9c1b6e7d"],
  ["version", "84 → 85（released）"],
  ["最近 commit", "0fb6e560 · chore(spechub): update /api/apis"],
];

/* ───────────────────────── AI 文档域 ───────────────────────── */

export interface DocSection {
  id: string;
  title: string;
  paras: string[];
  code?: string;
  subs?: { id: string; title: string; paras: string[] }[];
}

export interface Doc {
  id: string;
  name: string;
  title: string;
  lede: string;
  sections: DocSection[];
  related: { method: string; path: string }[];
}

export const DOCS: Doc[] = [
  {
    id: "overview", name: "overview.md", title: "petstore 服务概览",
    lede: "AI 生成的认知文档 · 归属 doc 域（与接口契约分离管理）",
    sections: [
      {
        id: "purpose", title: "用途",
        paras: [
          "提供宠物商店 REST API，对内被移动端、Web 门店与对账系统多端消费。契约真源在 specs/petstore/openapi.yaml，由 SpecHub 摄取并归一化为 OpenAPI 3.1 单一模型。",
          "消费方统一通过 API 网关访问，网关负责限流与鉴权；契约变更需经 draft → review → release 三步，release 即当前快照。",
        ],
      },
      {
        id: "quickstart", title: "快速开始",
        paras: ["先 pull 契约到本地，查看是否有未同步改动，再 push。常用三连如下。"],
        code: "$ spechub pull pets:main\n$ spechub status   # 3 个接口未同步到本地\n$ spechub push",
        subs: [
          { id: "qs-cli", title: "CLI 约定", paras: ["仓库名 pets 对应 Web 侧 gable petstore；tag 同 MCP search_api 的入参语义。"] },
          { id: "qs-env", title: "环境名", paras: ["draft / release 两个视图，P2 前 release = 当前快照。"] },
        ],
      },
      {
        id: "auth", title: "鉴权",
        paras: [
          "除 /health 外全部接口走 Bearer 令牌；令牌由 --seed-admin 生成，可在管理台轮换。过期返回 401，携带无效 scope 返回 403。",
        ],
      },
      {
        id: "versioning", title: "版本与兼容",
        paras: ["契约以 specHash 标注版本；主版本变更（破坏性）会在 release 前经过第三方告警。"],
      },
      {
        id: "errors", title: "错误码约定",
        paras: ["统一错误结构 { code, message }；业务错误用 4xx，服务异常 5xx 且有可观测 traceId。"],
      },
    ],
    related: [
      { method: "GET", path: "/api/pets/{id}" },
      { method: "POST", path: "/api/pets" },
      { method: "GET", path: "/api/store/orders" },
    ],
  },
  {
    id: "auth-guide", name: "auth.md", title: "鉴权与令牌",
    lede: "认证体系深度说明 · 建议先读 overview",
    sections: [
      {
        id: "tokens", title: "令牌生命周期",
        paras: [
          "--seed-admin 生成管理员令牌；普通用户令牌由 MCP 工具 create_token 发放，均不落库存原文，仅存哈希。",
          "令牌默认 24h 过期，可配置 opt-in 的 refresh token；泄露即在管理台吊销。",
        ],
      },
      {
        id: "scopes", title: "Scope 权限表",
        paras: ["admin：全部操作；member：读写其被加入的仓库；viewer：只读。scope 校验在网关层完成。"],
        code: "admin   api.read api.write repo.admin doc.admin\nmember  api.read api.write\nviewer  api.read",
      },
      { id: "header", title: "请求头", paras: ["Authorization: Bearer <token>；头组参数测试在交互 tab 的 curl 示例中。"] },
    ],
    related: [
      { method: "GET", path: "/api/users/me" },
      { method: "PUT", path: "/api/repos/{id}/docs/*" },
    ],
  },
  {
    id: "changelog", name: "changelog.md", title: "变更日志",
    lede: "按 release 整理的契约变更 · 自动生成",
    sections: [
      {
        id: "v4", title: "v4 · 2026-10",
        paras: ["新增 POST /api/store/orders 库存扣减。", "GET /api/pets 增加 cursor 分页，旧 page 参数已废弃。", "错误结构统一为 { code, message }（破坏性变更）。"],
      },
      { id: "v3", title: "v3 · 2026-08", paras: ["引入 specHash 版本标注；启动 draft/release 双视图。"] },
      { id: "v2", title: "v2 · 2026-05", paras: ["Webhook 订阅改为 token 不落库，secret 加密暂存。"] },
    ],
    related: [{ method: "PUT", path: "/api/repos/{id}/docs/*" }],
  },
  {
    id: "errors-guide", name: "errors.md", title: "错误码表",
    lede: "全接口统一错误枚举 · 按 HTTP 分类",
    sections: [
      {
        id: "4xx", title: "4xx 业务错误",
        paras: ["40001 参数校验失败 / 40010 版本冲突（带 expectedVersion）/ 40020 库存不足。"],
        code: "40001  VALIDATION_FAILED\n40010  VERSION_CONFLICT\n40020  STOCK_INSUFFICIENT",
      },
      { id: "5xx", title: "5xx 服务错误", paras: ["50000 内部错误（响应带 traceId，可对账）。"] },
    ],
    related: [{ method: "DELETE", path: "/api/pets/{id}" }],
  },
];